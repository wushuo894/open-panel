package ani.rss.update;

import ani.rss.repository.JsonConfigRepository;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class UpdateService {
    private static final String RELEASE_API = "https://api.github.com/repos/wushuo894/open-panel/releases/latest";
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final String currentVersion;
    private final boolean container;
    private final JsonConfigRepository repository;

    public UpdateService(@Value("${info.build.version:1.0.2}") String currentVersion,
                         @Value("${open-panel.container:false}") boolean container,
                         JsonConfigRepository repository) {
        this.currentVersion = currentVersion;
        this.container = container;
        this.repository = repository;
    }

    public Map<String, Object> check() {
        JsonObject release = fetchRelease();
        String latest = release.get("tag_name").getAsString().replaceFirst("^[vV]", "");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("currentVersion", currentVersion);
        result.put("latestVersion", latest);
        result.put("available", compareVersions(latest, currentVersion) > 0);
        result.put("container", container);
        result.put("releaseUrl", release.get("html_url").getAsString());
        result.put("publishedAt", release.get("published_at").getAsString());
        result.put("notes", release.has("body") && !release.get("body").isJsonNull() ? release.get("body").getAsString() : "");
        return result;
    }

    public Map<String, Object> current() {
        return Map.of("currentVersion", currentVersion, "container", container);
    }

    public Map<String, Object> install() {
        if (container) throw new IllegalStateException("容器部署请拉取新镜像后重新创建容器");
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            throw new IllegalStateException("项目自更新不支持 Windows");
        }
        Path runningJar = new ApplicationHome(UpdateService.class).getSource().toPath().toAbsolutePath();
        if (!Files.isRegularFile(runningJar) || !runningJar.toString().endsWith(".jar")) {
            throw new IllegalStateException("开发环境不能执行自动更新");
        }
        JsonObject release = fetchRelease();
        JsonObject asset = release.getAsJsonArray("assets").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(item -> item.get("name").getAsString().endsWith(".jar"))
                .findFirst().orElseThrow(() -> new IllegalStateException("发行版没有 JAR 文件"));
        String digest = asset.has("digest") && !asset.get("digest").isJsonNull() ? asset.get("digest").getAsString() : "";
        if (!digest.startsWith("sha256:")) throw new IllegalStateException("发行文件缺少 SHA-256 摘要，已拒绝更新");
        Path downloaded = runningJar.resolveSibling(runningJar.getFileName() + ".new");
        download(asset.get("browser_download_url").getAsString(), downloaded);
        verify(downloaded, digest.substring(7));
        restartAfterResponse(downloaded, runningJar);
        return Map.of("restarting", true, "version", release.get("tag_name").getAsString());
    }

    private JsonObject fetchRelease() {
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(RELEASE_API)).timeout(Duration.ofSeconds(12))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "Open-Panel/" + currentVersion);
            String githubToken = repository.get().getUpdate().getGithubToken();
            if (githubToken != null && !githubToken.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + githubToken);
            }
            HttpRequest request = requestBuilder.GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("GitHub 返回 HTTP " + response.statusCode());
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("检查更新已中断", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("检查更新失败: " + exception.getMessage(), exception);
        }
    }

    private void download(String url, Path target) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5))
                    .header("User-Agent", "Open-Panel/" + currentVersion).GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) throw new IllegalStateException("下载更新失败: HTTP " + response.statusCode());
            try (InputStream input = response.body()) {
                Files.copy(input, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("下载更新已中断", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("下载更新失败: " + exception.getMessage(), exception);
        }
    }

    private void verify(Path file, String expected) {
        try (InputStream input = Files.newInputStream(file)) {
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
            if (!actual.equalsIgnoreCase(expected)) {
                Files.deleteIfExists(file);
                throw new IllegalStateException("更新文件校验失败");
            }
        } catch (Exception exception) {
            throw exception instanceof IllegalStateException state ? state : new IllegalStateException("更新文件校验失败", exception);
        }
    }

    private void restartAfterResponse(Path downloaded, Path runningJar) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Executors.newSingleThreadScheduledExecutor().schedule(() -> {
            try {
                new ProcessBuilder("sh", "-c",
                        "sleep 2; mv \"$1\" \"$2\"; exec \"$3\" -jar \"$2\"",
                        "open-panel-update", downloaded.toString(), runningJar.toString(), java)
                        .inheritIO().start();
                System.exit(0);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }, 1, TimeUnit.SECONDS);
    }

    private int compareVersions(String left, String right) {
        String[] a = left.replaceAll("[^0-9.]", "").split("\\.");
        String[] b = right.replaceAll("[^0-9.]", "").split("\\.");
        for (int index = 0; index < Math.max(a.length, b.length); index++) {
            int av = index < a.length && !a[index].isBlank() ? Integer.parseInt(a[index]) : 0;
            int bv = index < b.length && !b[index].isBlank() ? Integer.parseInt(b[index]) : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }
}
