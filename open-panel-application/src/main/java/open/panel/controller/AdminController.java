package open.panel.controller;

import open.panel.auth.AuthService;
import open.panel.entity.PanelConfig;
import open.panel.entity.web.AuthPayloads;
import open.panel.entity.web.DockerUpdateModels;
import open.panel.entity.web.Result;
import open.panel.entity.web.WebScanModels;
import open.panel.service.ConfigBackupService;
import open.panel.service.DockerUpdateService;
import open.panel.service.PanelService;
import open.panel.service.StatusService;
import open.panel.service.WebScanService;
import open.panel.update.UpdateService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.List;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final PanelService panelService;
    private final ConfigBackupService configBackupService;
    private final AuthService authService;
    private final UpdateService updateService;
    private final StatusService statusService;
    private final WebScanService webScanService;
    private final DockerUpdateService dockerUpdateService;

    public AdminController(PanelService panelService, ConfigBackupService configBackupService,
                           AuthService authService, UpdateService updateService, StatusService statusService,
                           WebScanService webScanService, DockerUpdateService dockerUpdateService) {
        this.panelService = panelService;
        this.configBackupService = configBackupService;
        this.authService = authService;
        this.updateService = updateService;
        this.statusService = statusService;
        this.webScanService = webScanService;
        this.dockerUpdateService = dockerUpdateService;
    }

    @GetMapping("/config")
    public Result<PanelConfig> config() {
        return Result.ok(panelService.adminConfig());
    }

    @PutMapping("/config")
    public Result<PanelConfig> save(@RequestBody PanelConfig config) {
        return Result.ok(panelService.saveAdminConfig(config));
    }

    @GetMapping("/config/export")
    public ResponseEntity<StreamingResponseBody> exportConfig() {
        String filename = "open-panel-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".zip";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(configBackupService::exportTo);
    }

    @PostMapping(value = "/config/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<PanelConfig> importConfig(@RequestPart("file") MultipartFile file) throws Exception {
        configBackupService.importArchive(file);
        return Result.ok(panelService.adminConfig());
    }

    @PostMapping("/password")
    public Result<Void> password(@RequestBody AuthPayloads.PasswordChange request) {
        authService.changePassword(request.getCurrentPassword(), request.getNewPassword());
        return Result.ok();
    }

    @PostMapping("/username")
    public Result<Void> username(@RequestBody AuthPayloads.UsernameChange request) {
        authService.changeUsername(request.getCurrentPassword(), request.getNewUsername());
        return Result.ok();
    }

    @GetMapping("/docker/containers")
    public Result<List<Map<String, Object>>> containers() {
        return Result.ok(statusService.containers());
    }

    @PostMapping("/docker/containers/action")
    public Result<Map<String, Object>> containerAction(@RequestBody Map<String, String> request) {
        return Result.ok(statusService.containerAction(request.get("containerId"), request.get("action")));
    }

    @GetMapping("/docker/containers/{id}/compose")
    public Result<DockerUpdateModels.ComposeView> dockerCompose(
            @org.springframework.web.bind.annotation.PathVariable String id) {
        return Result.ok(dockerUpdateService.compose(id));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/docker/images/unused")
    public Result<DockerUpdateModels.ImageCleanupResult> cleanupDockerImages(
            @RequestBody(required = false) DockerUpdateModels.ImageCleanupRequest request) {
        return Result.ok(request == null
                ? dockerUpdateService.cleanupUnusedImages()
                : dockerUpdateService.cleanupUnusedImages(request.getImageIds()));
    }

    @GetMapping("/docker/images/unused")
    public Result<DockerUpdateModels.ImageCleanupPreview> previewDockerImageCleanup() {
        return Result.ok(dockerUpdateService.previewUnusedImages());
    }

    @GetMapping("/docker")
    public Result<DockerUpdateModels.DockerOverview> docker() {
        return Result.ok(dockerUpdateService.overview());
    }

    @PostMapping("/docker/check")
    public Result<DockerUpdateModels.DockerJob> checkDockerUpdates() {
        return Result.ok(dockerUpdateService.startCheck());
    }

    @PostMapping("/docker/update")
    public Result<DockerUpdateModels.DockerJob> updateDockerContainer(
            @RequestBody DockerUpdateModels.UpdateRequest request) {
        return Result.ok(dockerUpdateService.startUpdate(request.getContainerId()));
    }

    @GetMapping("/docker/jobs/{id}")
    public Result<DockerUpdateModels.DockerJob> dockerJob(
            @org.springframework.web.bind.annotation.PathVariable String id) {
        return Result.ok(dockerUpdateService.job(id));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/docker/jobs/{id}")
    public Result<Void> cancelDockerJob(@org.springframework.web.bind.annotation.PathVariable String id) {
        dockerUpdateService.cancel(id);
        return Result.ok();
    }

    @PostMapping("/scan")
    public Result<WebScanModels.ScanJob> startScan(@RequestBody WebScanModels.ScanRequest request) {
        return Result.ok(webScanService.start(request));
    }

    @PostMapping("/link-metadata")
    public Result<WebScanModels.Candidate> linkMetadata(@RequestBody WebScanModels.LinkMetadataRequest request) {
        return Result.ok(webScanService.lookup(request.getUrl()));
    }

    @GetMapping("/scan/{id}")
    public Result<WebScanModels.ScanJob> scan(@org.springframework.web.bind.annotation.PathVariable String id) {
        return Result.ok(webScanService.get(id));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/scan/{id}")
    public Result<Void> cancelScan(@org.springframework.web.bind.annotation.PathVariable String id) {
        webScanService.cancel(id);
        return Result.ok();
    }

    @GetMapping("/update")
    public Result<Map<String, Object>> update() {
        return Result.ok(updateService.check());
    }

    @GetMapping("/version")
    public Result<Map<String, Object>> version() {
        return Result.ok(updateService.current());
    }

    @PostMapping("/update/install")
    public Result<Map<String, Object>> installUpdate() {
        return Result.ok(updateService.install());
    }
}
