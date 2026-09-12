package ani.rss.controller;

import ani.rss.auth.AuthService;
import ani.rss.entity.PanelConfig;
import ani.rss.entity.web.AuthPayloads;
import ani.rss.entity.web.Result;
import ani.rss.entity.web.WebScanModels;
import ani.rss.repository.JsonConfigRepository;
import ani.rss.service.PanelService;
import ani.rss.service.StatusService;
import ani.rss.service.WebScanService;
import ani.rss.update.UpdateService;
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

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final PanelService panelService;
    private final JsonConfigRepository repository;
    private final AuthService authService;
    private final UpdateService updateService;
    private final StatusService statusService;
    private final WebScanService webScanService;

    public AdminController(PanelService panelService, JsonConfigRepository repository,
                           AuthService authService, UpdateService updateService, StatusService statusService,
                           WebScanService webScanService) {
        this.panelService = panelService;
        this.repository = repository;
        this.authService = authService;
        this.updateService = updateService;
        this.statusService = statusService;
        this.webScanService = webScanService;
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
    public ResponseEntity<byte[]> exportConfig() {
        String filename = "open-panel-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(repository.exportJson().getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping(value = "/config/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<PanelConfig> importConfig(@RequestPart("file") MultipartFile file) throws Exception {
        if (file.isEmpty() || file.getSize() > 5 * 1024 * 1024) {
            throw new IllegalArgumentException("配置文件为空或超过 5 MB");
        }
        PanelConfig config = repository.parse(new String(file.getBytes(), StandardCharsets.UTF_8));
        repository.replace(config);
        return Result.ok(panelService.adminConfig());
    }

    @PostMapping("/password")
    public Result<Void> password(@RequestBody AuthPayloads.PasswordChange request) {
        authService.changePassword(request.getCurrentPassword(), request.getNewPassword());
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
