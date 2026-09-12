package ani.rss.service;

import ani.rss.entity.PanelConfig;
import ani.rss.repository.JsonConfigRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;

@Service
public class PanelService {
    private final JsonConfigRepository repository;

    public PanelService(JsonConfigRepository repository) {
        this.repository = repository;
    }

    public PanelConfig publicConfig(String requestedNetwork, boolean clientIsPrivate) {
        PanelConfig config = repository.get();
        String network = normalizeNetwork(requestedNetwork, clientIsPrivate);
        redactSecurity(config);
        config.setUpdate(null);
        config.getGroups().removeIf(group -> !group.isEnabled());
        config.getGroups().sort(Comparator.comparingInt(PanelConfig.Group::getSort));
        config.getCards().removeIf(card -> !card.isEnabled()
                || config.getGroups().stream().noneMatch(group -> group.getId().equals(card.getGroupId())));
        config.getCards().sort(Comparator.comparingInt(PanelConfig.Card::getSort));
        config.getCards().forEach(card -> {
            card.setResolvedUrl(resolveUrl(card, network));
            if (card.getService() != null) {
                card.getService().setToken("").setStatusUrl("");
            }
        });
        config.getSearchEngines().removeIf(engine -> !engine.isEnabled());
        config.getSearchEngines().sort(Comparator.comparingInt(PanelConfig.SearchEngine::getSort));
        return config;
    }

    public PanelConfig adminConfig() {
        PanelConfig config = repository.get();
        redactSecrets(config);
        return config;
    }

    public PanelConfig saveAdminConfig(PanelConfig next) {
        PanelConfig current = repository.get();
        if (next == null) throw new IllegalArgumentException("配置不能为空");
        if (next.getSecurity() == null) next.setSecurity(current.getSecurity());
        next.getSecurity().setUsername(current.getSecurity().getUsername());
        next.getSecurity().setPasswordHash(current.getSecurity().getPasswordHash());
        next.getSecurity().setJwtSecret(current.getSecurity().getJwtSecret());
        next.getSecurity().setTokenVersion(current.getSecurity().getTokenVersion());
        next.getSecurity().setActiveSessionId(current.getSecurity().getActiveSessionId());
        next.getSecurity().setRevokedTokens(current.getSecurity().getRevokedTokens());
        if (next.getUpdate() == null) {
            next.setUpdate(current.getUpdate());
        } else if ("********".equals(next.getUpdate().getGithubToken())) {
            next.getUpdate().setGithubToken(current.getUpdate().getGithubToken());
        }
        next.getCards().forEach(card -> current.getCards().stream()
                .filter(existing -> existing.getId() != null && existing.getId().equals(card.getId()))
                .findFirst().ifPresent(existing -> preserveMaskedServiceSecrets(card, existing)));
        normalizeIds(next);
        repository.replace(next);
        return adminConfig();
    }

    public boolean isInitialized() {
        return repository.get().getSecurity().isInitialized();
    }

    public boolean isAnonymousAccess() {
        return repository.get().getSecurity().isAnonymousAccess();
    }

    private void redactSecurity(PanelConfig config) {
        PanelConfig.Security publicSecurity = new PanelConfig.Security()
                .setInitialized(config.getSecurity().isInitialized())
                .setAnonymousAccess(config.getSecurity().isAnonymousAccess());
        config.setSecurity(publicSecurity);
    }

    private void redactSecrets(PanelConfig config) {
        config.getSecurity().setPasswordHash("").setJwtSecret("").setActiveSessionId("")
                .setRevokedTokens(new ArrayList<>());
        if (config.getUpdate() != null) {
            String githubToken = config.getUpdate().getGithubToken();
            config.getUpdate().setGithubToken(githubToken == null || githubToken.isBlank() ? "" : "********");
        }
        config.getCards().forEach(card -> {
            if (card.getService() != null) {
                card.getService().setToken(card.getService().getToken().isBlank() ? "" : "********");
            }
        });
    }

    private String normalizeNetwork(String network, boolean clientIsPrivate) {
        if ("internal".equals(network) || "external".equals(network)) return network;
        return clientIsPrivate ? "internal" : "external";
    }

    private String resolveUrl(PanelConfig.Card card, String network) {
        String internal = "";
        String external = "";
        switch (card.getType()) {
            case "custom" -> {
                if (card.getCustom() != null) {
                    internal = card.getCustom().getInternalUrl();
                    external = card.getCustom().getExternalUrl();
                }
            }
            case "service" -> {
                if (card.getService() != null) {
                    internal = card.getService().getInternalUrl();
                    external = card.getService().getExternalUrl();
                }
            }
            case "docker" -> {
                if (card.getDocker() != null) {
                    internal = card.getDocker().getInternalUrl();
                    external = card.getDocker().getExternalUrl();
                }
            }
            default -> {
                return "";
            }
        }
        String preferred = "internal".equals(network) ? internal : external;
        return preferred == null || preferred.isBlank() ? ("internal".equals(network) ? external : internal) : preferred;
    }

    private void normalizeIds(PanelConfig config) {
        config.getGroups().forEach(group -> {
            if (group.getId() == null || group.getId().isBlank()) group.setId(UUID.randomUUID().toString());
        });
        config.getCards().forEach(card -> {
            if (card.getId() == null || card.getId().isBlank()) card.setId(UUID.randomUUID().toString());
        });
        config.getSearchEngines().forEach(engine -> {
            if (engine.getId() == null || engine.getId().isBlank()) engine.setId(UUID.randomUUID().toString());
        });
    }

    private void preserveMaskedServiceSecrets(PanelConfig.Card card, PanelConfig.Card existing) {
        if (card.getService() == null || existing.getService() == null) return;
        if ("********".equals(card.getService().getToken())) {
            card.getService().setToken(existing.getService().getToken());
        }
    }
}
