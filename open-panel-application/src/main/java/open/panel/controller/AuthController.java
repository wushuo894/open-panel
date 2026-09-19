package open.panel.controller;

import open.panel.auth.AuthService;
import open.panel.auth.ClientIpResolver;
import open.panel.auth.JwtService;
import open.panel.auth.SecurityFilter;
import open.panel.entity.web.AuthPayloads;
import open.panel.entity.web.Result;
import open.panel.service.PanelService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    private final ClientIpResolver ipResolver;
    private final PanelService panelService;

    public AuthController(AuthService authService, ClientIpResolver ipResolver, PanelService panelService) {
        this.authService = authService;
        this.ipResolver = ipResolver;
        this.panelService = panelService;
    }

    @GetMapping("/status")
    public Result<Map<String, Object>> status(HttpServletRequest request) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("initialized", panelService.isInitialized());
        status.put("anonymousAccess", panelService.isAnonymousAccess());
        status.put("authenticated", request.getAttribute(SecurityFilter.CLAIMS_ATTRIBUTE) != null);
        status.put("clientIp", ipResolver.resolve(request));
        return Result.ok(status);
    }

    @PostMapping("/setup")
    public Result<AuthService.LoginResult> setup(@RequestBody AuthPayloads.Credentials credentials,
                                                  HttpServletRequest request) {
        return Result.ok(authService.setup(credentials.getUsername(), credentials.getPassword(), ipResolver.resolve(request)));
    }

    @PostMapping("/login")
    public Result<AuthService.LoginResult> login(@RequestBody AuthPayloads.Credentials credentials,
                                                  HttpServletRequest request) {
        return Result.ok(authService.login(credentials.getUsername(), credentials.getPassword(), ipResolver.resolve(request)));
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        authService.logout((JwtService.Claims) request.getAttribute(SecurityFilter.CLAIMS_ATTRIBUTE));
        return Result.ok();
    }
}
