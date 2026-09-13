package ani.rss.repository;

import ani.rss.config.DefaultConfigFactory;
import ani.rss.entity.PanelConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

@Repository
public class JsonConfigRepository {
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Path configFile;
    private PanelConfig config;

    public JsonConfigRepository(@Value("${open-panel.config-dir}") String configDir) {
        this.configFile = Path.of(configDir).toAbsolutePath().normalize().resolve("open-panel.json");
    }

    @PostConstruct
    void initialize() throws IOException {
        Files.createDirectories(configFile.getParent());
        if (Files.exists(configFile)) {
            config = gson.fromJson(Files.readString(configFile, StandardCharsets.UTF_8), PanelConfig.class);
        }
        if (config == null) {
            config = DefaultConfigFactory.create();
            persist(config);
        }
        normalize(config);
        persist(config);
    }

    public PanelConfig get() {
        lock.readLock().lock();
        try {
            return gson.fromJson(gson.toJson(config), PanelConfig.class);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void replace(PanelConfig next) {
        lock.writeLock().lock();
        try {
            normalize(next);
            persist(next);
            config = next;
        } catch (IOException exception) {
            throw new IllegalStateException("保存配置失败", exception);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public PanelConfig update(Consumer<PanelConfig> updater) {
        lock.writeLock().lock();
        try {
            PanelConfig next = gson.fromJson(gson.toJson(config), PanelConfig.class);
            updater.accept(next);
            normalize(next);
            persist(next);
            config = next;
            return gson.fromJson(gson.toJson(config), PanelConfig.class);
        } catch (IOException exception) {
            throw new IllegalStateException("保存配置失败", exception);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public String exportJson() {
        return gson.toJson(get());
    }

    public PanelConfig parse(String json) {
        PanelConfig parsed = gson.fromJson(json, PanelConfig.class);
        if (parsed == null) {
            throw new IllegalArgumentException("配置文件为空");
        }
        return parsed;
    }

    private void persist(PanelConfig value) throws IOException {
        Path temporary = configFile.resolveSibling(configFile.getFileName() + ".tmp");
        Files.writeString(temporary, gson.toJson(value), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            Files.move(temporary, configFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void normalize(PanelConfig value) {
        PanelConfig defaults = DefaultConfigFactory.create();
        if (value.getSite() == null) value.setSite(defaults.getSite());
        if (value.getPage() == null) value.setPage(defaults.getPage());
        if (value.getPage().getCover() == null) value.getPage().setCover(defaults.getPage().getCover());
        if (value.getPage().getCover().getGroupIds() == null) {
            value.getPage().getCover().setGroupIds(new java.util.ArrayList<>());
        } else if (value.getPage().getCover().getGroupIds().size() > 1) {
            value.getPage().getCover().setGroupIds(new java.util.ArrayList<>(java.util.List.of(
                    value.getPage().getCover().getGroupIds().getFirst())));
        }
        if (value.getSecurity() == null) value.setSecurity(defaults.getSecurity());
        if (value.getUpdate() == null) value.setUpdate(defaults.getUpdate());
        if (value.getGroups() == null) value.setGroups(new java.util.ArrayList<>());
        if (value.getCards() == null) value.setCards(new java.util.ArrayList<>());
        if (value.getSearchEngines() == null) value.setSearchEngines(new java.util.ArrayList<>());
        if (value.getSecurity().getJwtSecret() == null || value.getSecurity().getJwtSecret().isBlank()) {
            value.getSecurity().setJwtSecret(defaults.getSecurity().getJwtSecret());
        }
        if (value.getUpdate().getGithubToken() == null) value.getUpdate().setGithubToken("");
        if (value.getSchemaVersion() < 2) {
            migrateDefaultWallpaper(value);
        }
        if (value.getSchemaVersion() < 3) {
            boolean hasSystemCard = value.getCards().stream().anyMatch(card -> "system".equals(card.getType()));
            if (!hasSystemCard) DefaultConfigFactory.addDefaultSystemGroup(value);
            value.setSchemaVersion(3);
        }
        if (value.getSchemaVersion() < 4) {
            DefaultConfigFactory.addDefaultDockerGroupIfAvailable(value);
            value.getGroups().sort(Comparator
                    .comparingInt((PanelConfig.Group group) -> defaultGroupPriority(group.getTitle()))
                    .thenComparingInt(PanelConfig.Group::getSort));
            for (int index = 0; index < value.getGroups().size(); index++) {
                value.getGroups().get(index).setSort(index);
            }
            value.setSchemaVersion(4);
        }
        if (value.getSchemaVersion() < 5) {
            value.getPage().getBanner().setShowDate(true).setShowWeekday(true);
            value.setSchemaVersion(5);
        }
        normalizeServiceTypes(value);
        if (value.getSchemaVersion() < 6) value.setSchemaVersion(6);
        if (value.getSchemaVersion() < 7) {
            if (Math.abs(value.getSite().getBackgroundOverlay() - 0.42d) < 0.0001d) {
                value.getSite().setBackgroundOverlay(0.20d);
            }
            value.setSchemaVersion(7);
        }
    }

    private void normalizeServiceTypes(PanelConfig value) {
        java.util.Set<String> supported = java.util.Set.of("generic", "emby", "ani-rss", "qbit", "openlist");
        value.getCards().stream()
                .filter(card -> "service".equals(card.getType()) && card.getService() != null)
                .forEach(card -> {
                    String type = card.getService().getServiceType();
                    if ("qbittorrent".equalsIgnoreCase(type)) {
                        card.getService().setServiceType("qbit");
                    } else if (type == null || !supported.contains(type.toLowerCase(java.util.Locale.ROOT))) {
                        card.getService().setServiceType("generic").setToken("");
                    }
                });
    }

    private int defaultGroupPriority(String title) {
        if ("系统信息".equals(title)) return 0;
        if ("Docker".equalsIgnoreCase(title)) return 1;
        if ("常用网站".equals(title)) return 2;
        return 3;
    }

    private void migrateDefaultWallpaper(PanelConfig value) {
        String remoteDefault = "https://api.imlazy.ink/img";
        String oldDefault = "https://images.unsplash.com/photo-1519681393784-d120267933ba3ee?auto=format&fit=crop&w=2400&q=85";
        if (value.getPage().getCover().getWallpapers().size() == 1) {
            String wallpaper = value.getPage().getCover().getWallpapers().getFirst();
            if (remoteDefault.equals(wallpaper) || oldDefault.equals(wallpaper)) {
                value.getPage().getCover().setWallpapers(new java.util.ArrayList<>(
                        defaultsWallpaper()));
            }
        }
        if (remoteDefault.equals(value.getSite().getBackground()) || oldDefault.equals(value.getSite().getBackground())) {
            value.getSite().setBackground("assets/default-wallpaper.webp");
        }
    }

    private java.util.List<String> defaultsWallpaper() {
        return java.util.List.of("assets/default-wallpaper.webp");
    }
}
