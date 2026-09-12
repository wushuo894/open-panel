package ani.rss.auth;

import ani.rss.entity.PanelConfig;
import ani.rss.repository.JsonConfigRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {
    private final JsonConfigRepository repository;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    public AuthService(JsonConfigRepository repository, JwtService jwtService) {
        this.repository = repository;
        this.jwtService = jwtService;
    }

    public LoginResult setup(String username, String password, String ip) {
        validateCredentials(username, password);
        if (repository.get().getSecurity().isInitialized()) {
            throw new IllegalStateException("管理员账号已经初始化");
        }
        repository.update(config -> {
            config.getSecurity().setUsername(username.trim());
            config.getSecurity().setPasswordHash(passwordEncoder.encode(password));
            config.getSecurity().setInitialized(true);
        });
        return login(username, password, ip);
    }

    public LoginResult login(String username, String password, String ip) {
        PanelConfig.Security security = repository.get().getSecurity();
        if (!security.isInitialized()) throw new IllegalStateException("请先初始化管理员账号");
        Attempts current = attempts.get(ip);
        long now = System.currentTimeMillis();
        if (security.isLimitLoginAttempts() && current != null && current.lockedUntil() > now) {
            long minutes = Math.max(1, (current.lockedUntil() - now + 59_999) / 60_000);
            throw new IllegalStateException("登录尝试过多，请在 " + minutes + " 分钟后重试");
        }
        if (!security.getUsername().equals(username) || !passwordEncoder.matches(password, security.getPasswordHash())) {
            registerFailure(ip, security, now);
            throw new IllegalArgumentException("用户名或密码错误");
        }
        attempts.remove(ip);
        JwtService.Token token = jwtService.create(security, ip);
        if (security.isForbidMultipleLogin()) {
            repository.update(config -> config.getSecurity().setActiveSessionId(token.sessionId()));
        }
        return new LoginResult(token.value(), token.expiresAt(), security.getUsername());
    }

    public JwtService.Claims authenticate(String token, String ip) {
        if (token == null || token.isBlank()) return null;
        return jwtService.verify(token, repository.get().getSecurity(), ip);
    }

    public void logout(JwtService.Claims claims) {
        if (claims == null) return;
        repository.update(config -> {
            long now = Instant.now().getEpochSecond();
            config.getSecurity().getRevokedTokens().removeIf(item -> item.getExpiresAt() > 0 && item.getExpiresAt() <= now);
            PanelConfig.RevokedToken revoked = new PanelConfig.RevokedToken();
            revoked.setIdHash(jwtService.sha256(claims.id()));
            revoked.setExpiresAt(claims.expiresAt());
            config.getSecurity().getRevokedTokens().add(revoked);
        });
    }

    public void changePassword(String currentPassword, String newPassword) {
        PanelConfig.Security security = repository.get().getSecurity();
        if (!passwordEncoder.matches(currentPassword, security.getPasswordHash())) {
            throw new IllegalArgumentException("当前密码不正确");
        }
        validateCredentials(security.getUsername(), newPassword);
        repository.update(config -> {
            config.getSecurity().setPasswordHash(passwordEncoder.encode(newPassword));
            config.getSecurity().setTokenVersion(config.getSecurity().getTokenVersion() + 1);
            config.getSecurity().setActiveSessionId("");
            config.getSecurity().getRevokedTokens().clear();
        });
    }

    public void changeUsername(String currentPassword, String newUsername) {
        PanelConfig.Security security = repository.get().getSecurity();
        if (!passwordEncoder.matches(currentPassword, security.getPasswordHash())) {
            throw new IllegalArgumentException("当前密码不正确");
        }
        validateUsername(newUsername);
        String username = newUsername.trim();
        if (security.getUsername().equals(username)) {
            throw new IllegalArgumentException("新用户名不能与当前用户名相同");
        }
        repository.update(config -> {
            config.getSecurity().setUsername(username);
            config.getSecurity().setTokenVersion(config.getSecurity().getTokenVersion() + 1);
            config.getSecurity().setActiveSessionId("");
            config.getSecurity().getRevokedTokens().clear();
        });
    }

    private void registerFailure(String ip, PanelConfig.Security security, long now) {
        if (!security.isLimitLoginAttempts()) return;
        Attempts next = attempts.compute(ip, (ignored, previous) -> {
            int count = previous == null || previous.lockedUntil() > 0 ? 1 : previous.count() + 1;
            long lockedUntil = count >= Math.max(1, security.getMaxLoginAttempts())
                    ? now + Math.max(1, security.getLoginLockMinutes()) * 60_000L : 0;
            return new Attempts(count, lockedUntil);
        });
        if (next.lockedUntil() > 0) attempts.put(ip, next);
    }

    private void validateCredentials(String username, String password) {
        validateUsername(username);
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw new IllegalArgumentException("密码长度应为 8 到 128 个字符");
        }
    }

    private void validateUsername(String username) {
        if (username == null || username.trim().length() < 3 || username.trim().length() > 64) {
            throw new IllegalArgumentException("用户名长度应为 3 到 64 个字符");
        }
    }

    private record Attempts(int count, long lockedUntil) { }
    public record LoginResult(String token, long expiresAt, String username) { }
}
