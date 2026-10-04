package open.panel.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import open.panel.auth.AuthService;
import open.panel.auth.ClientIpResolver;
import open.panel.auth.JwtService;
import open.panel.auth.SecurityFilter;
import open.panel.entity.web.AuthPayloads;
import open.panel.entity.web.Result;
import open.panel.service.PanelService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 提供面板初始化、登录状态查询和会话管理接口。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;
    private final ClientIpResolver ipResolver;
    private final PanelService panelService;

    /**
     * 返回初始化状态、匿名访问策略及当前请求的认证信息。
     */
    @GetMapping("/status")
    public Result<Map<String, Object>> status(HttpServletRequest request) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("initialized", panelService.isInitialized());
        status.put("anonymousAccess", panelService.isAnonymousAccess());
        status.put("authenticated", request.getAttribute(SecurityFilter.CLAIMS_ATTRIBUTE) != null);
        status.put("clientIp", ipResolver.resolve(request));
        return Result.ok(status);
    }

    /**
     * 首次运行时创建管理员账号并签发登录令牌。
     */
    @PostMapping("/setup")
    public Result<AuthService.LoginResult> setup(@RequestBody AuthPayloads.Credentials credentials,
                                                 HttpServletRequest request) {
        return Result.ok(authService.setup(credentials.getUsername(), credentials.getPassword(), ipResolver.resolve(request)));
    }

    /**
     * 校验管理员凭据并签发登录令牌。
     */
    @PostMapping("/login")
    public Result<AuthService.LoginResult> login(@RequestBody AuthPayloads.Credentials credentials,
                                                 HttpServletRequest request) {
        return Result.ok(authService.login(credentials.getUsername(), credentials.getPassword(), ipResolver.resolve(request)));
    }

    /**
     * 撤销当前令牌对应的登录会话。
     */
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        authService.logout((JwtService.Claims) request.getAttribute(SecurityFilter.CLAIMS_ATTRIBUTE));
        return Result.ok();
    }
}
