package ani.rss.entity.web;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public final class DockerUpdateModels {
    private DockerUpdateModels() {
    }

    @Data
    @Accessors(chain = true)
    public static class DockerOverview implements Serializable {
        private boolean available;
        private String error = "";
        private List<ContainerInfo> containers = new ArrayList<>();
        private DockerJob activeJob;
    }

    @Data
    @Accessors(chain = true)
    public static class ContainerInfo implements Serializable {
        private String id;
        private String name;
        private String image;
        private String imageId;
        private String latestImageId;
        private String state;
        private String status;
        private long uptimeSeconds;
        private boolean updateChecked;
        private boolean updateAvailable;
        private boolean updatable;
        private boolean self;
        private String reason = "";
        private String checkError = "";
    }

    @Data
    @Accessors(chain = true)
    public static class UpdateRequest implements Serializable {
        private String containerId;
    }

    @Data
    @Accessors(chain = true)
    public static class ComposeView implements Serializable {
        private String containerId;
        private String containerName;
        private String filename = "docker-compose.yaml";
        private String content;
    }

    @Data
    @Accessors(chain = true)
    public static class ImageCleanupResult implements Serializable {
        private int deletedImages;
        private int removedTags;
        private int skippedImages;
        private long spaceReclaimed;
    }

    @Data
    @Accessors(chain = true)
    public static class ImageCleanupRequest implements Serializable {
        private List<String> imageIds = new ArrayList<>();
    }

    @Data
    @Accessors(chain = true)
    public static class ImageCleanupPreview implements Serializable {
        private List<UnusedImage> images = new ArrayList<>();
        private long totalSize;
    }

    @Data
    @Accessors(chain = true)
    public static class UnusedImage implements Serializable {
        private String id;
        private String cleanupTarget;
        private boolean tagOnly;
        private List<String> references = new ArrayList<>();
        private long size;
    }

    @Data
    @Accessors(chain = true)
    public static class DockerJob implements Serializable {
        private String id;
        private String type;
        private String status = "queued";
        private String phase = "queued";
        private String containerId = "";
        private String containerName = "";
        private String image = "";
        private int progress;
        private int completedItems;
        private int totalItems;
        private boolean cancellable = true;
        private long startedAt;
        private long completedAt;
        private String error = "";
        private List<JobLog> logs = new ArrayList<>();
    }

    @Data
    @Accessors(chain = true)
    public static class JobLog implements Serializable {
        private long timestamp;
        private String level = "info";
        private String message;
    }
}
