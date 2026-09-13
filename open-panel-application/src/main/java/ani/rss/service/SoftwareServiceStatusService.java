package ani.rss.service;

import ani.rss.entity.PanelConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class SoftwareServiceStatusService {
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public Map<String, Object> status(PanelConfig.ServiceCard service) {
        String type = value(service.getServiceType()).toLowerCase(Locale.ROOT);
        if (type.isBlank()) type = "generic";
        try {
            return switch (type) {
                case "emby" -> emby(service);
                case "ani-rss" -> aniRss(service);
                case "qbit" -> qBittorrent(service);
                case "openlist" -> openList(service);
                default -> generic(service, type);
            };
        } catch (MissingApiKeyException exception) {
            return fallback(service, type, "缺少 API Key");
        } catch (Exception exception) {
            return fallback(service, type, "连接失败");
        }
    }

    private Map<String, Object> emby(PanelConfig.ServiceCard service) throws Exception {
        String base = requireBase(service);
        Map<String, String> headers = apiKeyHeader(service, "X-Emby-Token", "emby");
        Response info = get(endpoint(base, "/System/Info/Public"), headers);
        if (info.statusCode() == 404 && !base.toLowerCase(Locale.ROOT).endsWith("/emby")) {
            info = get(endpoint(base, "/emby/System/Info/Public"), headers);
        }
        if (!success(info)) return specialFailure(service, "emby", info);

        JsonObject data = jsonObject(info.body());
        String serverName = string(data, "ServerName");
        String version = string(data, "Version");
        int activeSessions = -1;
        int transcoding = -1;
        Response sessionsResponse = get(endpoint(base, "/Sessions"), headers);
        if (sessionsResponse.statusCode() == 404 && !base.toLowerCase(Locale.ROOT).endsWith("/emby")) {
            sessionsResponse = get(endpoint(base, "/emby/Sessions"), headers);
        }
        if (success(sessionsResponse)) {
            JsonArray sessions = jsonArray(sessionsResponse.body());
            activeSessions = 0;
            transcoding = 0;
            for (JsonElement item : sessions) {
                JsonObject session = item.getAsJsonObject();
                if (session.has("NowPlayingItem")) activeSessions++;
                if (session.has("TranscodingInfo")) transcoding++;
            }
        }

        Map<String, Object> result = online("emby", info, "服务正常");
        put(result, "serverName", serverName);
        put(result, "version", version);
        if (activeSessions >= 0) result.put("activeSessions", activeSessions);
        if (transcoding >= 0) result.put("transcoding", transcoding);
        result.put("summary", summary(
                serverName,
                version.isBlank() ? "" : "v" + version,
                activeSessions < 0 ? "" : "播放 " + activeSessions,
                transcoding > 0 ? "转码 " + transcoding : ""
        ));
        return result;
    }

    private Map<String, Object> aniRss(PanelConfig.ServiceCard service) throws Exception {
        String base = requireBase(service);
        Map<String, String> headers = apiKeyHeader(service, "api-key", "ani-rss");
        Response about = post(endpoint(base, "/api/about"), headers);
        if (!success(about)) return specialFailure(service, "ani-rss", about);
        JsonObject envelope = jsonObject(about.body());
        if (integer(envelope, "code", 500) >= 300) return authFailure("ani-rss", about.elapsedMs());

        String version = string(object(envelope, "data"), "version");
        int subscriptions = -1;
        int torrents = -1;
        int activeTorrents = -1;
        Response subscriptionsResponse = post(endpoint(base, "/api/listAni"), headers);
        if (success(subscriptionsResponse)) {
            subscriptions = integer(object(jsonObject(subscriptionsResponse.body()), "data"), "total", -1);
        }
        Response torrentsResponse = post(endpoint(base, "/api/torrentsInfos"), headers);
        if (success(torrentsResponse)) {
            JsonArray torrentItems = array(jsonObject(torrentsResponse.body()), "data");
            torrents = torrentItems.size();
            activeTorrents = 0;
            for (JsonElement item : torrentItems) {
                String state = string(item.getAsJsonObject(), "state");
                if (!"stoppedUP".equalsIgnoreCase(state) && !"pausedUP".equalsIgnoreCase(state)) activeTorrents++;
            }
        }

        Map<String, Object> result = online("ani-rss", about, "服务正常");
        put(result, "version", version);
        if (subscriptions >= 0) result.put("subscriptions", subscriptions);
        if (torrents >= 0) result.put("torrents", torrents);
        if (activeTorrents >= 0) result.put("activeTorrents", activeTorrents);
        result.put("summary", summary(
                version.isBlank() ? "" : "v" + version,
                subscriptions < 0 ? "" : "订阅 " + subscriptions,
                activeTorrents < 0 ? "" : "活动任务 " + activeTorrents
        ));
        return result;
    }

    private Map<String, Object> qBittorrent(PanelConfig.ServiceCard service) throws Exception {
        String base = requireBase(service);
        Map<String, String> headers = apiKeyHeader(service, "Authorization", "qbit");
        headers.put("Authorization", "Bearer " + service.getToken().trim());
        Response versionResponse = get(endpoint(base, "/api/v2/app/version"), headers);
        if (!success(versionResponse)) return specialFailure(service, "qbit", versionResponse);

        String version = versionResponse.body().trim();
        long downloadSpeed = -1;
        long uploadSpeed = -1;
        int torrents = -1;
        int downloading = -1;
        int seeding = -1;
        Response transferResponse = get(endpoint(base, "/api/v2/transfer/info"), headers);
        if (success(transferResponse)) {
            JsonObject transfer = jsonObject(transferResponse.body());
            downloadSpeed = number(transfer, "dl_info_speed", -1).longValue();
            uploadSpeed = number(transfer, "up_info_speed", -1).longValue();
        }
        Response torrentsResponse = get(endpoint(base, "/api/v2/torrents/info"), headers);
        if (success(torrentsResponse)) {
            JsonArray torrentItems = jsonArray(torrentsResponse.body());
            torrents = torrentItems.size();
            downloading = 0;
            seeding = 0;
            for (JsonElement item : torrentItems) {
                String state = string(item.getAsJsonObject(), "state").toLowerCase(Locale.ROOT);
                if (state.contains("dl") || state.contains("download")) downloading++;
                if (state.contains("up") || state.contains("seed")) seeding++;
            }
        }

        Map<String, Object> result = online("qbit", versionResponse, "服务正常");
        put(result, "version", version);
        if (downloadSpeed >= 0) result.put("downloadSpeed", downloadSpeed);
        if (uploadSpeed >= 0) result.put("uploadSpeed", uploadSpeed);
        if (torrents >= 0) result.put("torrents", torrents);
        if (downloading >= 0) result.put("downloading", downloading);
        if (seeding >= 0) result.put("seeding", seeding);
        String versionLabel = version.isBlank()
                ? ""
                : (version.startsWith("v") || version.startsWith("V") ? version : "v" + version);
        result.put("summary", summary(
                versionLabel,
                downloading < 0 ? "" : "下载" + downloading,
                seeding < 0 ? "" : "做种" + seeding
        ));
        return result;
    }

    private Map<String, Object> openList(PanelConfig.ServiceCard service) throws Exception {
        String base = requireBase(service);
        Map<String, String> headers = apiKeyHeader(service, "Authorization", "openlist");
        Response storageResponse = get(endpoint(base, "/api/admin/storage/list"), headers);
        if (!success(storageResponse)) return specialFailure(service, "openlist", storageResponse);

        JsonObject envelope = jsonObject(storageResponse.body());
        int apiCode = integer(envelope, "code", 500);
        if (apiCode == 401 || apiCode == 403) return authFailure("openlist", storageResponse.elapsedMs());
        if (apiCode < 200 || apiCode >= 300) return fallback(service, "openlist", "OpenList API 不可用");

        JsonObject data = object(envelope, "data");
        JsonArray storages = array(data, "content");
        int storageCount = integer(data, "total", storages.size());
        long totalSpace = 0;
        int storageDetailsCount = 0;
        for (JsonElement element : storages) {
            if (!element.isJsonObject()) continue;
            JsonObject details = object(element.getAsJsonObject(), "mount_details");
            if (details.isEmpty()) continue;
            long capacity = Math.max(0, number(details, "total_space", 0).longValue());
            totalSpace = capacity > Long.MAX_VALUE - totalSpace ? Long.MAX_VALUE : totalSpace + capacity;
            storageDetailsCount++;
        }

        Map<String, Object> result = online("openlist", storageResponse, "服务正常");
        result.put("storageCount", storageCount);
        result.put("totalSpace", totalSpace);
        result.put("storageDetailsCount", storageDetailsCount);
        result.put("summary", summary(
                storageDetailsCount > 0 ? formatBytes(totalSpace) : "容量未知",
                "存储 " + storageCount
        ));
        return result;
    }

    private Map<String, String> apiKeyHeader(PanelConfig.ServiceCard service, String header, String type) {
        if (blank(service.getToken())) throw new MissingApiKeyException(type);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(header, service.getToken().trim());
        return headers;
    }

    private Map<String, Object> generic(PanelConfig.ServiceCard service, String type) throws Exception {
        String url = blank(service.getStatusUrl()) ? requireBase(service) : service.getStatusUrl().trim();
        Response response = get(url, Map.of());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("online", response.statusCode() >= 200 && response.statusCode() < 500);
        result.put("status", "HTTP " + response.statusCode());
        result.put("summary", response.statusCode() >= 200 && response.statusCode() < 500 ? "服务在线" : "服务异常");
        result.put("serviceType", type);
        result.put("latencyMs", response.elapsedMs());
        return result;
    }

    private Map<String, Object> fallback(PanelConfig.ServiceCard service, String type, String message) {
        if (message.equals("连接失败") && !blank(service.getToken())) return offline(type, message);
        try {
            Map<String, Object> result = generic(service, type);
            if (Boolean.TRUE.equals(result.get("online"))) {
                result.put("status", message);
                result.put("summary", message.equals("缺少 API Key") ? message : "服务在线");
            }
            return result;
        } catch (Exception ignored) {
            return offline(type, message);
        }
    }

    private Map<String, Object> specialFailure(PanelConfig.ServiceCard service, String type, Response response) {
        if (response.statusCode() == 401 || response.statusCode() == 403) return authFailure(type, response.elapsedMs());
        return fallback(service, type, "专用接口不可用");
    }

    private Map<String, Object> authFailure(String type, long elapsedMs) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("online", true);
        result.put("status", "API Key 鉴权失败");
        result.put("summary", "请检查 API Key");
        result.put("serviceType", type);
        result.put("latencyMs", elapsedMs);
        return result;
    }

    private Map<String, Object> offline(String type, String status) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("online", false);
        result.put("status", status);
        result.put("summary", status);
        result.put("serviceType", type);
        return result;
    }

    private Map<String, Object> online(String type, Response response, String status) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("online", true);
        result.put("status", status);
        result.put("serviceType", type);
        result.put("latencyMs", response.elapsedMs());
        return result;
    }

    private Response get(String url, Map<String, String> headers) throws Exception {
        return request("GET", url, headers);
    }

    private Response post(String url, Map<String, String> headers) throws Exception {
        return request("POST", url, headers);
    }

    private Response request(String method, String url, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(4))
                .header("User-Agent", "Open-Panel/1.0")
                .header("Accept", "application/json, text/plain, */*");
        headers.forEach(builder::header);
        if ("POST".equals(method)) builder.POST(HttpRequest.BodyPublishers.noBody());
        else builder.GET();
        long started = System.nanoTime();
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        return new Response(response.statusCode(), response.body(), response.headers(), elapsedMs);
    }

    private String requireBase(PanelConfig.ServiceCard service) {
        String base = blank(service.getInternalUrl()) ? value(service.getExternalUrl()) : service.getInternalUrl();
        if (base.isBlank()) throw new IllegalArgumentException("服务 URL 不能为空");
        return base.trim().replaceAll("/+$", "");
    }

    private String endpoint(String base, String path) {
        return base.replaceAll("/+$", "") + (path.startsWith("/") ? path : "/" + path);
    }

    private boolean success(Response response) {
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }

    private JsonObject jsonObject(String body) {
        JsonElement element = JsonParser.parseString(body);
        return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private JsonArray jsonArray(String body) {
        JsonElement element = JsonParser.parseString(body);
        return element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private JsonObject object(JsonObject parent, String key) {
        JsonElement element = parent == null ? null : parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private JsonArray array(JsonObject parent, String key) {
        JsonElement element = parent == null ? null : parent.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private String string(JsonObject object, String key) {
        JsonElement element = object == null ? null : object.get(key);
        return element == null || element.isJsonNull() || !element.isJsonPrimitive() ? "" : element.getAsString();
    }

    private Number number(JsonObject object, String key, Number fallback) {
        try {
            JsonElement element = object == null ? null : object.get(key);
            return element == null || element.isJsonNull() ? fallback : element.getAsNumber();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int integer(JsonObject object, String key, int fallback) {
        return number(object, key, fallback).intValue();
    }

    private String summary(String... parts) {
        List<String> values = new ArrayList<>();
        for (String part : parts) if (!blank(part)) values.add(part.trim());
        return values.isEmpty() ? "服务在线" : String.join(" · ", values);
    }

    private String formatBytes(long bytes) {
        String[] units = {"B", "KB", "MB", "GB", "TB", "PB"};
        double size = Math.max(0, bytes);
        int unit = 0;
        while (size >= 1024 && unit < units.length - 1) {
            size /= 1024;
            unit++;
        }
        return unit == 0
                ? Math.round(size) + " " + units[unit]
                : String.format(Locale.ROOT, "%.1f %s", size, units[unit]);
    }

    private void put(Map<String, Object> target, String key, String value) {
        if (!blank(value)) target.put(key, value.trim());
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private record Response(int statusCode, String body, HttpHeaders headers, long elapsedMs) {
    }

    private static class MissingApiKeyException extends IllegalArgumentException {
        private MissingApiKeyException(String type) {
            super(type);
        }
    }
}
