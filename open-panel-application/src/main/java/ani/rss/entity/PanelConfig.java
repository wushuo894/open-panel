package ani.rss.entity;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class PanelConfig implements Serializable {
    private int schemaVersion = 9;
    private Site site = new Site();
    private Page page = new Page();
    private Security security = new Security();
    private Update update = new Update();
    private List<Group> groups = new ArrayList<>();
    private List<Card> cards = new ArrayList<>();
    private List<SearchEngine> searchEngines = new ArrayList<>();

    @Data
    @Accessors(chain = true)
    public static class Site implements Serializable {
        private String title = "Open Panel";
        private String icon = "/icons/icon.svg";
        private String background = "";
        private double backgroundOverlay = 0.20;
        private String theme = "system";
        private String themeColor = "#d8f257";
        private boolean cornerControlsHoverOnly;
    }

    @Data
    @Accessors(chain = true)
    public static class Page implements Serializable {
        private String mode = "cover";
        private String groupLayout = "sections";
        private boolean tabsShowAll = true;
        private Banner banner = new Banner();
        private Cover cover = new Cover();
        private Footer footer = new Footer();
    }

    @Data
    @Accessors(chain = true)
    public static class Banner implements Serializable {
        private boolean visible = true;
        private boolean showTime = true;
        private boolean showSeconds;
        private boolean showDate = true;
        private boolean showWeekday = true;
        private boolean showTitle = true;
        private String title = "Open Panel";
        private boolean showQuote = true;
        private String quote = "把常用服务放在触手可及的地方";
    }

    @Data
    @Accessors(chain = true)
    public static class Cover implements Serializable {
        private List<String> wallpapers = new ArrayList<>();
        private int intervalSeconds = 30;
        private List<String> groupIds = new ArrayList<>();
    }

    @Data
    @Accessors(chain = true)
    public static class Footer implements Serializable {
        private boolean visible = true;
        private List<String> lines = new ArrayList<>();
    }

    @Data
    @Accessors(chain = true)
    public static class Security implements Serializable {
        private boolean initialized;
        private String username = "admin";
        private String passwordHash = "";
        private String jwtSecret = "";
        private int tokenVersion = 1;
        private String activeSessionId = "";
        private boolean anonymousAccess = true;
        private boolean forbidMultipleLogin;
        private boolean forbidPublicAccess;
        private boolean invalidateOnIpChange;
        private boolean limitLoginAttempts = true;
        private int maxLoginAttempts = 5;
        private int loginLockMinutes = 15;
        private boolean allowCors;
        private List<String> corsOrigins = new ArrayList<>();
        private List<String> ipAllowlist = new ArrayList<>();
        private List<String> trustedProxyIps = new ArrayList<>();
        private int tokenValidHours = 24;
        private List<RevokedToken> revokedTokens = new ArrayList<>();
    }

    @Data
    @Accessors(chain = true)
    public static class RevokedToken implements Serializable {
        private String idHash;
        private long expiresAt;
    }

    @Data
    @Accessors(chain = true)
    public static class Update implements Serializable {
        private boolean autoCheck = true;
        private boolean notify = true;
        private String channel = "stable";
        private String githubToken = "";
        private long lastCheckedAt;
    }

    @Data
    @Accessors(chain = true)
    public static class Group implements Serializable {
        private String id;
        private String title;
        private String icon = "mdi-folder-outline";
        private String displayMode = "detail";
        private boolean enabled = true;
        private int sort;
    }

    @Data
    @Accessors(chain = true)
    public static class Card implements Serializable {
        private String id;
        private String groupId;
        private String type = "custom";
        private String title = "新卡片";
        private String remark = "";
        private String icon = "mdi-web";
        private String iconUrl = "";
        private boolean iconFrameless;
        private boolean enabled = true;
        private int sort;
        private String openTarget = "new";
        private CustomCard custom;
        private SystemCard system;
        private ServiceCard service;
        private DockerCard docker;
        private transient String resolvedUrl;
    }

    @Data
    @Accessors(chain = true)
    public static class CustomCard implements Serializable {
        private String internalUrl = "";
        private String externalUrl = "";
    }

    @Data
    @Accessors(chain = true)
    public static class SystemCard implements Serializable {
        private String metric = "overview";
        private String storagePath = ".";
    }

    @Data
    @Accessors(chain = true)
    public static class ServiceCard implements Serializable {
        private String serviceType = "generic";
        private String internalUrl = "";
        private String externalUrl = "";
        private String statusUrl = "";
        private String token = "";
    }

    @Data
    @Accessors(chain = true)
    public static class DockerCard implements Serializable {
        private String containerId = "";
        private String internalUrl = "";
        private String externalUrl = "";
    }

    @Data
    @Accessors(chain = true)
    public static class SearchEngine implements Serializable {
        private String id;
        private String name;
        private String icon = "mdi-magnify";
        private String urlTemplate;
        private boolean enabled = true;
        private int sort;
    }
}
