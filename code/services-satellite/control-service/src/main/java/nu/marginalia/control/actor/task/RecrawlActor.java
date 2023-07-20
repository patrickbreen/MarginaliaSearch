package nu.marginalia.control.actor.task;

import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.With;
import nu.marginalia.control.svc.ProcessOutboxFactory;
import nu.marginalia.control.svc.ProcessService;
import nu.marginalia.db.storage.FileStorageService;
import nu.marginalia.db.storage.model.FileStorage;
import nu.marginalia.db.storage.model.FileStorageId;
import nu.marginalia.db.storage.model.FileStorageType;
import nu.marginalia.index.client.IndexClient;
import nu.marginalia.mq.MqMessage;
import nu.marginalia.mq.MqMessageState;
import nu.marginalia.mq.outbox.MqOutbox;
import nu.marginalia.mqapi.crawling.CrawlRequest;
import nu.marginalia.mqsm.StateFactory;
import nu.marginalia.mqsm.graph.AbstractStateGraph;
import nu.marginalia.mqsm.graph.GraphState;
import nu.marginalia.mqsm.graph.ResumeBehavior;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Singleton
public class RecrawlActor extends AbstractStateGraph {

    // STATES

    public static final String INITIAL = "INITIAL";
    public static final String CRAWL = "CRAWL";
    public static final String CRAWL_WAIT = "CRAWL-WAIT";
    public static final String END = "END";
    private final ProcessService processService;
    private final MqOutbox mqCrawlerOutbox;
    private final FileStorageService storageService;
    private final Gson gson;
    private final Logger logger = LoggerFactory.getLogger(getClass());


    @AllArgsConstructor @With @NoArgsConstructor
    public static class RecrawlMessage {
        public FileStorageId crawlSpecId = null;
        public FileStorageId crawlStorageId = null;
        public long crawlerMsgId = 0L;
    };

    public static RecrawlMessage recrawlFromCrawlData(FileStorageId crawlData) {
        return new RecrawlMessage(null, crawlData, 0L);
    }
    public static RecrawlMessage recrawlFromCrawlDataAndCralSpec(FileStorageId crawlData, FileStorageId crawlSpec) {
        return new RecrawlMessage(crawlSpec, crawlData, 0L);
    }

    @Inject
    public RecrawlActor(StateFactory stateFactory,
                        ProcessService processService,
                        ProcessOutboxFactory processOutboxFactory,
                        FileStorageService storageService,
                        Gson gson
                                   )
    {
        super(stateFactory);
        this.processService = processService;
        this.mqCrawlerOutbox = processOutboxFactory.createCrawlerOutbox();
        this.storageService = storageService;
        this.gson = gson;
    }

    @GraphState(name = INITIAL,
                next = CRAWL,
                description = """
                    Validate the input and transition to CRAWL
                    """)
    public RecrawlMessage init(RecrawlMessage recrawlMessage) throws Exception {
        if (null == recrawlMessage) {
            error("This Actor requires a message as an argument");
        }


        var crawlStorage = storageService.getStorage(recrawlMessage.crawlStorageId);
        FileStorage specStorage;

        if (recrawlMessage.crawlSpecId != null) {
            specStorage = storageService.getStorage(recrawlMessage.crawlSpecId);
        }
        else {
            specStorage = getSpec(crawlStorage).orElse(null);
        }

        if (specStorage == null) error("Bad storage id");
        if (specStorage.type() != FileStorageType.CRAWL_SPEC) error("Bad storage type " + specStorage.type());
        if (crawlStorage == null) error("Bad storage id");
        if (crawlStorage.type() != FileStorageType.CRAWL_DATA) error("Bad storage type " + specStorage.type());

        Files.deleteIfExists(crawlStorage.asPath().resolve("crawler.log"));

        return recrawlMessage
                .withCrawlSpecId(specStorage.id());
    }

    private Optional<FileStorage> getSpec(FileStorage crawlStorage) throws SQLException {
        return storageService.getSourceFromStorage(crawlStorage)
                .stream()
                .filter(storage -> storage.type().equals(FileStorageType.CRAWL_SPEC))
                .findFirst();
    }

    @GraphState(name = CRAWL,
                next = CRAWL_WAIT,
                resume = ResumeBehavior.ERROR,
                description = """
                        Send a crawl request to the crawler and transition to CRAWL_WAIT.
                        """
    )
    public RecrawlMessage crawl(RecrawlMessage recrawlMessage) throws Exception {
        // Create processed data area

        var toCrawl = storageService.getStorage(recrawlMessage.crawlSpecId);

        // Pre-send crawl request
        var request = new CrawlRequest(recrawlMessage.crawlSpecId, recrawlMessage.crawlStorageId);
        long id = mqCrawlerOutbox.sendAsync(CrawlRequest.class.getSimpleName(), gson.toJson(request));

        return recrawlMessage.withCrawlerMsgId(id);
    }

    @GraphState(
            name = CRAWL_WAIT,
            next = END,
            resume = ResumeBehavior.RETRY,
            description = """
                    Wait for the crawler to finish retreiving the data.
                    """
    )
    public RecrawlMessage crawlerWait(RecrawlMessage recrawlMessage) throws Exception {
        var rsp = waitResponse(mqCrawlerOutbox, ProcessService.ProcessId.CRAWLER, recrawlMessage.crawlerMsgId);

        if (rsp.state() != MqMessageState.OK)
            error("Crawler failed");

        return recrawlMessage;
    }


    public MqMessage waitResponse(MqOutbox outbox, ProcessService.ProcessId processId, long id) throws Exception {
        if (!waitForProcess(processId, TimeUnit.SECONDS, 30)) {
            error("Process " + processId + " did not launch");
        }
        for (;;) {
            try {
                return outbox.waitResponse(id, 1, TimeUnit.SECONDS);
            }
            catch (TimeoutException ex) {
                // Maybe the process died, wait a moment for it to restart
                if (!waitForProcess(processId, TimeUnit.SECONDS, 30)) {
                    error("Process " + processId + " died and did not re-launch");
                }
            }
        }
    }

    public boolean waitForProcess(ProcessService.ProcessId processId, TimeUnit unit, int duration) throws InterruptedException {

        // Wait for process to start
        long deadline = System.currentTimeMillis() + unit.toMillis(duration);
        while (System.currentTimeMillis() < deadline) {
            if (processService.isRunning(processId))
                return true;

            TimeUnit.SECONDS.sleep(1);
        }

        return false;
    }

}
