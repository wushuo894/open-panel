package ani.rss.service;

import ani.rss.entity.PanelConfig;
import ani.rss.repository.JsonConfigRepository;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.CpuStatsConfig;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.MemoryStatsConfig;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.NetworkIF;
import org.springframework.stereotype.Service;

import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class StatusService {
    private final JsonConfigRepository repository;
    private final SoftwareServiceStatusService softwareServiceStatusService;
    private final SystemInfo systemInfo = new SystemInfo();
    private long[] previousCpuTicks;

    public StatusService(JsonConfigRepository repository, SoftwareServiceStatusService softwareServiceStatusService) {
        this.repository = repository;
        this.softwareServiceStatusService = softwareServiceStatusService;
        previousCpuTicks = systemInfo.getHardware().getProcessor().getSystemCpuLoadTicks();
    }

    public Map<String, Object> systemStatuses() {
        PanelConfig config = repository.get();
        List<PanelConfig.Card> cards = config.getCards().stream()
                .filter(card -> card.isEnabled() && "system".equals(card.getType()))
                .toList();
        boolean commonStatusNeeded = cards.stream()
                .anyMatch(card -> card.getSystem() == null || !"storage".equals(card.getSystem().getMetric()));
        Map<String, Object> commonStatus = commonStatusNeeded ? systemStatus() : Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        for (PanelConfig.Card card : cards) {
            boolean storage = card.getSystem() != null && "storage".equals(card.getSystem().getMetric());
            result.put(card.getId(), storage ? storageStatus(card.getSystem()) : commonStatus);
        }
        return result;
    }

    public Map<String, Object> serviceStatuses() {
        List<PanelConfig.Card> cards = repository.get().getCards().stream()
                .filter(card -> card.isEnabled() && "service".equals(card.getType()) && card.getService() != null)
                .toList();
        Map<String, Future<Map<String, Object>>> futures = new LinkedHashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            cards.forEach(card -> futures.put(card.getId(),
                    executor.submit(() -> softwareServiceStatusService.status(card.getService()))));
            Map<String, Object> result = new LinkedHashMap<>();
            for (PanelConfig.Card card : cards) {
                try {
                    result.put(card.getId(), futures.get(card.getId()).get());
                } catch (Exception exception) {
                    result.put(card.getId(), offline("连接失败"));
                }
            }
            return result;
        }
    }

    public Map<String, Object> dockerStatuses() {
        List<PanelConfig.Card> cards = repository.get().getCards();
        Map<String, Map<String, Object>> containerStatuses = collectDockerStatuses(cards);
        Map<String, Object> result = new LinkedHashMap<>();
        cards.stream()
                .filter(card -> card.isEnabled() && "docker".equals(card.getType()) && card.getDocker() != null)
                .forEach(card -> result.put(card.getId(), containerStatuses.getOrDefault(
                        card.getDocker().getContainerId(), offline("未找到容器"))));
        return result;
    }

    public List<Map<String, Object>> containers() {
        List<Map<String, Object>> result = new ArrayList<>();
        var dockerConfig = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        try (var transport = new ApacheDockerHttpClient.Builder()
                .dockerHost(dockerConfig.getDockerHost())
                .sslConfig(dockerConfig.getSSLConfig())
                .connectionTimeout(Duration.ofSeconds(3))
                .responseTimeout(Duration.ofSeconds(4))
                .build(); DockerClient docker = DockerClientImpl.getInstance(dockerConfig, transport)) {
            for (Container container : docker.listContainersCmd().withShowAll(true).exec()) {
                String name = container.getNames() == null || container.getNames().length == 0
                        ? container.getId().substring(0, Math.min(12, container.getId().length()))
                        : container.getNames()[0].replaceFirst("^/", "");
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", name);
                item.put("containerId", container.getId());
                item.put("name", name);
                item.put("image", container.getImage());
                item.put("state", container.getState());
                item.put("label", name + " · " + container.getImage() + " · " + container.getState());
                result.add(item);
            }
            return result;
        } catch (Exception | LinkageError exception) {
            throw new IllegalStateException("Docker 不可用，请检查 Socket 挂载和权限", exception);
        }
    }

    public Map<String, Object> containerAction(String containerId, String action) {
        if (containerId == null || containerId.isBlank()) throw new IllegalArgumentException("容器 ID 或名称不能为空");
        if (!Set.of("start", "stop", "restart").contains(action)) throw new IllegalArgumentException("不支持的容器操作");
        var dockerConfig = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        try (var transport = new ApacheDockerHttpClient.Builder()
                .dockerHost(dockerConfig.getDockerHost())
                .sslConfig(dockerConfig.getSSLConfig())
                .connectionTimeout(Duration.ofSeconds(3))
                .responseTimeout(Duration.ofSeconds(12))
                .build(); DockerClient docker = DockerClientImpl.getInstance(dockerConfig, transport)) {
            switch (action) {
                case "start" -> docker.startContainerCmd(containerId).exec();
                case "stop" -> docker.stopContainerCmd(containerId).withTimeout(10).exec();
                case "restart" -> docker.restartContainerCmd(containerId).withTimeout(10).exec();
                default -> throw new IllegalArgumentException("不支持的容器操作");
            }
            return containerStatus(docker, containerId);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception | LinkageError exception) {
            throw new IllegalStateException("容器操作失败: " + safeMessage(exception), exception);
        }
    }

    private synchronized Map<String, Object> systemStatus() {
        var hardware = systemInfo.getHardware();
        CentralProcessor processor = hardware.getProcessor();
        double cpu = processor.getSystemCpuLoadBetweenTicks(previousCpuTicks) * 100;
        previousCpuTicks = processor.getSystemCpuLoadTicks();
        GlobalMemory memory = hardware.getMemory();
        long receive = 0;
        long sent = 0;
        for (NetworkIF network : hardware.getNetworkIFs()) {
            network.updateAttributes();
            receive += network.getBytesRecv();
            sent += network.getBytesSent();
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("online", true);
        value.put("cpuPercent", Math.round(Math.max(0, cpu) * 10) / 10.0);
        value.put("memoryUsed", memory.getTotal() - memory.getAvailable());
        value.put("memoryTotal", memory.getTotal());
        value.put("networkReceived", receive);
        value.put("networkSent", sent);
        return value;
    }

    private Map<String, Object> storageStatus(PanelConfig.SystemCard systemCard) {
        String configuredPath = systemCard.getStoragePath();
        if (configuredPath == null || configuredPath.isBlank()) configuredPath = ".";
        Map<String, Object> value = new LinkedHashMap<>();
        try {
            Path path = Path.of(configuredPath).toAbsolutePath().normalize();
            if (!Files.isDirectory(path)) throw new IllegalArgumentException("文件夹不存在");
            FileStore store = Files.getFileStore(path);
            long total = store.getTotalSpace();
            long available = store.getUsableSpace();
            value.put("online", true);
            value.put("diskPath", path.toString());
            value.put("diskUsed", Math.max(0, total - available));
            value.put("diskAvailable", available);
            value.put("diskTotal", total);
        } catch (Exception exception) {
            value.put("online", false);
            value.put("diskPath", configuredPath);
            value.put("status", "无法读取路径: " + safeMessage(exception));
        }
        return value;
    }

    private Map<String, Map<String, Object>> collectDockerStatuses(List<PanelConfig.Card> cards) {
        boolean needed = cards.stream().anyMatch(card -> card.isEnabled() && "docker".equals(card.getType()));
        if (!needed) return Map.of();
        Map<String, Map<String, Object>> result = new HashMap<>();
        Set<String> requestedContainers = new HashSet<>();
        cards.stream()
                .filter(card -> card.isEnabled() && "docker".equals(card.getType()) && card.getDocker() != null)
                .map(card -> card.getDocker().getContainerId())
                .filter(id -> id != null && !id.isBlank())
                .forEach(requestedContainers::add);
        var dockerConfig = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        try (var transport = new ApacheDockerHttpClient.Builder()
                .dockerHost(dockerConfig.getDockerHost())
                .sslConfig(dockerConfig.getSSLConfig())
                .connectionTimeout(Duration.ofSeconds(3))
                .responseTimeout(Duration.ofSeconds(4))
                .build(); DockerClient docker = DockerClientImpl.getInstance(dockerConfig, transport)) {
            List<Container> containers = docker.listContainersCmd().withShowAll(true).exec();
            Map<String, Future<Map<String, Object>>> detailFutures = new HashMap<>();
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (Container container : containers) {
                    if (isRequested(container, requestedContainers)) {
                        detailFutures.put(container.getId(), executor.submit(() -> containerStatus(docker, container.getId())));
                    }
                }
                for (Container container : containers) {
                    Map<String, Object> status = basicContainerStatus(container);
                    Future<Map<String, Object>> future = detailFutures.get(container.getId());
                    if (future != null) {
                        try {
                            status = future.get();
                        } catch (Exception ignored) {
                        }
                    }
                    result.put(container.getId(), status);
                    if (container.getNames() != null) {
                        for (String name : container.getNames()) result.put(name.replaceFirst("^/", ""), status);
                    }
                }
            }
        } catch (Exception | LinkageError exception) {
            result.put("", offline("Docker 不可用"));
        }
        return result;
    }

    private boolean isRequested(Container container, Set<String> requestedContainers) {
        if (requestedContainers.stream().anyMatch(id -> container.getId().startsWith(id))) return true;
        if (container.getNames() == null) return false;
        for (String name : container.getNames()) {
            if (requestedContainers.contains(name.replaceFirst("^/", ""))) return true;
        }
        return false;
    }

    private Map<String, Object> basicContainerStatus(Container container) {
        Map<String, Object> status = new LinkedHashMap<>();
        boolean running = "running".equalsIgnoreCase(container.getState());
        status.put("online", running);
        status.put("state", container.getState());
        status.put("status", container.getStatus());
        status.put("summary", running ? "运行中" : stateLabel(container.getState()));
        return status;
    }

    private Map<String, Object> containerStatus(DockerClient docker, String containerId) throws Exception {
        InspectContainerResponse inspect = docker.inspectContainerCmd(containerId).exec();
        InspectContainerResponse.ContainerState state = inspect.getState();
        boolean running = state != null && Boolean.TRUE.equals(state.getRunning());
        String stateValue = state == null || state.getStatus() == null ? "unknown" : state.getStatus();
        String health = state != null && state.getHealth() != null ? state.getHealth().getStatus() : "";
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("online", running && !"unhealthy".equalsIgnoreCase(health));
        status.put("state", stateValue);
        status.put("status", stateLabel(stateValue));

        if (health != null && !health.isBlank()) status.put("health", health);
        long uptimeSeconds = running ? uptimeSeconds(state == null ? null : state.getStartedAt()) : 0;
        status.put("uptimeSeconds", uptimeSeconds);

        if (running) {
            Statistics statistics = readStatistics(docker, containerId);
            if (statistics != null) addResourceUsage(status, statistics);
        }
        status.put("summary", running ? "运行 " + duration(uptimeSeconds) : stateLabel(stateValue));
        return status;
    }

    private Statistics readStatistics(DockerClient docker, String containerId) throws Exception {
        AtomicReference<Statistics> value = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        ResultCallback.Adapter<Statistics> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Statistics statistics) {
                value.compareAndSet(null, statistics);
                completed.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                completed.countDown();
            }

            @Override
            public void onComplete() {
                completed.countDown();
            }
        };
        try {
            docker.statsCmd(containerId).withNoStream(true).exec(callback);
            completed.await(3, TimeUnit.SECONDS);
            return value.get();
        } finally {
            callback.close();
        }
    }

    private void addResourceUsage(Map<String, Object> status, Statistics statistics) {
        double cpuPercent = cpuPercent(statistics);
        status.put("cpuPercent", Math.round(Math.max(0, cpuPercent) * 10d) / 10d);
        MemoryStatsConfig memory = statistics.getMemoryStats();
        if (memory == null) return;
        long usage = memory.getUsage() == null ? 0 : memory.getUsage();
        long cache = memory.getStats() == null || memory.getStats().getCache() == null ? 0 : memory.getStats().getCache();
        status.put("memoryUsed", Math.max(0, usage - cache));
        status.put("memoryLimit", memory.getLimit() == null ? 0 : memory.getLimit());
    }

    private double cpuPercent(Statistics statistics) {
        CpuStatsConfig cpu = statistics.getCpuStats();
        CpuStatsConfig previous = statistics.getPreCpuStats();
        if (cpu == null || previous == null || cpu.getCpuUsage() == null || previous.getCpuUsage() == null) return 0;
        Long total = cpu.getCpuUsage().getTotalUsage();
        Long previousTotal = previous.getCpuUsage().getTotalUsage();
        Long system = cpu.getSystemCpuUsage();
        Long previousSystem = previous.getSystemCpuUsage();
        if (total == null || previousTotal == null || system == null || previousSystem == null) return 0;
        long cpuDelta = total - previousTotal;
        long systemDelta = system - previousSystem;
        if (cpuDelta <= 0 || systemDelta <= 0) return 0;
        long onlineCpus = cpu.getOnlineCpus() == null ? 0 : cpu.getOnlineCpus();
        if (onlineCpus <= 0 && cpu.getCpuUsage().getPercpuUsage() != null) {
            onlineCpus = cpu.getCpuUsage().getPercpuUsage().size();
        }
        return (double) cpuDelta / systemDelta * Math.max(1, onlineCpus) * 100d;
    }

    private long uptimeSeconds(String startedAt) {
        if (startedAt == null || startedAt.isBlank()) return 0;
        try {
            return Math.max(0, Duration.between(Instant.parse(startedAt), Instant.now()).getSeconds());
        } catch (DateTimeParseException ignored) {
            return 0;
        }
    }

    private String duration(long seconds) {
        long days = seconds / 86400;
        long hours = seconds % 86400 / 3600;
        if (days > 0) return days + "天" + (hours > 0 ? hours + "小时" : "");
        long minutes = seconds % 3600 / 60;
        if (hours > 0) return hours + "小时" + (minutes > 0 ? minutes + "分" : "");
        return Math.max(0, minutes) + "分钟";
    }

    private String stateLabel(String state) {
        if (state == null) return "未知";
        return switch (state.toLowerCase()) {
            case "running" -> "运行中";
            case "created" -> "已创建";
            case "exited" -> "已停止";
            case "paused" -> "已暂停";
            case "restarting" -> "重启中";
            case "dead" -> "异常退出";
            default -> state;
        };
    }

    private String safeMessage(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private Map<String, Object> offline(String status) {
        return new LinkedHashMap<>(Map.of("online", false, "status", status));
    }
}
