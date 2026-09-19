package open.panel.config;

import open.panel.entity.PanelConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

public final class DefaultConfigFactory {
    private DefaultConfigFactory() {
    }

    public static PanelConfig create() {
        PanelConfig config = new PanelConfig();
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        config.getSecurity().setJwtSecret(Base64.getUrlEncoder().withoutPadding().encodeToString(secret));

        config.getSite().setBackground("assets/default-wallpaper.webp");
        config.getPage().getCover().setWallpapers(new ArrayList<>(List.of(config.getSite().getBackground())));
        config.getPage().getFooter().setLines(new ArrayList<>(List.of("Open Panel · Your services, one place")));

        addDefaultSystemGroup(config);
        addDefaultDockerGroupIfAvailable(config);

        String groupId = UUID.randomUUID().toString();
        PanelConfig.Group group = new PanelConfig.Group();
        group.setId(groupId);
        group.setTitle("常用网站");
        group.setIcon("mdi-view-grid-outline");
        group.setSort(config.getGroups().size());
        config.getGroups().add(group);

        addLink(config, groupId, "知乎", "发现问题背后的答案", "mdi-alpha-z-box", "https://www.zhihu.com/", 0);
        addLink(config, groupId, "百度贴吧", "兴趣社区", "mdi-forum-outline", "https://tieba.baidu.com/", 1);
        addLink(config, groupId, "QQ邮箱", "收发邮件", "mdi-email-outline", "https://mail.qq.com/", 2);
        addLink(config, groupId, "GitHub", "代码与项目", "mdi-github", "https://github.com/", 3);
        addLink(config, groupId, "哔哩哔哩", "视频与直播", "mdi-television-play", "https://www.bilibili.com/", 4);
        addLink(config, groupId, "YouTube", "视频平台", "mdi-youtube", "https://www.youtube.com/", 5);
        addLink(config, groupId, "ChatGPT", "AI 助手", "mdi-robot-outline", "https://chatgpt.com/", 6);
        addLink(config, groupId, "Cloudflare", "网络与安全", "mdi-cloud-outline", "https://dash.cloudflare.com/", 7);

        addSearch(config, "Google", "mdi-google", "https://www.google.com/search?q={query}", 0);
        addSearch(config, "Bing", "mdi-microsoft-bing", "https://www.bing.com/search?q={query}", 1);
        addSearch(config, "GitHub", "mdi-github", "https://github.com/search?q={query}", 2);
        return config;
    }

    public static void addDefaultSystemGroup(PanelConfig config) {
        String groupId = UUID.randomUUID().toString();
        PanelConfig.Group group = new PanelConfig.Group()
                .setId(groupId)
                .setTitle("系统信息")
                .setIcon("mdi-server-outline")
                .setSort(config.getGroups().size());
        config.getGroups().add(group);

        addSystem(config, groupId, "CPU", "处理器使用率", "mdi-cpu-64-bit", "cpu", 0);
        addSystem(config, groupId, "RAM", "已用与总内存", "mdi-memory", "memory", 1);
        addSystem(config, groupId, "Network", "累计接收与发送", "mdi-lan", "network", 2);
    }

    public static boolean addDefaultDockerGroupIfAvailable(PanelConfig config) {
        if (!dockerAvailable() || config.getGroups().stream().anyMatch(group -> "Docker".equalsIgnoreCase(group.getTitle()))) {
            return false;
        }
        config.getGroups().add(new PanelConfig.Group()
                .setId(UUID.randomUUID().toString())
                .setTitle("Docker")
                .setIcon("mdi-docker")
                .setSort(config.getGroups().size()));
        return true;
    }

    public static boolean dockerAvailable() {
        String dockerHost = System.getenv("DOCKER_HOST");
        if (dockerHost != null && !dockerHost.isBlank()) return true;
        String userHome = System.getProperty("user.home", "");
        return Files.exists(Path.of("/var/run/docker.sock"))
                || Files.exists(Path.of("/run/docker.sock"))
                || (!userHome.isBlank() && Files.exists(Path.of(userHome, ".docker", "run", "docker.sock")));
    }

    private static void addLink(PanelConfig config, String groupId, String title, String remark,
                                String icon, String url, int sort) {
        PanelConfig.Card card = new PanelConfig.Card();
        card.setId(UUID.randomUUID().toString());
        card.setGroupId(groupId);
        card.setTitle(title);
        card.setRemark(remark);
        card.setIcon(icon);
        card.setSort(sort);
        PanelConfig.CustomCard custom = new PanelConfig.CustomCard();
        custom.setExternalUrl(url);
        custom.setInternalUrl(url);
        card.setCustom(custom);
        config.getCards().add(card);
    }

    private static void addSystem(PanelConfig config, String groupId, String title, String remark,
                                  String icon, String metric, int sort) {
        PanelConfig.Card card = new PanelConfig.Card()
                .setId(UUID.randomUUID().toString())
                .setGroupId(groupId)
                .setType("system")
                .setTitle(title)
                .setRemark(remark)
                .setIcon(icon)
                .setSort(sort)
                .setOpenTarget("self")
                .setSystem(new PanelConfig.SystemCard().setMetric(metric));
        config.getCards().add(card);
    }

    private static void addSearch(PanelConfig config, String name, String icon, String template, int sort) {
        PanelConfig.SearchEngine engine = new PanelConfig.SearchEngine();
        engine.setId(UUID.randomUUID().toString());
        engine.setName(name);
        engine.setIcon(icon);
        engine.setUrlTemplate(template);
        engine.setSort(sort);
        config.getSearchEngines().add(engine);
    }
}
