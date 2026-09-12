package ani.rss.auth;

import ani.rss.entity.PanelConfig;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class JwtService {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private final Gson gson = new Gson();

    public Token create(PanelConfig.Security security, String ip) {
        long issuedAt = Instant.now().getEpochSecond();
        long expiresAt = security.getTokenValidHours() == 0
                ? 0
                : issuedAt + Math.max(1, security.getTokenValidHours()) * 3600L;
        String id = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", "open-panel");
        payload.put("sub", security.getUsername());
        payload.put("iat", issuedAt);
        if (expiresAt > 0) payload.put("exp", expiresAt);
        payload.put("jti", id);
        payload.put("sid", sessionId);
        payload.put("ver", security.getTokenVersion());
        payload.put("ip", ip);
        String header = encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String body = encode(gson.toJson(payload));
        String unsigned = header + "." + body;
        return new Token(unsigned + "." + sign(unsigned, security.getJwtSecret()), id, sessionId, expiresAt);
    }

    public Claims verify(String token, PanelConfig.Security security, String currentIp) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return null;
            String unsigned = parts[0] + "." + parts[1];
            byte[] actual = DECODER.decode(parts[2]);
            byte[] expected = DECODER.decode(sign(unsigned, security.getJwtSecret()));
            if (!MessageDigest.isEqual(actual, expected)) return null;
            Map<String, Object> payload = gson.fromJson(
                    new String(DECODER.decode(parts[1]), StandardCharsets.UTF_8),
                    new TypeToken<Map<String, Object>>() { }.getType());
            Claims claims = new Claims(
                    string(payload.get("sub")),
                    string(payload.get("jti")),
                    string(payload.get("sid")),
                    string(payload.get("ip")),
                    number(payload.get("ver")),
                    payload.get("exp") instanceof Number expiration ? expiration.longValue() : 0);
            if (!security.getUsername().equals(claims.username())) return null;
            if (claims.expiresAt() > 0 && claims.expiresAt() <= Instant.now().getEpochSecond()) return null;
            if (claims.tokenVersion() != security.getTokenVersion()) return null;
            if (security.isForbidMultipleLogin() && !claims.sessionId().equals(security.getActiveSessionId())) return null;
            if (security.isInvalidateOnIpChange() && !claims.loginIp().equals(currentIp)) return null;
            boolean revoked = security.getRevokedTokens().stream()
                    .anyMatch(item -> sha256(claims.id()).equals(item.getIdHash()));
            return revoked ? null : claims;
        } catch (Exception exception) {
            return null;
        }
    }

    public String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String sign(String value, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return ENCODER.encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("JWT 签名失败", exception);
        }
    }

    private String encode(String value) {
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    public record Token(String value, String id, String sessionId, long expiresAt) { }
    public record Claims(String username, String id, String sessionId, String loginIp, int tokenVersion,
                         long expiresAt) { }
}
