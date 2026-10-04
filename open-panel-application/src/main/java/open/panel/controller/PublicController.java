package open.panel.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import open.panel.auth.ClientIpResolver;
import open.panel.entity.PanelConfig;
import open.panel.entity.web.Result;
import open.panel.service.PanelService;
import open.panel.service.StatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 提供无需管理员认证即可读取的面板展示和状态接口。
 */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicController {
    private final PanelService panelService;
    private final StatusService statusService;
    private final ClientIpResolver ipResolver;

    /**
     * 返回轻量健康状态，供部署平台和更新流程探活。
     */
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        return Result.ok(Map.of("status", "UP"));
    }

    /**
     * 返回过滤后的公开配置，并结合客户端网络位置解析自动访问地址。
     */
    @GetMapping("/panel")
    public Result<PanelConfig> panel(@RequestParam(defaultValue = "auto") String network,
                                     HttpServletRequest request) {
        boolean privateClient = ipResolver.isPrivate(ipResolver.resolve(request));
        return Result.ok(panelService.publicConfig(network, privateClient));
    }

    /**
     * 获取配置中系统类状态项的检测结果。
     */
    @GetMapping("/system-status")
    public Result<Map<String, Object>> systemStatus() {
        return Result.ok(statusService.systemStatuses());
    }

    /**
     * 获取软件服务类状态项的检测结果。
     */
    @GetMapping("/service-status")
    public Result<Map<String, Object>> serviceStatus() {
        return Result.ok(statusService.serviceStatuses());
    }

    /**
     * 获取 Docker 容器卡片的运行状态。
     */
    @GetMapping("/docker-status")
    public Result<Map<String, Object>> dockerStatus() {
        return Result.ok(statusService.dockerStatuses());
    }

}
