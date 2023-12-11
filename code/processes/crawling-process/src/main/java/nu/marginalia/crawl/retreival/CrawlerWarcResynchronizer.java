package nu.marginalia.crawl.retreival;

import nu.marginalia.crawl.retreival.fetcher.body.DocumentBodyExtractor;
import nu.marginalia.crawl.retreival.fetcher.body.DocumentBodyResult;
import nu.marginalia.crawl.retreival.fetcher.warc.HttpFetchResult;
import nu.marginalia.crawl.retreival.fetcher.warc.WarcRecorder;
import nu.marginalia.model.EdgeUrl;
import org.jsoup.Jsoup;
import org.netpreserve.jwarc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * This class is responsible for resynchronizing the crawl frontier with a partially written
 * warc file.  This may happen if the crawl is interrupted or crashes.
 * <p>
 * This is best-effort and not guaranteed to recover all data, but it should limit
 * the amount of data that is lost and needs to be re-crawled in the event of an unexpected
 * shutdown.
 */
public class CrawlerWarcResynchronizer {
    private final DomainCrawlFrontier crawlFrontier;
    private final WarcRecorder recorder;
    private static final Logger logger = LoggerFactory.getLogger(CrawlerWarcResynchronizer.class);
    public CrawlerWarcResynchronizer(DomainCrawlFrontier crawlFrontier, WarcRecorder recorder) {
        this.crawlFrontier = crawlFrontier;
        this.recorder = recorder;
    }

    public void run(Path tempFile) {
        // First pass, enqueue links
        try (var reader = new WarcReader(tempFile)) {
            for (var item : reader) {
                accept(item);
            }
        } catch (IOException e) {
            logger.info(STR."Failed read full warc file \{tempFile}", e);
        }

        // Second pass, copy records to the new warc file
        try (var reader = new WarcReader(tempFile)) {
            for (var item : reader) {
                recorder.resync(item);
            }
        } catch (IOException e) {
            logger.info(STR."Failed read full warc file \{tempFile}", e);
        }
    }

    public void accept(WarcRecord item) {
        try {
            if (item instanceof WarcResponse rsp) {
                response(rsp);
            } else if (item instanceof WarcRevisit revisit) {
                revisit(revisit);
            } else if (item instanceof WarcRequest req) {
                request(req);
            }
        }
        catch (Exception ex) {
            logger.info(STR."Failed to process warc record \{item}", ex);
        }
    }

    private void request(WarcRequest request) {
        EdgeUrl.parse(request.target()).ifPresent(crawlFrontier::addVisited);
    }

    private void response(WarcResponse rsp) {
        var url = new EdgeUrl(rsp.targetURI());

        crawlFrontier.addVisited(url);

        try {
            var response = HttpFetchResult.importWarc(rsp);
            if (DocumentBodyExtractor.extractBody(response) instanceof DocumentBodyResult.Ok ok) {
                var doc = Jsoup.parse(ok.body());
                crawlFrontier.enqueueLinksFromDocument(url, doc);
            }
        }
        catch (Exception e) {
            logger.info(STR."Failed to parse response body for \{url}", e);
        }
    }

    private void revisit(WarcRevisit revisit) throws IOException {
        if (!WarcRecorder.revisitURI.equals(revisit.profile())) {
            return;
        }

        var url = new EdgeUrl(revisit.targetURI());

        crawlFrontier.addVisited(url);

        try {
            var response = HttpFetchResult.importWarc(revisit);
            if (DocumentBodyExtractor.extractBody(response) instanceof DocumentBodyResult.Ok ok) {
                var doc = Jsoup.parse(ok.body());
                crawlFrontier.enqueueLinksFromDocument(url, doc);
            }
        }
        catch (Exception e) {
            logger.info(STR."Failed to parse response body for \{url}", e);
        }
    }

}
