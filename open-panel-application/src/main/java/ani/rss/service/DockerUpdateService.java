package ani.rss.service;

import ani.rss.entity.PanelConfig;
import ani.rss.entity.web.DockerUpdateModels;
import ani.rss.repository.JsonConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.AuthConfig;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerConfig;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.api.model.PruneType;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.core.RemoteApiVersion;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.Closeable;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DockerUpdateService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DockerUpdateService.class);
    private static final int MAX_JOBS = 12;
    private static final int MAX_LOGS = 300;
    private static final String SOURCE_IMAGE_LABEL = "io.github.wushuo894.open-panel.source-image";
    private static final String MANIFEST_ACCEPT = String.join(", ",
            "application/vnd.oci.image.index.v1+json",
            "application/vnd.oci.image.manifest.v1+json",
            "application/vnd.docker.distribution.manifest.list.v2+json",
            "application/vnd.docker.distribution.manifest.v2+json");
    private static final Pattern AUTH_PARAMETER = Pattern.compile("([a-zA-Z]+)=\\\"([^\\\"]*)\\\"");
    private final JsonConfigRepository repository;
    private final HttpClient registryHttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Map<String, JobState> jobs = new ConcurrentHashMap<>();
    private final Set<String> checkedImages = ConcurrentHashMap.newKeySet();
    private final Map<String, String> imageCheckErrors = new ConcurrentHashMap<>();
    private final Map<String, String> remoteImageDigests = new ConcurrentHashMap<>();
    private final Map<String, StagedImage> stagedImages = new ConcurrentHashMap<>();
    private final AtomicReference<String> activeJobId = new AtomicReference<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public DockerUpdateService(JsonConfigRepository repository) {
        this.repository = repository;
    }

    public DockerUpdateModels.DockerOverview overview() {
        DockerUpdateModels.DockerOverview result = new DockerUpdateModels.DockerOverview();
        try (DockerConnection connection = connect(Duration.ofSeconds(8))) {
            connection.client().pingCmd().exec();
            Map<String, Set<String>> localDigestCache = new ConcurrentHashMap<>();
            List<DockerUpdateModels.ContainerInfo> containers = connection.client().listContainersCmd()
                    .withShowAll(true)
                    .exec()
                    .stream()
                    .filter(container -> !isOpenPanelContainer(container))
                    .map(container -> containerInfo(connection.client(), container, localDigestCache))
                    .sorted(Comparator.comparing(DockerUpdateModels.ContainerInfo::getName, String.CASE_INSENSITIVE_ORDER))
                    .toList();
            result.setAvailable(true).setContainers(containers).setActiveJob(activeJob());
        } catch (Exception | LinkageError exception) {
            result.setAvailable(false).setError("Docker 不可用，请检查 Socket 挂载和权限");
        }
        return result;
    }

    public DockerUpdateModels.ComposeView compose(String containerId) {
        if (containerId == null || containerId.isBlank()) throw new IllegalArgumentException("容器 ID 或名称不能为空");
        try (DockerConnection connection = connect(Duration.ofSeconds(12))) {
            Container container = findContainer(connection.client(), containerId.strip());
            InspectContainerResponse inspect = connection.client().inspectContainerCmd(container.getId()).exec();
            return new DockerUpdateModels.ComposeView()
                    .setContainerId(container.getId())
                    .setContainerName(containerName(container))
                    .setContent(composeYaml(connection.client(), container, inspect));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception | LinkageError exception) {
            throw new IllegalStateException("读取容器配置失败: " + safeMessage(exception), exception);
        }
    }

    public synchronized DockerUpdateModels.ImageCleanupResult cleanupUnusedImages() {
        assertIdle();
        try (DockerConnection connection = connect(Duration.ofMinutes(5))) {
            DockerClient docker = connection.client();
            int before = docker.listImagesCmd().withShowAll(true).exec().size();
            var result = docker.pruneCmd(PruneType.IMAGES).withDangling(false).exec();
            int after = docker.listImagesCmd().withShowAll(true).exec().size();
            stagedImages.clear();
            checkedImages.clear();
            imageCheckErrors.clear();
            remoteImageDigests.clear();
            return new DockerUpdateModels.ImageCleanupResult()
                    .setDeletedImages(Math.max(0, before - after))
                    .setSpaceReclaimed(result.getSpaceReclaimed() == null ? 0 : result.getSpaceReclaimed());
        } catch (Exception | LinkageError exception) {
            throw new IllegalStateException("清理未使用镜像失败: " + safeMessage(exception), exception);
        }
    }

    private String composeYaml(DockerClient docker, Container container, InspectContainerResponse inspect) {
        String name = cleanName(inspect.getName());
        ContainerConfig config = inspect.getConfig();
        HostConfig host = inspect.getHostConfig();
        Map<String, Object> service = new LinkedHashMap<>();
        service.put("image", resolveContainerImage(docker, container, inspect));
        service.put("container_name", name);
        if (config != null) {
            putText(service, "hostname", config.getHostName());
            putText(service, "domainname", config.getDomainName());
            putText(service, "user", config.getUser());
            putText(service, "working_dir", config.getWorkingDir());
            if (config.getEntrypoint() != null && config.getEntrypoint().length > 0) {
                service.put("entrypoint", Arrays.asList(config.getEntrypoint()));
            }
            if (config.getCmd() != null && config.getCmd().length > 0) service.put("command", Arrays.asList(config.getCmd()));
            if (config.getEnv() != null && config.getEnv().length > 0) service.put("environment", Arrays.asList(config.getEnv()));
            if (Boolean.TRUE.equals(config.getStdinOpen())) service.put("stdin_open", true);
            if (Boolean.TRUE.equals(config.getTty())) service.put("tty", true);
            if (config.getLabels() != null) {
                Map<String, String> labels = new LinkedHashMap<>();
                config.getLabels().forEach((key, value) -> {
                    if (!key.startsWith("com.docker.compose.")) labels.put(key, value);
                });
                if (!labels.isEmpty()) service.put("labels", labels);
            }
        }
        if (host != null) addHostConfig(service, host, inspect);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("services", Map.of(composeServiceName(name), service));
        addComposeResources(root, service, inspect);
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setWidth(160);
        options.setSplitLines(false);
        return new Yaml(options).dump(root);
    }

    private void addHostConfig(Map<String, Object> service, HostConfig host, InspectContainerResponse inspect) {
        if (host.getRestartPolicy() != null && notBlank(host.getRestartPolicy().getName())
                && !"no".equals(host.getRestartPolicy().getName())) {
            String restart = host.getRestartPolicy().getName();
            if ("on-failure".equals(restart) && host.getRestartPolicy().getMaximumRetryCount() != null
                    && host.getRestartPolicy().getMaximumRetryCount() > 0) {
                restart += ':' + host.getRestartPolicy().getMaximumRetryCount().toString();
            }
            service.put("restart", restart);
        }
        String networkMode = host.getNetworkMode();
        if (notBlank(networkMode) && (Set.of("host", "none").contains(networkMode) || networkMode.startsWith("container:"))) {
            service.put("network_mode", networkMode);
        } else {
            List<String> ports = composePorts(host);
            if (!ports.isEmpty()) service.put("ports", ports);
        }
        List<String> volumes = composeVolumes(host, inspect);
        if (!volumes.isEmpty()) service.put("volumes", volumes);
        if (host.getVolumesFrom() != null && host.getVolumesFrom().length > 0) {
            service.put("volumes_from", Arrays.stream(host.getVolumesFrom()).map(Object::toString).toList());
        }
        if (Boolean.TRUE.equals(host.getPrivileged())) service.put("privileged", true);
        if (Boolean.TRUE.equals(host.getReadonlyRootfs())) service.put("read_only", true);
        if (host.getCapAdd() != null && host.getCapAdd().length > 0) {
            service.put("cap_add", Arrays.stream(host.getCapAdd()).map(Enum::name).toList());
        }
        if (host.getCapDrop() != null && host.getCapDrop().length > 0) {
            service.put("cap_drop", Arrays.stream(host.getCapDrop()).map(Enum::name).toList());
        }
        if (host.getDns() != null && host.getDns().length > 0) service.put("dns", Arrays.asList(host.getDns()));
        if (host.getDnsSearch() != null && host.getDnsSearch().length > 0) service.put("dns_search", Arrays.asList(host.getDnsSearch()));
        if (host.getExtraHosts() != null && host.getExtraHosts().length > 0) service.put("extra_hosts", Arrays.asList(host.getExtraHosts()));
        if (host.getGroupAdd() != null && !host.getGroupAdd().isEmpty()) service.put("group_add", host.getGroupAdd());
        if (host.getSysctls() != null && !host.getSysctls().isEmpty()) service.put("sysctls", host.getSysctls());
        if (host.getTmpFs() != null && !host.getTmpFs().isEmpty()) service.put("tmpfs", host.getTmpFs());
        putText(service, "pid", host.getPidMode());
        putText(service, "ipc", host.getIpcMode());
        if (host.getShmSize() != null && host.getShmSize() > 0) service.put("shm_size", host.getShmSize());
    }

    private List<String> composePorts(HostConfig host) {
        if (host.getPortBindings() == null || host.getPortBindings().getBindings() == null) return List.of();
        List<String> result = new ArrayList<>();
        host.getPortBindings().getBindings().forEach((port, bindings) -> {
            if (bindings == null) return;
            for (var binding : bindings) {
                String containerPort = port.getPort() + ("tcp".equalsIgnoreCase(port.getScheme()) ? "" : "/" + port.getScheme());
                String hostPort = binding.getHostPortSpec();
                String hostIp = binding.getHostIp();
                if (!notBlank(hostPort)) {
                    result.add(containerPort);
                } else if (notBlank(hostIp) && !"0.0.0.0".equals(hostIp) && !"::".equals(hostIp)) {
                    result.add(hostIp + ':' + hostPort + ':' + containerPort);
                } else {
                    result.add(hostPort + ':' + containerPort);
                }
            }
        });
        return result;
    }

    private List<String> composeVolumes(HostConfig host, InspectContainerResponse inspect) {
        List<String> result = new ArrayList<>();
        Set<String> destinations = new LinkedHashSet<>();
        if (host.getBinds() != null) {
            for (Bind bind : host.getBinds()) {
                result.add(bind.toString());
                if (bind.getVolume() != null) destinations.add(bind.getVolume().getPath());
            }
        }
        if (inspect.getMounts() != null) {
            inspect.getMounts().stream()
                    .filter(mount -> notBlank(mount.getName()) && mount.getDestination() != null)
                    .filter(mount -> destinations.add(mount.getDestination().getPath()))
                    .forEach(mount -> result.add(mount.getName() + ':' + mount.getDestination().getPath()
                            + (Boolean.FALSE.equals(mount.getRW()) ? ":ro" : "")));
        }
        return result;
    }

    private void addComposeResources(Map<String, Object> root, Map<String, Object> service,
                                     InspectContainerResponse inspect) {
        if (inspect.getMounts() != null) {
            Map<String, Object> volumes = new LinkedHashMap<>();
            inspect.getMounts().stream().filter(mount -> notBlank(mount.getName())).forEach(mount ->
                    volumes.put(mount.getName(), Map.of("external", true, "name", mount.getName())));
            if (!volumes.isEmpty()) root.put("volumes", volumes);
        }
        if (inspect.getNetworkSettings() == null || inspect.getNetworkSettings().getNetworks() == null
                || inspect.getHostConfig() == null || "host".equals(inspect.getHostConfig().getNetworkMode())) return;
        List<String> names = inspect.getNetworkSettings().getNetworks().keySet().stream()
                .filter(name -> !"bridge".equals(name)).toList();
        if (names.isEmpty()) return;
        service.put("networks", names);
        Map<String, Object> networks = new LinkedHashMap<>();
        names.forEach(name -> networks.put(name, Map.of("external", true, "name", name)));
        root.put("networks", networks);
    }

    private void putText(Map<String, Object> target, String key, String value) {
        if (notBlank(value)) target.put(key, value);
    }

    private String composeServiceName(String name) {
        String normalized = name.replaceAll("[^a-zA-Z0-9_.-]", "-");
        return normalized.isBlank() ? "service" : normalized;
    }

    public synchronized DockerUpdateModels.DockerJob startCheck() {
        assertIdle();
        JobState state = createJob("check");
        submit(state, () -> checkImages(state));
        return snapshot(state);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkAfterStartup() {
        startAutomaticCheck();
    }

    @Scheduled(fixedDelay = 1, initialDelay = 1, timeUnit = TimeUnit.HOURS)
    public void checkHourly() {
        startAutomaticCheck();
    }

    private void startAutomaticCheck() {
        try {
            startCheck();
        } catch (IllegalStateException ignored) {
            // A manual check or container update is already running; retry on the next schedule.
        }
    }

    public synchronized DockerUpdateModels.DockerJob startUpdate(String containerId) {
        if (containerId == null || containerId.isBlank()) throw new IllegalArgumentException("容器 ID 或名称不能为空");
        assertIdle();
        JobState state = createJob("update");
        state.job.setContainerId(containerId.strip());
        submit(state, () -> updateContainer(state, containerId.strip()));
        return snapshot(state);
    }

    public DockerUpdateModels.DockerJob job(String id) {
        JobState state = jobs.get(id);
        if (state == null) throw new IllegalArgumentException("Docker 任务不存在");
        return snapshot(state);
    }

    public void cancel(String id) {
        JobState state = jobs.get(id);
        if (state == null) throw new IllegalArgumentException("Docker 任务不存在");
        if (terminal(state.job.getStatus())) return;
        if (!state.job.isCancellable()) throw new IllegalStateException("容器正在安全替换，当前阶段不能中断");
        state.cancelRequested.set(true);
        state.job.setStatus("cancelling").setPhase("cancelling");
        log(state, "warn", "正在中断任务");
        Closeable callback = state.activeCallback;
        if (callback != null) {
            try {
                callback.close();
            } catch (Exception ignored) {
            }
        }
    }

    private JobState createJob(String type) {
        String id = UUID.randomUUID().toString();
        JobState state = new JobState(new DockerUpdateModels.DockerJob()
                .setId(id)
                .setType(type)
                .setStatus("queued")
                .setPhase("queued")
                .setCancellable(true)
                .setStartedAt(System.currentTimeMillis())
                .setLogs(new CopyOnWriteArrayList<>()));
        jobs.put(id, state);
        activeJobId.set(id);
        trimJobs();
        return state;
    }

    private void submit(JobState state, Runnable task) {
        executor.submit(() -> {
            try {
                task.run();
            } catch (JobCancelledException exception) {
                state.job.setStatus("cancelled").setPhase("cancelled").setCancellable(false);
                log(state, "warn", "任务已中断");
            } catch (Exception | LinkageError exception) {
                String message = safeMessage(exception);
                state.job.setStatus("failed").setPhase("failed").setError(message).setCancellable(false);
                log(state, "error", message);
            } finally {
                state.job.setCompletedAt(System.currentTimeMillis());
                activeJobId.compareAndSet(state.job.getId(), null);
            }
        });
    }

    private void checkImages(JobState state) {
        state.job.setStatus("running").setPhase("loading");
        log(state, "info", "正在读取容器列表");
        try (DockerConnection connection = connect(Duration.ofMinutes(30))) {
            DockerClient docker = connection.client();
            List<Container> containers = docker.listContainersCmd().withShowAll(true).exec();
            Map<String, String> images = new LinkedHashMap<>();
            for (Container container : containers) {
                if (isOpenPanelContainer(container)) continue;
                InspectContainerResponse inspect = inspectContainer(docker, container.getId());
                String image = resolveContainerImage(docker, container, inspect);
                if (pullableImage(image)) images.putIfAbsent(image, container.getImageId());
            }
            state.job.setTotalItems(images.size());
            if (images.isEmpty()) {
                complete(state, "没有可检查更新的镜像");
                return;
            }
            int index = 0;
            for (Map.Entry<String, String> entry : images.entrySet()) {
                String image = entry.getKey();
                checkCancelled(state);
                state.job.setImage(image).setPhase("checking");
                log(state, "info", "查询远端镜像摘要 " + image);
                try {
                    String remoteDigest = inspectRemoteDigest(connection, image);
                    Set<String> localDigests = inspectLocalDigests(docker, image);
                    if (localDigests.isEmpty()) {
                        throw new IllegalStateException("本地镜像没有仓库摘要，无法判断是否有更新");
                    }
                    boolean updateAvailable = !localDigests.contains(remoteDigest);
                    checkedImages.add(image);
                    imageCheckErrors.remove(image);
                    remoteImageDigests.put(image, remoteDigest);
                    log(state, "success", updateAvailable
                            ? "发现新镜像 " + image + " · " + shortId(remoteDigest)
                            : "镜像已是最新 " + image);
                } catch (JobCancelledException exception) {
                    throw exception;
                } catch (Exception exception) {
                    checkedImages.add(image);
                    remoteImageDigests.remove(image);
                    String message = safeMessage(exception);
                    imageCheckErrors.put(image, message);
                    log(state, "error", image + "：" + message);
                }
                index++;
                state.job.setCompletedItems(index).setProgress(Math.round(index * 100f / images.size()));
            }
            complete(state, "镜像更新检查完成");
        }
    }

    private void updateContainer(JobState state, String containerId) {
        state.job.setStatus("running").setPhase("loading").setProgress(2);
        log(state, "info", "正在读取容器配置");
        try (DockerConnection connection = connect(Duration.ofMinutes(30))) {
            DockerClient docker = connection.client();
            Container listed = findContainer(docker, containerId);
            InspectContainerResponse inspect = docker.inspectContainerCmd(listed.getId()).exec();
            String name = containerName(listed);
            String image = resolveContainerImage(docker, listed, inspect);
            state.job.setContainerId(listed.getId()).setContainerName(name).setImage(image);
            String blockReason = updateBlockReason(listed, inspect, image);
            if (!blockReason.isBlank()) throw new IllegalStateException(blockReason);

            checkCancelled(state);
            state.job.setPhase("pulling").setProgress(5);
            StagedImage staged = stagedImages.get(image);
            if (staged == null || sameImage(inspect.getImageId(), staged.newImageId())) {
                log(state, "info", "正在拉取 " + image);
                staged = stageImage(connection, image, inspect.getImageId(), state,
                        value -> state.job.setProgress(5 + Math.round(value * 0.60f)));
            } else {
                log(state, "info", "使用已暂存的新镜像 " + staged.hashReference());
                state.job.setProgress(65);
            }
            checkedImages.add(image);
            imageCheckErrors.remove(image);
            String currentImageId = inspect.getImageId();
            String latestImageId = staged.newImageId();
            if (sameImage(currentImageId, latestImageId)) {
                complete(state, "容器已使用最新镜像");
                return;
            }

            checkCancelled(state);
            state.job.setCancellable(false).setPhase("recreating").setProgress(70);
            log(state, "success", "发现新镜像 " + shortId(latestImageId));
            replaceContainer(docker, state, inspect, image, currentImageId, staged);
            complete(state, "容器更新完成");
        }
    }

    private void replaceContainer(DockerClient docker, JobState state, InspectContainerResponse original,
                                  String image, String oldImageId, StagedImage staged) {
        String oldId = original.getId();
        String name = cleanName(original.getName());
        boolean wasRunning = original.getState() != null && Boolean.TRUE.equals(original.getState().getRunning());
        String newId = null;
        try {
            tagHash(docker, staged.newImageId(), staged.reference());
            tagOriginal(docker, oldImageId, staged.reference());
            if (wasRunning) {
                log(state, "info", "正在停止容器 " + name);
                docker.stopContainerCmd(oldId).withTimeout(20).exec();
            }
            state.job.setProgress(78);
            log(state, "info", "正在移除旧容器，数据卷将保留");
            docker.removeContainerCmd(oldId).withForce(true).withRemoveVolumes(false).exec();
            state.job.setProgress(82);
            String oldHashReference = tagHash(docker, oldImageId, staged.reference());
            log(state, "info", "旧镜像已保存为 " + oldHashReference);
            state.job.setProgress(86);
            tagOriginal(docker, staged.newImageId(), staged.reference());
            log(state, "info", "新镜像已恢复标签 " + image);
            state.job.setProgress(90);
            log(state, "info", "正在使用新镜像重建容器");
            newId = createFromSnapshot(docker, original, image);
            connectAdditionalNetworks(docker, newId, original);
            if (wasRunning) {
                state.job.setProgress(93);
                log(state, "info", "正在启动新容器");
                docker.startContainerCmd(newId).exec();
            }
            updateCardReferences(oldId, name);
            removeTemporaryTag(docker, staged.hashReference());
        } catch (Exception exception) {
            log(state, "error", "更新失败，正在恢复旧镜像");
            rollback(docker, original, oldImageId, newId, wasRunning, state, staged.reference());
            throw new IllegalStateException("容器更新失败，已恢复旧版本: " + safeMessage(exception), exception);
        }
    }

    private void rollback(DockerClient docker, InspectContainerResponse original, String oldImageId,
                          String newId, boolean wasRunning, JobState state, ImageReference reference) {
        try {
            if (newId != null) {
                try {
                    docker.removeContainerCmd(newId).withForce(true).withRemoveVolumes(false).exec();
                } catch (Exception ignored) {
                }
            }
            tagOriginal(docker, oldImageId, reference);
            String restoredId = createFromSnapshot(docker, original, reference.namedReference());
            connectAdditionalNetworks(docker, restoredId, original);
            if (wasRunning) docker.startContainerCmd(restoredId).exec();
            log(state, "warn", "旧容器已恢复");
        } catch (Exception rollbackException) {
            log(state, "error", "自动恢复失败: " + safeMessage(rollbackException));
            throw new IllegalStateException("自动恢复旧容器失败: " + safeMessage(rollbackException), rollbackException);
        }
    }

    private String createFromSnapshot(DockerClient docker, InspectContainerResponse snapshot, String image) {
        ContainerConfig config = snapshot.getConfig();
        HostConfig hostConfig = preserveNamedVolumes(snapshot);
        CreateContainerCmd command = docker.createContainerCmd(image)
                .withName(cleanName(snapshot.getName()))
                .withHostConfig(hostConfig);
        if (config != null) {
            if (config.getCmd() != null) command.withCmd(config.getCmd());
            if (config.getEntrypoint() != null) command.withEntrypoint(config.getEntrypoint());
            if (config.getEnv() != null) command.withEnv(config.getEnv());
            if (config.getExposedPorts() != null) command.withExposedPorts(config.getExposedPorts());
            if (config.getHostName() != null) command.withHostName(config.getHostName());
            if (config.getDomainName() != null) command.withDomainName(config.getDomainName());
            if (config.getUser() != null) command.withUser(config.getUser());
            if (config.getWorkingDir() != null) command.withWorkingDir(config.getWorkingDir());
            Map<String, String> labels = config.getLabels() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(config.getLabels());
            labels.put(SOURCE_IMAGE_LABEL, image);
            command.withLabels(labels);
            if (config.getHealthcheck() != null) command.withHealthcheck(config.getHealthcheck());
            if (config.getAttachStdin() != null) command.withAttachStdin(config.getAttachStdin());
            if (config.getAttachStdout() != null) command.withAttachStdout(config.getAttachStdout());
            if (config.getAttachStderr() != null) command.withAttachStderr(config.getAttachStderr());
            if (config.getNetworkDisabled() != null) command.withNetworkDisabled(config.getNetworkDisabled());
            if (config.getStdInOnce() != null) command.withStdInOnce(config.getStdInOnce());
            if (config.getStdinOpen() != null) command.withStdinOpen(config.getStdinOpen());
            if (config.getTty() != null) command.withTty(config.getTty());
            if (config.getMacAddress() != null) command.withMacAddress(config.getMacAddress());
            List<Volume> volumes = config.getVolumes() == null ? List.of() : config.getVolumes().keySet().stream()
                    .map(Volume::new).toList();
            if (!volumes.isEmpty()) command.withVolumes(volumes);
        }
        applyPrimaryNetwork(command, snapshot);
        return command.exec().getId();
    }

    private HostConfig preserveNamedVolumes(InspectContainerResponse snapshot) {
        HostConfig hostConfig = snapshot.getHostConfig() == null ? HostConfig.newHostConfig() : snapshot.getHostConfig();
        List<Bind> binds = new ArrayList<>();
        if (hostConfig.getBinds() != null) binds.addAll(Arrays.asList(hostConfig.getBinds()));
        Set<String> destinations = new LinkedHashSet<>();
        binds.stream().map(Bind::getVolume).filter(Objects::nonNull).map(Volume::getPath).forEach(destinations::add);
        if (snapshot.getMounts() != null) {
            snapshot.getMounts().stream()
                    .filter(mount -> mount.getName() != null && !mount.getName().isBlank())
                    .filter(mount -> mount.getDestination() != null && destinations.add(mount.getDestination().getPath()))
                    .forEach(mount -> binds.add(new Bind(mount.getName(), mount.getDestination(),
                            AccessMode.fromBoolean(Boolean.TRUE.equals(mount.getRW())))));
        }
        if (!binds.isEmpty()) hostConfig.withBinds(binds);
        return hostConfig;
    }

    private void applyPrimaryNetwork(CreateContainerCmd command, InspectContainerResponse snapshot) {
        if (snapshot.getHostConfig() == null || snapshot.getNetworkSettings() == null
                || snapshot.getNetworkSettings().getNetworks() == null) return;
        String networkMode = snapshot.getHostConfig().getNetworkMode();
        Map.Entry<String, ContainerNetwork> entry = primaryNetwork(networkMode, snapshot.getNetworkSettings().getNetworks());
        if (entry == null) return;
        ContainerNetwork network = entry.getValue();
        List<String> aliases = cleanAliases(network.getAliases(), snapshot.getId());
        if (!aliases.isEmpty()) command.withAliases(aliases);
        if (notBlank(network.getIpAddress())) command.withIpv4Address(network.getIpAddress());
        if (notBlank(network.getGlobalIPv6Address())) command.withIpv6Address(network.getGlobalIPv6Address());
    }

    private void connectAdditionalNetworks(DockerClient docker, String containerId, InspectContainerResponse snapshot) {
        if (snapshot.getHostConfig() == null || snapshot.getNetworkSettings() == null
                || snapshot.getNetworkSettings().getNetworks() == null) return;
        String networkMode = snapshot.getHostConfig().getNetworkMode();
        Map.Entry<String, ContainerNetwork> primary = primaryNetwork(networkMode, snapshot.getNetworkSettings().getNetworks());
        for (Map.Entry<String, ContainerNetwork> entry : snapshot.getNetworkSettings().getNetworks().entrySet()) {
            if (primary != null && primary.getKey().equals(entry.getKey())) continue;
            ContainerNetwork original = entry.getValue();
            ContainerNetwork network = new ContainerNetwork();
            List<String> aliases = cleanAliases(original.getAliases(), snapshot.getId());
            if (!aliases.isEmpty()) network.withAliases(aliases);
            if (notBlank(original.getIpAddress())) network.withIpv4Address(original.getIpAddress());
            if (notBlank(original.getGlobalIPv6Address())) network.withGlobalIPv6Address(original.getGlobalIPv6Address());
            docker.connectToNetworkCmd()
                    .withNetworkId(entry.getKey())
                    .withContainerId(containerId)
                    .withContainerNetwork(network)
                    .exec();
        }
    }

    private Map.Entry<String, ContainerNetwork> primaryNetwork(String mode, Map<String, ContainerNetwork> networks) {
        if (mode == null || networks.isEmpty() || Set.of("host", "none").contains(mode) || mode.startsWith("container:")) {
            return null;
        }
        String wanted = "default".equals(mode) ? "bridge" : mode;
        return networks.entrySet().stream()
                .filter(entry -> entry.getKey().equals(wanted)
                        || entry.getValue().getNetworkID() != null && entry.getValue().getNetworkID().startsWith(wanted))
                .findFirst()
                .orElse(networks.size() == 1 ? networks.entrySet().iterator().next() : null);
    }

    private StagedImage stageImage(DockerConnection connection, String image, String fallbackImageId,
                                   JobState state, IntConsumer progress) {
        DockerClient docker = connection.client();
        ImageReference reference = imageReference(image);
        String taggedBeforePull = inspectImageId(docker, image);
        if (!notBlank(taggedBeforePull)) taggedBeforePull = fallbackImageId;
        try {
            pullImage(connection, image, state, progress);
        } catch (RuntimeException exception) {
            safeguardPulledImage(docker, image, taggedBeforePull, reference, state);
            throw exception;
        }
        String newImageId = inspectImageId(docker, image);
        if (!notBlank(newImageId)) throw new IllegalStateException("拉取完成后无法读取新镜像");
        String hashReference = tagHash(docker, newImageId, reference);
        if (notBlank(taggedBeforePull) && !sameImage(taggedBeforePull, newImageId)) {
            tagOriginal(docker, taggedBeforePull, reference);
            log(state, "info", "原镜像标签保持不变，新镜像暂存为 " + hashReference);
        }
        StagedImage staged = new StagedImage(reference, taggedBeforePull, newImageId, hashReference);
        stagedImages.put(image, staged);
        return staged;
    }

    private void safeguardPulledImage(DockerClient docker, String image, String originalImageId,
                                      ImageReference reference, JobState state) {
        if (!notBlank(originalImageId)) return;
        try {
            String pulledImageId = inspectImageId(docker, image);
            if (!notBlank(pulledImageId) || sameImage(originalImageId, pulledImageId)) return;
            String hashReference = tagHash(docker, pulledImageId, reference);
            tagOriginal(docker, originalImageId, reference);
            stagedImages.put(image, new StagedImage(reference, originalImageId, pulledImageId, hashReference));
            log(state, "warn", "任务中断前已恢复原镜像标签，新镜像保留为 " + hashReference);
        } catch (Exception exception) {
            log(state, "error", "恢复原镜像标签失败: " + safeMessage(exception));
        }
    }

    private String tagHash(DockerClient docker, String imageId, ImageReference reference) {
        String hash = normalizeImageId(imageId);
        docker.tagImageCmd(imageId, reference.repository(), hash).withForce(true).exec();
        return reference.repository() + ':' + hash;
    }

    private void tagOriginal(DockerClient docker, String imageId, ImageReference reference) {
        docker.tagImageCmd(imageId, reference.repository(), reference.tag()).withForce(true).exec();
    }

    private void removeTemporaryTag(DockerClient docker, String reference) {
        if (!notBlank(reference)) return;
        try {
            docker.removeImageCmd(reference).withForce(false).withNoPrune(true).exec();
        } catch (Exception ignored) {
        }
    }

    private ImageReference imageReference(String image) {
        int slash = image.lastIndexOf('/');
        int colon = image.lastIndexOf(':');
        if (colon > slash) return new ImageReference(image.substring(0, colon), image.substring(colon + 1));
        return new ImageReference(image, "latest");
    }

    private void pullImage(DockerConnection connection, String image, JobState state, IntConsumer progress) {
        DockerClient docker = connection.client();
        DockerClientConfig config = connection.config();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        Map<String, long[]> layers = new ConcurrentHashMap<>();
        ResultCallback.Adapter<PullResponseItem> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(PullResponseItem item) {
                if (state.cancelRequested.get()) return;
                if (item.isErrorIndicated()) {
                    String message = item.getError() == null ? "镜像拉取失败" : item.getError();
                    failure.compareAndSet(null, new IllegalStateException(message));
                }
                updateLayerProgress(item, layers, progress);
                String key = Objects.toString(item.getId(), "") + '|' + Objects.toString(item.getStatus(), "");
                if (state.pullLogKeys.add(key) && item.getStatus() != null) {
                    String prefix = item.getId() == null || item.getId().isBlank() ? "" : shortId(item.getId()) + " ";
                    log(state, "info", prefix + item.getStatus());
                }
            }

            @Override
            public void onError(Throwable throwable) {
                failure.set(throwable);
                try {
                    super.onError(throwable);
                } finally {
                    completed.countDown();
                }
            }

            @Override
            public void onComplete() {
                try {
                    super.onComplete();
                } finally {
                    completed.countDown();
                }
            }
        };
        state.activeCallback = callback;
        try {
            var command = docker.pullImageCmd(image);
            AuthConfig auth = config.effectiveAuthConfig(image);
            if (auth != null) command.withAuthConfig(auth);
            command.exec(callback);
            while (!completed.await(400, TimeUnit.MILLISECONDS)) checkCancelled(state);
            checkCancelled(state);
            if (failure.get() != null) throw new IllegalStateException(safeMessage(failure.get()), failure.get());
            progress.accept(100);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new JobCancelledException();
        } finally {
            state.activeCallback = null;
            try {
                callback.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void updateLayerProgress(PullResponseItem item, Map<String, long[]> layers, IntConsumer progress) {
        if (item.getProgressDetail() == null || item.getProgressDetail().getTotal() == null
                || item.getProgressDetail().getCurrent() == null || item.getProgressDetail().getTotal() <= 0) return;
        String id = item.getId() == null ? "layer" : item.getId();
        layers.put(id, new long[]{item.getProgressDetail().getCurrent(), item.getProgressDetail().getTotal()});
        long current = layers.values().stream().mapToLong(value -> value[0]).sum();
        long total = layers.values().stream().mapToLong(value -> value[1]).sum();
        if (total > 0) progress.accept((int) Math.min(99, current * 100 / total));
    }

    private DockerUpdateModels.ContainerInfo containerInfo(DockerClient docker, Container container,
                                                            Map<String, Set<String>> localDigestCache) {
        InspectContainerResponse inspect = inspectContainer(docker, container.getId());
        String image = resolveContainerImage(docker, container, inspect);
        StagedImage staged = stagedImages.get(image);
        String latestId = staged != null ? staged.newImageId()
                : remoteImageDigests.getOrDefault(image, "");
        String reason = updateBlockReason(container, inspect, image);
        boolean checked = checkedImages.contains(image);
        boolean updateAvailable = false;
        if (checked && notBlank(latestId) && !imageCheckErrors.containsKey(image)) {
            updateAvailable = staged != null
                    ? !sameImage(container.getImageId(), latestId)
                    : !localDigestCache.computeIfAbsent(image, key -> inspectLocalDigests(docker, key)).contains(latestId);
        }
        long uptimeSeconds = inspect == null || inspect.getState() == null
                || !Boolean.TRUE.equals(inspect.getState().getRunning())
                ? 0 : uptimeSeconds(inspect.getState().getStartedAt());
        return new DockerUpdateModels.ContainerInfo()
                .setId(container.getId())
                .setName(containerName(container))
                .setImage(image)
                .setImageId(container.getImageId())
                .setLatestImageId(latestId)
                .setState(container.getState())
                .setStatus(container.getStatus())
                .setUptimeSeconds(uptimeSeconds)
                .setUpdateChecked(checked)
                .setUpdateAvailable(updateAvailable)
                .setUpdatable(reason.isBlank())
                .setSelf(isSelf(container))
                .setReason(reason)
                .setCheckError(imageCheckErrors.getOrDefault(image, ""));
    }

    private String inspectRemoteDigest(DockerConnection connection, String image) {
        try {
            return inspectDockerEngineDigest(connection, image);
        } catch (Exception engineException) {
            try {
                return inspectRegistryDigest(connection.config(), image);
            } catch (Exception registryException) {
                throw new IllegalStateException("Docker Engine 查询失败: " + conciseMessage(engineException)
                        + "；直连镜像仓库失败: " + conciseMessage(registryException), registryException);
            }
        }
    }

    private String inspectDockerEngineDigest(DockerConnection connection, String image) {
        DockerClientConfig config = connection.config();
        String path = "/distribution/" + URLEncoder.encode(image, StandardCharsets.UTF_8).replace("+", "%20") + "/json";
        RemoteApiVersion apiVersion = config.getApiVersion();
        if (apiVersion != null && !RemoteApiVersion.UNKNOWN_VERSION.equals(apiVersion)) {
            path = '/' + apiVersion.asWebPathPart() + path;
        }
        DockerHttpClient.Request.Builder request = DockerHttpClient.Request.builder()
                .method(DockerHttpClient.Request.Method.GET)
                .path(path);
        AuthConfig auth = config.effectiveAuthConfig(image);
        if (auth != null) {
            try {
                request.putHeader("X-Registry-Auth", Base64.getUrlEncoder().encodeToString(
                        config.getObjectMapper().writeValueAsBytes(auth)));
            } catch (Exception exception) {
                throw new IllegalStateException("读取镜像仓库认证信息失败: " + safeMessage(exception), exception);
            }
        }
        try (DockerHttpClient.Response response = connection.transport().execute(request.build())) {
            byte[] content = response.getBody().readAllBytes();
            JsonNode body = content.length == 0 ? null : config.getObjectMapper().readTree(content);
            if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
                String message = body == null ? "" : body.path("message").asText("");
                throw new IllegalStateException(notBlank(message)
                        ? message : "Docker 镜像摘要查询失败 (HTTP " + response.getStatusCode() + ')');
            }
            String digest = body == null ? "" : body.path("Descriptor").path("digest").asText("");
            if (!notBlank(digest)) throw new IllegalStateException("远端仓库未返回镜像摘要");
            return digest;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("查询远端镜像摘要失败: " + safeMessage(exception), exception);
        }
    }

    private String inspectRegistryDigest(DockerClientConfig config, String image) {
        RegistryReference reference = registryReference(image);
        URI manifestUri = URI.create("https://" + reference.registry() + "/v2/" + reference.repository()
                + "/manifests/" + encodePathSegment(reference.tag()));
        HttpResponse<byte[]> response = sendManifestRequest(manifestUri, "");
        if (response.statusCode() == 401) {
            String challenge = response.headers().firstValue("WWW-Authenticate").orElse("");
            String token = requestRegistryToken(config, image, reference, challenge);
            response = sendManifestRequest(manifestUri, "Bearer " + token);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + registryError(response.body()));
        }
        String digest = response.headers().firstValue("Docker-Content-Digest").orElse("");
        if (!notBlank(digest)) digest = "sha256:" + sha256(response.body());
        return digest;
    }

    private HttpResponse<byte[]> sendManifestRequest(URI uri, String authorization) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Accept", MANIFEST_ACCEPT)
                .header("User-Agent", "Open-Panel/1.0")
                .GET();
        if (notBlank(authorization)) request.header("Authorization", authorization);
        try {
            return registryHttpClient.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("镜像仓库请求已中断", exception);
        } catch (Exception exception) {
            throw new IllegalStateException(safeMessage(exception), exception);
        }
    }

    private String requestRegistryToken(DockerClientConfig config, String image, RegistryReference reference,
                                        String challenge) {
        if (!challenge.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new IllegalStateException("镜像仓库要求不支持的认证方式");
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        Matcher matcher = AUTH_PARAMETER.matcher(challenge.substring(7));
        while (matcher.find()) parameters.put(matcher.group(1).toLowerCase(Locale.ROOT), matcher.group(2));
        String realm = parameters.getOrDefault("realm", "");
        if (!notBlank(realm)) throw new IllegalStateException("镜像仓库认证响应缺少 realm");
        String scope = parameters.getOrDefault("scope", "repository:" + reference.repository() + ":pull");
        StringBuilder tokenUrl = new StringBuilder(realm)
                .append(realm.contains("?") ? '&' : '?');
        String service = parameters.getOrDefault("service", "");
        if (notBlank(service)) tokenUrl.append("service=").append(encodeQuery(service)).append('&');
        tokenUrl.append("scope=").append(encodeQuery(scope));

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(tokenUrl.toString()))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("User-Agent", "Open-Panel/1.0")
                .GET();
        AuthConfig auth = config.effectiveAuthConfig(image);
        if (auth != null && notBlank(auth.getUsername()) && notBlank(auth.getPassword())) {
            String value = auth.getUsername() + ':' + auth.getPassword();
            request.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString(value.getBytes(StandardCharsets.UTF_8)));
        }
        try {
            HttpResponse<byte[]> response = registryHttpClient.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("认证服务返回 HTTP " + response.statusCode() + registryError(response.body()));
            }
            JsonNode body = config.getObjectMapper().readTree(response.body());
            String token = body == null ? "" : body.path("token").asText("");
            if (!notBlank(token) && body != null) token = body.path("access_token").asText("");
            if (!notBlank(token)) throw new IllegalStateException("认证服务未返回访问令牌");
            return token;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("镜像仓库认证已中断", exception);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(safeMessage(exception), exception);
        }
    }

    private RegistryReference registryReference(String image) {
        ImageReference tagged = imageReference(image);
        String repository = tagged.repository();
        int slash = repository.indexOf('/');
        String first = slash < 0 ? repository : repository.substring(0, slash);
        boolean explicitRegistry = first.contains(".") || first.contains(":") || "localhost".equals(first);
        if (!explicitRegistry) {
            if (slash < 0) repository = "library/" + repository;
            return new RegistryReference("registry-1.docker.io", repository, tagged.tag());
        }
        String registry = first;
        repository = slash < 0 ? "" : repository.substring(slash + 1);
        if (Set.of("docker.io", "index.docker.io", "registry-1.docker.io").contains(registry)) {
            registry = "registry-1.docker.io";
            if (!repository.contains("/")) repository = "library/" + repository;
        }
        if (!notBlank(repository)) throw new IllegalArgumentException("镜像名称缺少仓库路径");
        return new RegistryReference(registry, repository, tagged.tag());
    }

    private String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String encodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String registryError(byte[] body) {
        if (body == null || body.length == 0) return "";
        String value = new String(body, StandardCharsets.UTF_8).replaceAll("\\s+", " ").strip();
        if (value.isEmpty()) return "";
        return ": " + value.substring(0, Math.min(value.length(), 180));
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算镜像摘要", exception);
        }
    }

    private String conciseMessage(Throwable exception) {
        String message = safeMessage(exception).replaceAll("\\s+", " ");
        return message.substring(0, Math.min(message.length(), 180));
    }

    private Set<String> inspectLocalDigests(DockerClient docker, String image) {
        try {
            InspectImageResponse response = docker.inspectImageCmd(image).exec();
            if (response.getRepoDigests() == null) return Set.of();
            return response.getRepoDigests().stream()
                    .filter(this::notBlank)
                    .map(value -> value.substring(value.lastIndexOf('@') + 1))
                    .filter(value -> value.startsWith("sha256:"))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        } catch (Exception exception) {
            return Set.of();
        }
    }

    private InspectContainerResponse inspectContainer(DockerClient docker, String containerId) {
        try {
            return docker.inspectContainerCmd(containerId).exec();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String resolveContainerImage(DockerClient docker, Container container, InspectContainerResponse inspect) {
        String configured = inspect == null || inspect.getConfig() == null
                ? "" : Objects.toString(inspect.getConfig().getImage(), "");
        if (configured.contains("@sha256:") || usableImageName(configured)) return configured;

        String listed = Objects.toString(container.getImage(), "");
        if (listed.contains("@sha256:") || usableImageName(listed)) return listed;

        String imageId = inspect != null && notBlank(inspect.getImageId())
                ? inspect.getImageId() : container.getImageId();
        String stagedName = stagedImages.entrySet().stream()
                .filter(entry -> sameImage(entry.getValue().newImageId(), imageId))
                .map(Map.Entry::getKey)
                .filter(this::usableImageName)
                .findFirst()
                .orElse("");
        if (notBlank(stagedName)) return stagedName;

        if (inspect != null && inspect.getConfig() != null && inspect.getConfig().getLabels() != null) {
            String sourceImage = inspect.getConfig().getLabels().get(SOURCE_IMAGE_LABEL);
            if (usableImageName(sourceImage)) return sourceImage;
        }

        try {
            List<String> tags = docker.inspectImageCmd(imageId).exec().getRepoTags();
            if (tags != null) {
                return tags.stream()
                        .filter(this::usableImageName)
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .findFirst()
                        .orElseGet(() -> notBlank(configured) ? configured : listed);
            }
        } catch (Exception ignored) {
        }
        return notBlank(configured) ? configured : listed;
    }

    private boolean usableImageName(String image) {
        return pullableImage(image) && !hasHashTag(image);
    }

    private boolean hasHashTag(String image) {
        if (!notBlank(image)) return false;
        int slash = image.lastIndexOf('/');
        int colon = image.lastIndexOf(':');
        return colon > slash && image.substring(colon + 1).matches("(?i)[a-f0-9]{64}");
    }

    private String updateBlockReason(Container container, InspectContainerResponse inspect, String image) {
        if (isSelf(container)) return "Open Panel 自身容器请通过 Docker Compose 更新";
        if (!pullableImage(image)) return "镜像使用固定 ID 或 Digest，无法检测更新";
        Map<String, String> labels = inspect != null && inspect.getConfig() != null
                ? inspect.getConfig().getLabels() : container.getLabels();
        if (labels != null && labels.containsKey("com.docker.swarm.service.id")) return "容器由 Docker Swarm 管理";
        if (inspect != null && inspect.getHostConfig() != null
                && Boolean.TRUE.equals(inspect.getHostConfig().getAutoRemove())) return "自动删除容器不支持更新";
        return "";
    }

    private boolean pullableImage(String image) {
        if (image == null || image.isBlank() || image.contains("<none>")) return false;
        String value = image.toLowerCase(Locale.ROOT);
        return !value.startsWith("sha256:") && !value.matches("[a-f0-9]{64}") && !value.contains("@sha256:");
    }

    private boolean isSelf(Container container) {
        String hostname = System.getenv("HOSTNAME");
        if (hostname == null || hostname.isBlank()) return false;
        String normalized = hostname.strip().toLowerCase(Locale.ROOT);
        if (container.getId() != null && container.getId().toLowerCase(Locale.ROOT).startsWith(normalized)) return true;
        if (container.getNames() == null) return false;
        return Arrays.stream(container.getNames()).map(this::cleanName)
                .anyMatch(name -> name.equalsIgnoreCase(normalized));
    }

    private boolean isOpenPanelContainer(Container container) {
        if (isSelf(container)) return true;
        String image = Objects.toString(container.getImage(), "");
        String repository = imageReference(image).repository().toLowerCase(Locale.ROOT);
        return repository.equals("wushuo894/open-panel") || repository.endsWith("/wushuo894/open-panel");
    }

    private Container findContainer(DockerClient docker, String requested) {
        return docker.listContainersCmd().withShowAll(true).exec().stream()
                .filter(container -> container.getId().equals(requested)
                        || container.getId().startsWith(requested)
                        || containerName(container).equals(requested))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到容器"));
    }

    private String inspectImageId(DockerClient docker, String image) {
        try {
            return docker.inspectImageCmd(image).exec().getId();
        } catch (Exception exception) {
            return "";
        }
    }

    private void updateCardReferences(String oldId, String name) {
        repository.update(config -> config.getCards().stream()
                .filter(card -> "docker".equals(card.getType()) && card.getDocker() != null)
                .map(PanelConfig.Card::getDocker)
                .filter(card -> notBlank(card.getContainerId()))
                .filter(card -> oldId.equals(card.getContainerId()) || oldId.startsWith(card.getContainerId()))
                .forEach(card -> card.setContainerId(name)));
    }

    private List<String> cleanAliases(List<String> aliases, String oldId) {
        if (aliases == null) return List.of();
        return aliases.stream()
                .filter(Objects::nonNull)
                .filter(alias -> !alias.equals(oldId) && !oldId.startsWith(alias))
                .distinct()
                .toList();
    }

    private String containerName(Container container) {
        if (container.getNames() == null || container.getNames().length == 0) return shortId(container.getId());
        return cleanName(container.getNames()[0]);
    }

    private String cleanName(String name) {
        return name == null ? "" : name.replaceFirst("^/", "");
    }

    private boolean sameImage(String left, String right) {
        return normalizeImageId(left).equals(normalizeImageId(right));
    }

    private String normalizeImageId(String value) {
        return Objects.toString(value, "").replaceFirst("^sha256:", "");
    }

    private String shortId(String value) {
        String normalized = normalizeImageId(value);
        return normalized.substring(0, Math.min(12, normalized.length()));
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private long uptimeSeconds(String startedAt) {
        if (!notBlank(startedAt)) return 0;
        try {
            return Math.max(0, Duration.between(Instant.parse(startedAt), Instant.now()).getSeconds());
        } catch (DateTimeParseException exception) {
            return 0;
        }
    }

    private void checkCancelled(JobState state) {
        if (state.cancelRequested.get()) throw new JobCancelledException();
    }

    private void complete(JobState state, String message) {
        state.job.setStatus("completed").setPhase("completed").setProgress(100).setCancellable(false);
        log(state, "success", message);
    }

    private void log(JobState state, String level, String message) {
        if (state.job.getLogs().size() >= MAX_LOGS) state.job.getLogs().removeFirst();
        state.job.getLogs().add(new DockerUpdateModels.JobLog()
                .setTimestamp(System.currentTimeMillis())
                .setLevel(level)
                .setMessage(message));
        switch (level) {
            case "error" -> LOGGER.error("Docker task {}: {}", state.job.getId(), message);
            case "warn" -> LOGGER.warn("Docker task {}: {}", state.job.getId(), message);
            default -> LOGGER.info("Docker task {}: {}", state.job.getId(), message);
        }
    }

    private synchronized void assertIdle() {
        DockerUpdateModels.DockerJob active = activeJob();
        if (active != null && !terminal(active.getStatus())) throw new IllegalStateException("已有 Docker 任务正在运行");
    }

    private DockerUpdateModels.DockerJob activeJob() {
        String id = activeJobId.get();
        JobState state = id == null ? null : jobs.get(id);
        return state == null ? null : snapshot(state);
    }

    private boolean terminal(String status) {
        return Set.of("completed", "cancelled", "failed").contains(status);
    }

    private DockerUpdateModels.DockerJob snapshot(JobState state) {
        DockerUpdateModels.DockerJob source = state.job;
        List<DockerUpdateModels.JobLog> logs = source.getLogs().stream()
                .map(log -> new DockerUpdateModels.JobLog()
                        .setTimestamp(log.getTimestamp()).setLevel(log.getLevel()).setMessage(log.getMessage()))
                .toList();
        return new DockerUpdateModels.DockerJob()
                .setId(source.getId()).setType(source.getType()).setStatus(source.getStatus()).setPhase(source.getPhase())
                .setContainerId(source.getContainerId()).setContainerName(source.getContainerName()).setImage(source.getImage())
                .setProgress(source.getProgress()).setCompletedItems(source.getCompletedItems()).setTotalItems(source.getTotalItems())
                .setCancellable(source.isCancellable()).setStartedAt(source.getStartedAt()).setCompletedAt(source.getCompletedAt())
                .setError(source.getError()).setLogs(new ArrayList<>(logs));
    }

    private void trimJobs() {
        if (jobs.size() <= MAX_JOBS) return;
        jobs.values().stream()
                .filter(state -> terminal(state.job.getStatus()))
                .sorted(Comparator.comparingLong(state -> state.job.getStartedAt()))
                .limit(jobs.size() - MAX_JOBS)
                .forEach(state -> jobs.remove(state.job.getId()));
    }

    private DockerConnection connect(Duration responseTimeout) {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        ApacheDockerHttpClient transport = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .connectionTimeout(Duration.ofSeconds(4))
                .responseTimeout(responseTimeout)
                .build();
        return new DockerConnection(config, transport, DockerClientImpl.getInstance(config, transport));
    }

    private String safeMessage(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    @PreDestroy
    void shutdown() {
        jobs.values().forEach(state -> state.cancelRequested.set(true));
        executor.shutdownNow();
    }

    private static final class JobState {
        private final DockerUpdateModels.DockerJob job;
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private final Set<String> pullLogKeys = ConcurrentHashMap.newKeySet();
        private volatile Closeable activeCallback;

        private JobState(DockerUpdateModels.DockerJob job) {
            this.job = job;
        }
    }

    private static final class JobCancelledException extends RuntimeException {
    }

    private record DockerConnection(DockerClientConfig config, ApacheDockerHttpClient transport,
                                    DockerClient client) implements AutoCloseable {
        @Override
        public void close() {
            try {
                client.close();
            } catch (Exception ignored) {
            }
            try {
                transport.close();
            } catch (Exception ignored) {
            }
        }
    }

    private record ImageReference(String repository, String tag) {
        private String namedReference() {
            return repository + ':' + tag;
        }
    }

    private record RegistryReference(String registry, String repository, String tag) {
    }

    private record StagedImage(ImageReference reference, String previousImageId, String newImageId,
                               String hashReference) {
    }
}
