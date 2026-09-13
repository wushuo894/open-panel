package ani.rss.auth;

import ani.rss.entity.PanelConfig;
import ani.rss.entity.web.Result;
import ani.rss.repository.JsonConfigRepository;
import com.google.gson.Gson;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class SecurityFilter extends OncePerRequestFilter {
    public static final String CLAIMS_ATTRIBUTE = "openPanelClaims";
    private final JsonConfigRepository repository;
    private final ClientIpResolver ipResolver;
    private final AuthService authService;
    private final Gson gson = new Gson();

    public SecurityFilter(JsonConfigRepository repository, ClientIpResolver ipResolver, AuthService authService) {
        this.repository = repository;
        this.ipResolver = ipResolver;
        this.authService = authService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Robots-Tag", "noindex, nofollow, noarchive");
        response.setHeader("X-Open-Panel", "true");
        PanelConfig.Security security = repository.get().getSecurity();
        String path = request.getRequestURI();
        String ip = ipResolver.resolve(request);

        applyCors(request, response, security, path);
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        if (security.isForbidPublicAccess() && !ipResolver.isPrivate(ip) && !"/api/public/health".equals(path)) {
            writeError(response, 403, "已禁止公网访问");
            return;
        }
        boolean authArea = path.startsWith("/api/admin/") || path.startsWith("/api/auth/") && !path.endsWith("/status");
        if (authArea && !security.getIpAllowlist().isEmpty() && !ipResolver.matchesAny(ip, security.getIpAllowlist())) {
            writeError(response, 403, "当前 IP 不在管理白名单中");
            return;
        }

        String authorization = request.getHeader("Authorization");
        String token = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : "";
        JwtService.Claims claims = authService.authenticate(token, ip);
        if (claims != null) request.setAttribute(CLAIMS_ATTRIBUTE, claims);

        boolean publicApiNeedsLogin = path.startsWith("/api/public/")
                && !path.equals("/api/public/health")
                && !security.isAnonymousAccess();
        boolean adminApiNeedsLogin = path.startsWith("/api/admin/");
        if ((publicApiNeedsLogin || adminApiNeedsLogin) && claims == null) {
            writeError(response, 401, "请先登录");
            return;
        }
        chain.doFilter(request, response);
    }

    private void applyCors(HttpServletRequest request, HttpServletResponse response,
                           PanelConfig.Security security, String path) {
        if (!security.isAllowCors() || !path.startsWith("/api/public/")) return;
        String origin = request.getHeader("Origin");
        if (origin == null) return;
        if (security.getCorsOrigins().contains("*") || security.getCorsOrigins().contains(origin)) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Vary", "Origin");
            response.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type");
            response.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
        }
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(gson.toJson(Result.error(status, message)));
    }
}
