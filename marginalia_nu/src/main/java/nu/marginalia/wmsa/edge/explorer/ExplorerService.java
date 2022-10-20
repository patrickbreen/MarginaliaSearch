package nu.marginalia.wmsa.edge.explorer;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.zaxxer.hikari.HikariDataSource;
import lombok.SneakyThrows;
import nu.marginalia.wmsa.configuration.server.Initialization;
import nu.marginalia.wmsa.configuration.server.MetricsServer;
import nu.marginalia.wmsa.configuration.server.Service;
import nu.marginalia.wmsa.renderer.mustache.MustacheRenderer;
import nu.marginalia.wmsa.renderer.mustache.RendererFactory;
import nu.marginalia.wmsa.resource_store.StaticResources;
import org.jetbrains.annotations.NotNull;
import spark.Request;
import spark.Response;
import spark.Spark;

import java.sql.SQLException;
import java.util.*;

public class ExplorerService extends Service {

    private final MustacheRenderer<Object> renderer;
    private final HikariDataSource dataSource;
    private final StaticResources staticResources;

    record SearchResult(
            String domain,
            String url,
            double relatedness,
            boolean hasMore,
            boolean active,
            boolean indexed) implements Comparable<SearchResult> {

        @Override
        public int compareTo(@NotNull SearchResult o) {
            return (int)(o.relatedness - relatedness);
        }
    }

    record SearchResults(String query, String message, String aliasDomain, List<SearchResult> resultList) { }

    @SneakyThrows
    @Inject
    public ExplorerService(@Named("service-host") String ip,
                         @Named("service-port") Integer port,
                         Initialization initialization,
                         MetricsServer metricsServer,
                         RendererFactory rendererFactory,
                         HikariDataSource dataSource,
                           StaticResources staticResources
                           ) {

        super(ip, port, initialization, metricsServer);

        renderer = rendererFactory.renderer("explorer/explorer");
        this.dataSource = dataSource;
        this.staticResources = staticResources;
        Spark.get("/public/", this::serveIndex, this::render);
        Spark.get("/public/search", this::search, this::render);
        Spark.get("/public/:resource", this::serveStatic);

    }


    private Object serveStatic(Request request, Response response) {
        String resource = request.params("resource");
        staticResources.serveStatic("explore", resource, request, response);
        return "";
    }

    public String render(Object results) {
        return renderer.render(results);
    }

    private SearchResults serveIndex(Request request, Response response) {

        return new SearchResults("", "", null, Collections.emptyList());
    }


    private SearchResults search(Request request, Response response) throws SQLException {
        String query = request.queryParams("domain");

        query = trimUrlJunk(query);

        DomainIdInformation domainId = getDomainId(query);
        if (!domainId.isPresent()) {
            return new SearchResults(query,
                    "Could not find such a domain (maybe try adding/removing www?)",
                    null, Collections.emptyList());
        }

        var relatedDomains = getRelatedDomains(domainId);

        if (relatedDomains.isEmpty()) {
            String message =  """
                 I've got nothing. This may either be due to the website being far out in the periphery of Marginalia's
                 search engine index, or it may be due to the website being too big.
                 A few hundred of the biggest websites are excluded for performance reasons. They are usually
                 not very interesting to look at either as everyone links to them and there's no real pattern to discern.
                """;

            return new SearchResults(query, message, domainId.alias, relatedDomains);
        }

        return new SearchResults(query, "", domainId.alias, relatedDomains);
    }

    private List<SearchResult> getRelatedDomains(DomainIdInformation domainIdInformation) throws SQLException {
        List<SearchResult> ret = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                SELECT
                    NV.NEIGHBOR_NAME,
                    NV.RELATEDNESS,
                    (LV.DOMAIN_ID IS NOT NULL),
                    (STATE = 'ACTIVE' OR STATE='SOCIAL_MEDIA'),
                    INDEXED > 0
                FROM EC_NEIGHBORS_VIEW NV
                LEFT JOIN EC_NEIGHBORS_VIEW LV ON (NV.NEIGHBOR_ID=LV.DOMAIN_ID)
                INNER JOIN EC_DOMAIN ON EC_DOMAIN.ID=NV.NEIGHBOR_ID
                WHERE NV.DOMAIN_ID=?
                GROUP BY NV.NEIGHBOR_ID
                ORDER BY NV.RELATEDNESS DESC
                """);
             var stmtRev = conn.prepareStatement("""
                SELECT
                    NV.DOMAIN_NAME,
                    NV.RELATEDNESS,
                    (LV.NEIGHBOR_ID IS NOT NULL),
                    (STATE = 'ACTIVE' OR STATE='SOCIAL_MEDIA'),
                    INDEXED > 0
                FROM EC_NEIGHBORS_VIEW NV
                LEFT JOIN EC_NEIGHBORS_VIEW LV ON (NV.DOMAIN_ID=LV.NEIGHBOR_ID)
                INNER JOIN EC_DOMAIN ON EC_DOMAIN.ID=NV.DOMAIN_ID
                WHERE NV.NEIGHBOR_ID=?
                GROUP BY NV.DOMAIN_ID
                ORDER BY NV.RELATEDNESS DESC
                """
             );

             ) {

            stmt.setInt(1, domainIdInformation.domainId);
            var rsp = stmt.executeQuery();
            while (rsp.next()) {

                String domainName = rsp.getString(1);
                double relatedness = rsp.getDouble(2);
                boolean hasMore = rsp.getBoolean(3);
                boolean active = rsp.getBoolean(4);
                boolean indexed = rsp.getBoolean(5);

                seen.add(domainName);

                String url = "http://" + domainName + "/";


                if (domainName.length() < 48 && domainName.contains(".")) {
                    ret.add(new SearchResult(
                            domainName,
                            url,
                            relatedness,
                            hasMore,
                            active,
                            indexed
                            ));
                }
            }

            stmtRev.setInt(1, domainIdInformation.domainId);
            rsp = stmtRev.executeQuery();
            while (rsp.next()) {

                String domainName = rsp.getString(1);
                double relatedness = rsp.getDouble(2);
                boolean hasMore = rsp.getBoolean(3);
                boolean active = rsp.getBoolean(4);
                boolean indexed = rsp.getBoolean(5);

                String url = "http://" + domainName + "/";

                if (!seen.add(domainName))
                    continue;

                if (domainName.length() < 48 && domainName.contains(".")) {
                    ret.add(new SearchResult(
                            domainName,
                            url,
                            relatedness,
                            hasMore,
                            active,
                            indexed
                    ));
                }
            }
        }

        Comparator<SearchResult> comp = SearchResult::compareTo;
        comp = comp.thenComparing(SearchResult::domain);
        ret.sort(comp);

        return ret;

    }

    private DomainIdInformation getDomainId(String query) throws SQLException {

        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                SELECT IFNULL(ALIAS.ID, DOMAIN.ID), DOMAIN.INDEXED>0 OR ALIAS.INDEXED>0, ALIAS.DOMAIN_NAME 
                FROM EC_DOMAIN DOMAIN 
                LEFT JOIN EC_DOMAIN ALIAS ON DOMAIN.DOMAIN_ALIAS=ALIAS.ID                
                WHERE DOMAIN.DOMAIN_NAME=?
                """)) {
            stmt.setString(1, query);
            var rsp = stmt.executeQuery();
            if (rsp.next()) {
                return new DomainIdInformation(
                        rsp.getInt(1),
                        rsp.getBoolean(2),
                        rsp.getString(3)
                );
            }
        }
        return new DomainIdInformation(-1,  false, null);
    }

    private String trimUrlJunk(String query) {
        if (query.startsWith("http://")) {
            query = query.substring(7);
        }
        if (query.startsWith("https://")) {
            query = query.substring(8);
        }

        int lastSlash = query.indexOf('/');
        if (lastSlash > 0) {
            query = query.substring(0, lastSlash);
        }

        return query;
    }

    record DomainIdInformation(int domainId, boolean indexed, String alias) {
        boolean isPresent() {
            return domainId >= 0;
        }
    }
}
