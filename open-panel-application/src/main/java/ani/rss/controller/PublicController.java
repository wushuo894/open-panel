package ani.rss.controller;

import ani.rss.auth.ClientIpResolver;
import ani.rss.entity.PanelConfig;
import ani.rss.entity.web.Result;
import ani.rss.service.PanelService;
import ani.rss.service.StatusService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/public")
public class PublicController {
    private final PanelService panelService;
    private final StatusService statusService;
    private final ClientIpResolver ipResolver;

    public PublicController(PanelService panelService, StatusService statusService, ClientIpResolver ipResolver) {
        this.panelService = panelService;
        this.statusService = statusService;
        this.ipResolver = ipResolver;
    }

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        return Result.ok(Map.of("status", "UP"));
    }

    @GetMapping("/panel")
    public Result<PanelConfig> panel(@RequestParam(defaultValue = "auto") String network,
                                     HttpServletRequest request) {
        boolean privateClient = ipResolver.isPrivate(ipResolver.resolve(request));
        return Result.ok(panelService.publicConfig(network, privateClient));
    }

    @GetMapping("/card-status")
    public Result<Map<String, Object>> cardStatus() {
        return Result.ok(statusService.statuses());
    }
}
