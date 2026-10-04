package open.panel.controller;

import lombok.RequiredArgsConstructor;
import open.panel.auth.AuthService;
import open.panel.entity.PanelConfig;
import open.panel.entity.web.AuthPayloads;
import open.panel.entity.web.DockerUpdateModels;
import open.panel.entity.web.Result;
import open.panel.entity.web.WebScanModels;
import open.panel.service.*;
import open.panel.update.UpdateService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 提供面板配置、账号管理及运维操作等管理员接口。
 *
 * <p>该路径下的请求由安全过滤器统一校验管理员身份。</p>
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {
    private final PanelService panelService;
    private final ConfigBackupService configBackupService;
    private final AuthService authService;
    private final UpdateService updateService;
    private final StatusService statusService;
    private final WebScanService webScanService;
    private final DockerUpdateService dockerUpdateService;

    /**
     * 获取包含敏感管理字段的完整面板配置。
     */
    @GetMapping("/config")
    public Result<PanelConfig> config() {
        return Result.ok(panelService.adminConfig());
    }

    /**
     * 校验并持久化管理员提交的完整面板配置。
     */
    @PutMapping("/config")
    public Result<PanelConfig> save(@RequestBody PanelConfig config) {
        return Result.ok(panelService.saveAdminConfig(config));
    }

    /**
     * 以流式响应导出配置、凭据和上传资源的 ZIP 备份。
     */
    @GetMapping("/config/export")
    public ResponseEntity<StreamingResponseBody> exportConfig() {
        String filename = "open-panel-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".zip";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(configBackupService::exportTo);
    }

    /**
     * 导入 ZIP 备份，并返回导入后的最新配置。
     */
    @PostMapping(value = "/config/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<PanelConfig> importConfig(@RequestPart("file") MultipartFile file) throws Exception {
        configBackupService.importArchive(file);
        return Result.ok(panelService.adminConfig());
    }

    /**
     * 校验当前密码后修改管理员密码。
     */
    @PostMapping("/password")
    public Result<Void> password(@RequestBody AuthPayloads.PasswordChange request) {
        authService.changePassword(request.getCurrentPassword(), request.getNewPassword());
        return Result.ok();
    }

    /**
     * 校验当前密码后修改管理员用户名。
     */
    @PostMapping("/username")
    public Result<Void> username(@RequestBody AuthPayloads.UsernameChange request) {
        authService.changeUsername(request.getCurrentPassword(), request.getNewUsername());
        return Result.ok();
    }

    /**
     * 获取 Docker 容器及其当前运行状态。
     */
    @GetMapping("/docker/containers")
    public Result<List<Map<String, Object>>> containers() {
        return Result.ok(statusService.containers());
    }

    /**
     * 对指定容器执行启动、停止或重启操作。
     */
    @PostMapping("/docker/containers/action")
    public Result<Map<String, Object>> containerAction(@RequestBody Map<String, String> request) {
        return Result.ok(statusService.containerAction(request.get("containerId"), request.get("action")));
    }

    /**
     * 获取指定容器对应的 Compose 配置。
     */
    @GetMapping("/docker/containers/{id}/compose")
    public Result<DockerUpdateModels.ComposeView> dockerCompose(
            @org.springframework.web.bind.annotation.PathVariable String id) {
        return Result.ok(dockerUpdateService.compose(id));
    }

    /**
     * 删除选定的未使用镜像；请求体为空时清理全部候选镜像。
     */
    @org.springframework.web.bind.annotation.DeleteMapping("/docker/images/unused")
    public Result<DockerUpdateModels.ImageCleanupResult> cleanupDockerImages(
            @RequestBody(required = false) DockerUpdateModels.ImageCleanupRequest request) {
        return Result.ok(request == null
                ? dockerUpdateService.cleanupUnusedImages()
                : dockerUpdateService.cleanupUnusedImages(request.getImageIds()));
    }

    /**
     * 预览可安全清理的未使用镜像和冗余标签。
     */
    @GetMapping("/docker/images/unused")
    public Result<DockerUpdateModels.ImageCleanupPreview> previewDockerImageCleanup() {
        return Result.ok(dockerUpdateService.previewUnusedImages());
    }

    /**
     * 获取 Docker 可用性、容器和更新任务概览。
     */
    @GetMapping("/docker")
    public Result<DockerUpdateModels.DockerOverview> docker() {
        return Result.ok(dockerUpdateService.overview());
    }

    /**
     * 启动一次异步容器镜像更新检查。
     */
    @PostMapping("/docker/check")
    public Result<DockerUpdateModels.DockerJob> checkDockerUpdates() {
        return Result.ok(dockerUpdateService.startCheck());
    }

    /**
     * 为指定容器启动异步更新任务。
     */
    @PostMapping("/docker/update")
    public Result<DockerUpdateModels.DockerJob> updateDockerContainer(
            @RequestBody DockerUpdateModels.UpdateRequest request) {
        return Result.ok(dockerUpdateService.startUpdate(request.getContainerId()));
    }

    /**
     * 查询 Docker 后台任务的状态、进度和日志。
     */
    @GetMapping("/docker/jobs/{id}")
    public Result<DockerUpdateModels.DockerJob> dockerJob(
            @org.springframework.web.bind.annotation.PathVariable String id) {
        return Result.ok(dockerUpdateService.job(id));
    }

    /**
     * 请求取消仍在运行的 Docker 后台任务。
     */
    @org.springframework.web.bind.annotation.DeleteMapping("/docker/jobs/{id}")
    public Result<Void> cancelDockerJob(@org.springframework.web.bind.annotation.PathVariable String id) {
        dockerUpdateService.cancel(id);
        return Result.ok();
    }

    /**
     * 启动局域网 Web 服务扫描任务。
     */
    @PostMapping("/scan")
    public Result<WebScanModels.ScanJob> startScan(@RequestBody WebScanModels.ScanRequest request) {
        return Result.ok(webScanService.start(request));
    }

    /**
     * 抓取链接元数据，用于补全卡片名称、描述和图标。
     */
    @PostMapping("/link-metadata")
    public Result<WebScanModels.Candidate> linkMetadata(@RequestBody WebScanModels.LinkMetadataRequest request) {
        return Result.ok(webScanService.lookup(request.getUrl()));
    }

    /**
     * 查询扫描任务的进度及已发现服务。
     */
    @GetMapping("/scan/{id}")
    public Result<WebScanModels.ScanJob> scan(@org.springframework.web.bind.annotation.PathVariable String id) {
        return Result.ok(webScanService.get(id));
    }

    /**
     * 请求取消仍在运行的扫描任务。
     */
    @org.springframework.web.bind.annotation.DeleteMapping("/scan/{id}")
    public Result<Void> cancelScan(@org.springframework.web.bind.annotation.PathVariable String id) {
        webScanService.cancel(id);
        return Result.ok();
    }

    /**
     * 检查远端是否存在可安装的新版本。
     */
    @GetMapping("/update")
    public Result<Map<String, Object>> update() {
        return Result.ok(updateService.check());
    }

    /**
     * 获取当前运行版本。
     */
    @GetMapping("/version")
    public Result<Map<String, Object>> version() {
        return Result.ok(updateService.current());
    }

    /**
     * 安装已检查到的新版本并触发应用重启。
     */
    @PostMapping("/update/install")
    public Result<Map<String, Object>> installUpdate() {
        return Result.ok(updateService.install());
    }
}
