package ani.rss.controller;

import ani.rss.repository.JsonConfigRepository;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class PwaController {
    private final JsonConfigRepository repository;

    public PwaController(JsonConfigRepository repository) {
        this.repository = repository;
    }

    @GetMapping(value = "/manifest.webmanifest", produces = "application/manifest+json")
    public Map<String, Object> manifest() {
        String title = repository.get().getSite().getTitle();
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("name", title);
        manifest.put("short_name", title);
        manifest.put("start_url", "./");
        manifest.put("scope", "./");
        manifest.put("display", "standalone");
        manifest.put("background_color", "#101418");
        manifest.put("theme_color", "#e9ff70");
        manifest.put("icons", List.of(
                Map.of("src", "icons/icon-192.png", "sizes", "192x192", "type", "image/png", "purpose", "any maskable"),
                Map.of("src", "icons/icon-512.png", "sizes", "512x512", "type", "image/png", "purpose", "any maskable"),
                Map.of("src", "icons/icon.svg", "sizes", "any", "type", "image/svg+xml", "purpose", "any maskable")));
        return manifest;
    }
}
