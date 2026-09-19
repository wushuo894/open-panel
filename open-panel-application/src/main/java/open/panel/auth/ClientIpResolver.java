package open.panel.auth;

import open.panel.repository.JsonConfigRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

@Component
public class ClientIpResolver {
    private final JsonConfigRepository repository;

    public ClientIpResolver(JsonConfigRepository repository) {
        this.repository = repository;
    }

    public String resolve(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (!matchesAny(remote, repository.get().getSecurity().getTrustedProxyIps())) {
            return remote;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return remote;
        }
        return forwarded.split(",")[0].trim();
    }

    public boolean isPrivate(String ip) {
        try {
            InetAddress address = InetAddress.getByName(ip);
            return address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }

    public boolean matchesAny(String ip, List<String> rules) {
        if (rules == null || rules.isEmpty()) {
            return false;
        }
        return rules.stream().filter(rule -> rule != null && !rule.isBlank()).anyMatch(rule -> matches(ip, rule.trim()));
    }

    private boolean matches(String ip, String rule) {
        if (!rule.contains("/")) {
            return ip.equalsIgnoreCase(rule);
        }
        try {
            String[] parts = rule.split("/", 2);
            byte[] address = InetAddress.getByName(ip).getAddress();
            byte[] network = InetAddress.getByName(parts[0]).getAddress();
            int prefix = Integer.parseInt(parts[1]);
            if (address.length != network.length || prefix < 0 || prefix > address.length * 8) {
                return false;
            }
            for (int i = 0; i < address.length; i++) {
                int bits = Math.min(8, Math.max(0, prefix - i * 8));
                int mask = bits == 0 ? 0 : 0xff << (8 - bits);
                if ((address[i] & mask) != (network[i] & mask)) return false;
            }
            return true;
        } catch (Exception exception) {
            return false;
        }
    }
}
