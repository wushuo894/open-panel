package ani.rss.service;

import ani.rss.entity.web.WebScanModels;
import jakarta.annotation.PreDestroy;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class WebScanService {
    private static final int MAX_CONCURRENCY = 256;
    private static final int CONNECT_TIMEOUT_MS = 300;
    private static final int WEB_TIMEOUT_MS = 1800;
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private final Map<String, WebScanModels.ScanJob> jobs = new ConcurrentHashMap<>();
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
    private final ExecutorService coordinators = Executors.newVirtualThreadPerTaskExecutor();
    private final SSLContext insecureSslContext = createScannerSslContext();
    private final HttpClient metadataHttpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .sslContext(insecureSslContext)
            .connectTimeout(java.time.Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public WebScanModels.ScanJob start(WebScanModels.ScanRequest request) {
        if (jobs.values().stream().anyMatch(job -> "queued".equals(job.getStatus()) || "running".equals(job.getStatus()))) {
            throw new IllegalStateException("已有端口扫描任务正在运行");
        }
        String target = normalizeTarget(request.getTarget());
        int start = request.getStartPort();
        int end = request.getEndPort();
        if (start < 80 || end > 65535 || start > end) {
            throw new IllegalArgumentException("端口范围必须在 80 到 65535 之间");
        }
        WebScanModels.ScanJob job = new WebScanModels.ScanJob()
                .setId(UUID.randomUUID().toString())
                .setTarget(target)
                .setStartPort(start)
                .setEndPort(end)
                .setTotal(end - start + 1)
                .setResults(new CopyOnWriteArrayList<>())
                .setStartedAt(System.currentTimeMillis());
        jobs.put(job.getId(), job);
        coordinators.submit(() -> scan(job));
        trimJobs();
        return snapshot(job);
    }

    public WebScanModels.ScanJob get(String id) {
        WebScanModels.ScanJob job = jobs.get(id);
        if (job == null) throw new IllegalArgumentException("扫描任务不存在");
        return snapshot(job);
    }

    public void cancel(String id) {
        WebScanModels.ScanJob job = jobs.get(id);
        if (job == null) throw new IllegalArgumentException("扫描任务不存在");
        cancelled.add(id);
        job.setStatus("cancelling");
    }

    public WebScanModels.Candidate lookup(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("请先填写内网或公网 URL");
        String input = value.strip();
        if (input.contains("://")) return fetchMetadata(normalizeLinkUri(input));
        try {
            return fetchMetadata(normalizeLinkUri("https://" + input));
        } catch (IllegalArgumentException exception) {
            return fetchMetadata(normalizeLinkUri("http://" + input));
        }
    }

    private void scan(WebScanModels.ScanJob job) {
        job.setStatus("running");
        int concurrency = Math.min(MAX_CONCURRENCY, job.getTotal());
        ExecutorService workers = Executors.newFixedThreadPool(concurrency, Thread.ofVirtual().name("web-scan-", 0).factory());
        try {
            InetAddress address = InetAddress.getByName(job.getTarget());
            CompletionService<WebScanModels.Candidate> completion = new ExecutorCompletionService<>(workers);
            int nextPort = job.getStartPort();
            int submitted = 0;
            for (; submitted < concurrency; submitted++) {
                int port = nextPort++;
                completion.submit(() -> inspectPort(address, job.getTarget(), port));
            }
            int completed = 0;
            while (completed < submitted) {
                if (cancelled.contains(job.getId())) break;
                WebScanModels.Candidate candidate = completion.take().get();
                completed++;
                job.setScanned(completed);
                if (candidate != null) job.getResults().add(candidate);
                if (nextPort <= job.getEndPort()) {
                    int port = nextPort++;
                    completion.submit(() -> inspectPort(address, job.getTarget(), port));
                    submitted++;
                }
            }
            job.getResults().sort(Comparator.comparingInt(WebScanModels.Candidate::getPort));
            job.setStatus(cancelled.remove(job.getId()) ? "cancelled" : "completed");
        } catch (Exception exception) {
            job.setStatus("failed");
            job.setError(exception.getMessage() == null ? "扫描失败" : exception.getMessage());
        } finally {
            workers.shutdownNow();
            job.setCompletedAt(System.currentTimeMillis());
        }
    }

    private WebScanModels.Candidate inspectPort(InetAddress address, String host, int port) {
        if (!isOpen(address, port)) return null;
        boolean tlsFirst = port == 443 || port == 8443 || port == 9443;
        WebScanModels.Candidate candidate = request(address, host, port, tlsFirst);
        return candidate != null ? candidate : request(address, host, port, !tlsFirst);
    }

    private boolean isOpen(InetAddress address, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MS);
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private WebScanModels.Candidate request(InetAddress address, String host, int port, boolean secure) {
        try (Socket socket = createSocket(address, host, port, secure)) {
            String hostHeader = host.contains(":") ? "[" + host + "]" : host;
            String request = "GET / HTTP/1.1\r\nHost: " + hostHeader + ":" + port
                    + "\r\nUser-Agent: Open-Panel-Scanner/1.0\r\nAccept: text/html,application/xhtml+xml\r\nConnection: close\r\n\r\n";
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.US_ASCII));
            output.flush();
            byte[] response = readLimited(socket.getInputStream());
            int headerEnd = indexOf(response, new byte[]{13, 10, 13, 10});
            if (headerEnd < 0) return null;
            String headers = new String(response, 0, headerEnd, StandardCharsets.ISO_8859_1);
            String firstLine = headers.lines().findFirst().orElse("");
            if (!firstLine.startsWith("HTTP/")) return null;
            int statusCode = parseStatus(firstLine);
            String contentType = headerValue(headers, "Content-Type").toLowerCase(Locale.ROOT);
            if (statusCode != 200
                    || !(contentType.startsWith("text/html") || contentType.startsWith("application/xhtml+xml"))
                    || "true".equalsIgnoreCase(headerValue(headers, "X-Open-Panel"))) {
                return null;
            }
            String protocol = secure ? "https" : "http";
            String url = protocol + "://" + hostHeader + (isDefaultPort(port, secure) ? "" : ":" + port) + "/";
            Document document = Jsoup.parse(new ByteArrayInputStream(response, headerEnd + 4,
                    response.length - headerEnd - 4), null, url);
            String title = extractTitle(document, host + ":" + port);
            Element descriptionElement = document.selectFirst("meta[name=description], meta[property=og:description]");
            String description = descriptionElement == null ? "发现的 Web 服务 · HTTP " + statusCode
                    : descriptionElement.attr("content").strip();
            Element iconElement = document.selectFirst("link[rel~=icon][href]");
            String icon = iconElement == null ? URI.create(url).resolve("/favicon.ico").toString() : iconElement.absUrl("href");
            if (icon.isBlank()) icon = URI.create(url).resolve("/favicon.ico").toString();
            return new WebScanModels.Candidate()
                    .setId(protocol + "-" + port)
                    .setHost(host)
                    .setPort(port)
                    .setProtocol(protocol)
                    .setUrl(url)
                    .setTitle(limit(title, 100))
                    .setDescription(limit(description, 240))
                    .setIconUrl(icon)
                    .setStatusCode(statusCode);
        } catch (Exception exception) {
            return null;
        }
    }

    private WebScanModels.Candidate fetchMetadata(URI uri) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(java.time.Duration.ofSeconds(5))
                    .header("User-Agent", "Open-Panel/1.0")
                    .header("Accept", "text/html,application/xhtml+xml,*/*")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = metadataHttpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IllegalArgumentException("链接返回 HTTP " + response.statusCode());
            }
            if (!(contentType.startsWith("text/html") || contentType.startsWith("application/xhtml+xml"))) {
                response.body().close();
                throw new IllegalArgumentException("链接返回的内容不是 HTML");
            }
            byte[] body;
            try (InputStream input = response.body()) {
                body = input.readNBytes(MAX_RESPONSE_BYTES);
            }
            String finalUrl = response.uri().toString();
            Document document = Jsoup.parse(new ByteArrayInputStream(body), null, finalUrl);
            String title = extractTitle(document, uri.getHost());
            Element descriptionElement = document.selectFirst(
                    "meta[name=description], meta[property=og:description], meta[name=twitter:description]");
            String description = descriptionElement == null ? "" : descriptionElement.attr("content").strip();
            if (description.isBlank()) description = "访问 " + title;
            Element iconElement = document.selectFirst("link[rel~=icon][href]");
            String icon = iconElement == null ? URI.create(finalUrl).resolve("/favicon.ico").toString()
                    : iconElement.absUrl("href");
            if (icon.isBlank()) icon = URI.create(finalUrl).resolve("/favicon.ico").toString();
            URI resolved = URI.create(finalUrl);
            int port = resolved.getPort() >= 0 ? resolved.getPort() : ("https".equals(resolved.getScheme()) ? 443 : 80);
            return new WebScanModels.Candidate()
                    .setId(UUID.randomUUID().toString())
                    .setHost(resolved.getHost())
                    .setPort(port)
                    .setProtocol(resolved.getScheme())
                    .setUrl(finalUrl)
                    .setTitle(limit(title, 100))
                    .setDescription(limit(description, 240))
                    .setIconUrl(icon)
                    .setStatusCode(response.statusCode());
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("无法读取链接信息: " + exception.getMessage(), exception);
        }
    }

    private Socket createSocket(InetAddress address, String host, int port, boolean secure) throws Exception {
        Socket base = new Socket();
        base.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MS);
        base.setSoTimeout(WEB_TIMEOUT_MS);
        if (!secure) return base;
        SSLSocket ssl = (SSLSocket) insecureSslContext.getSocketFactory().createSocket(base, host, port, true);
        SSLParameters parameters = ssl.getSSLParameters();
        if (!isIpLiteral(host)) parameters.setServerNames(List.of(new SNIHostName(host)));
        ssl.setSSLParameters(parameters);
        ssl.setSoTimeout(WEB_TIMEOUT_MS);
        ssl.startHandshake();
        return ssl;
    }

    private byte[] readLimited(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (output.size() < MAX_RESPONSE_BYTES) {
            int length = input.read(buffer, 0, Math.min(buffer.length, MAX_RESPONSE_BYTES - output.size()));
            if (length < 0) break;
            output.write(buffer, 0, length);
        }
        return output.toByteArray();
    }

    private int parseStatus(String line) {
        try {
            String[] parts = line.split(" ", 3);
            return parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String headerValue(String headers, String name) {
        for (String line : headers.split("\r\n")) {
            int separator = line.indexOf(':');
            if (separator > 0 && name.equalsIgnoreCase(line.substring(0, separator).strip())) {
                return line.substring(separator + 1).strip();
            }
        }
        return "";
    }

    private int indexOf(byte[] source, byte[] target) {
        outer: for (int index = 0; index <= source.length - target.length; index++) {
            for (int offset = 0; offset < target.length; offset++) {
                if (source[index + offset] != target[offset]) continue outer;
            }
            return index;
        }
        return -1;
    }

    private String normalizeTarget(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("请输入 IP 或域名");
        String target = value.strip();
        try {
            if (target.contains("://")) target = URI.create(target).getHost();
        } catch (Exception exception) {
            throw new IllegalArgumentException("IP 或域名格式不正确");
        }
        if (target == null || target.isBlank() || target.contains("/") || target.contains(" ")) {
            throw new IllegalArgumentException("IP 或域名格式不正确");
        }
        return target;
    }

    private URI normalizeLinkUri(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null) {
                throw new IllegalArgumentException("链接必须是有效的 HTTP 或 HTTPS URL");
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("链接必须是有效的 HTTP 或 HTTPS URL", exception);
        }
    }

    private boolean isDefaultPort(int port, boolean secure) {
        return secure ? port == 443 : port == 80;
    }

    private boolean isIpLiteral(String host) {
        return host.matches("[0-9.]+") || host.contains(":");
    }

    private String extractTitle(Document document, String fallback) {
        String title = cleanText(document.title());
        if (!title.isBlank()) return title;
        for (String selector : List.of(
                "meta[property=og:title]",
                "meta[name=twitter:title]",
                "meta[name=application-name]",
                "meta[name=apple-mobile-web-app-title]",
                "meta[itemprop=name]",
                "meta[property=og:site_name]")) {
            Element element = document.selectFirst(selector);
            title = element == null ? "" : cleanText(element.attr("content"));
            if (!title.isBlank()) return title;
        }
        Element heading = document.selectFirst("main h1, header h1, h1");
        title = heading == null ? "" : cleanText(heading.text());
        return title.isBlank() ? fallback : title;
    }

    private String cleanText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    private String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private WebScanModels.ScanJob snapshot(WebScanModels.ScanJob source) {
        return new WebScanModels.ScanJob()
                .setId(source.getId())
                .setTarget(source.getTarget())
                .setStartPort(source.getStartPort())
                .setEndPort(source.getEndPort())
                .setTotal(source.getTotal())
                .setScanned(source.getScanned())
                .setStatus(source.getStatus())
                .setStartedAt(source.getStartedAt())
                .setCompletedAt(source.getCompletedAt())
                .setError(source.getError())
                .setResults(new ArrayList<>(source.getResults()));
    }

    private void trimJobs() {
        if (jobs.size() <= 20) return;
        jobs.values().stream()
                .filter(job -> !"running".equals(job.getStatus()) && !"queued".equals(job.getStatus()))
                .sorted(Comparator.comparingLong(WebScanModels.ScanJob::getStartedAt))
                .limit(jobs.size() - 20L)
                .map(WebScanModels.ScanJob::getId)
                .toList().forEach(jobs::remove);
    }

    private SSLContext createScannerSslContext() {
        try {
            TrustManager[] trustManagers = {new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            }};
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers, new SecureRandom());
            return context;
        } catch (Exception exception) {
            throw new IllegalStateException("无法初始化扫描器 TLS", exception);
        }
    }

    @PreDestroy
    void shutdown() {
        cancelled.addAll(jobs.keySet());
        coordinators.shutdownNow();
    }
}
