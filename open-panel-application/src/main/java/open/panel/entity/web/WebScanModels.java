package open.panel.entity.web;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public final class WebScanModels {
    private WebScanModels() {
    }

    @Data
    @Accessors(chain = true)
    public static class ScanRequest implements Serializable {
        private String target;
        private int startPort = 80;
        private int endPort = 65535;
    }

    @Data
    @Accessors(chain = true)
    public static class LinkMetadataRequest implements Serializable {
        private String url;
    }

    @Data
    @Accessors(chain = true)
    public static class Candidate implements Serializable {
        private String id;
        private String host;
        private int port;
        private String protocol;
        private String url;
        private String title;
        private String description;
        private String iconUrl;
        private int statusCode;
    }

    @Data
    @Accessors(chain = true)
    public static class ScanJob implements Serializable {
        private String id;
        private String target;
        private int startPort;
        private int endPort;
        private int total;
        private volatile int scanned;
        private volatile String status = "queued";
        private long startedAt;
        private volatile long completedAt;
        private volatile String error = "";
        private List<Candidate> results = new ArrayList<>();
    }
}
