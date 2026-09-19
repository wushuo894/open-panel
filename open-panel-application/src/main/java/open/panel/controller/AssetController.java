package open.panel.controller;

import open.panel.entity.web.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping
public class AssetController {
    private static final long MAX_ICON_SIZE = 2L * 1024 * 1024;
    private static final long MAX_BACKGROUND_SIZE = 20L * 1024 * 1024;
    private static final Set<String> ICON_EXTENSIONS = Set.of("avif", "png", "webp", "jpg", "jpeg", "gif", "svg", "ico");
    private static final Set<String> BACKGROUND_EXTENSIONS = Set.of("avif", "png", "webp", "jpg", "jpeg", "gif");
    private final Path uploadDirectory;

    public AssetController(@Value("${open-panel.config-dir}") String configDir) throws Exception {
        uploadDirectory = Path.of(configDir).toAbsolutePath().normalize().resolve("uploads");
        Files.createDirectories(uploadDirectory);
    }

    @PostMapping(value = "/api/admin/assets/icon", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Map<String, String>> uploadIcon(@RequestPart("file") MultipartFile file) throws Exception {
        if (file.isEmpty() || file.getSize() > MAX_ICON_SIZE) {
            throw new IllegalArgumentException("图标文件为空或超过 2 MB");
        }
        return Result.ok(Map.of("url", store(file, "icon", ICON_EXTENSIONS,
                "图标仅支持 AVIF、PNG、WebP、JPG、JPEG、GIF、SVG 和 ICO")));
    }

    @PostMapping(value = "/api/admin/assets/background", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Map<String, String>> uploadBackground(@RequestPart("file") MultipartFile file) throws Exception {
        if (file.isEmpty() || file.getSize() > MAX_BACKGROUND_SIZE) {
            throw new IllegalArgumentException("背景图片为空或超过 20 MB");
        }
        return Result.ok(Map.of("url", store(file, "background", BACKGROUND_EXTENSIONS,
                "背景图片仅支持 AVIF、PNG、WebP、JPG、JPEG 和 GIF")));
    }

    @GetMapping("/assets/uploads/{filename:[a-zA-Z0-9.-]+}")
    public ResponseEntity<Resource> icon(@PathVariable String filename) {
        Path file = uploadDirectory.resolve(filename).normalize();
        if (!file.startsWith(uploadDirectory) || !Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(mediaType(filename))
                .body(new FileSystemResource(file));
    }

    private String store(MultipartFile file, String prefix, Set<String> allowedExtensions,
                         String unsupportedMessage) throws Exception {
        String extension = extension(file.getOriginalFilename());
        if (!allowedExtensions.contains(extension)) throw new IllegalArgumentException(unsupportedMessage);
        byte[] bytes = file.getBytes();
        String filename = assetFilename(file.getOriginalFilename(), prefix, extension, bytes);
        Path temporary = Files.createTempFile(uploadDirectory, prefix + "-", ".tmp");
        Files.write(temporary, bytes);
        try {
            Files.move(temporary, uploadDirectory.resolve(filename), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temporary, uploadDirectory.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
        }
        return "assets/uploads/" + filename;
    }

    private String extension(String originalFilename) {
        if (originalFilename == null) return "";
        String leaf = originalFilename.replace('\\', '/');
        leaf = leaf.substring(leaf.lastIndexOf('/') + 1);
        int index = leaf.lastIndexOf('.');
        return index < 0 || index == leaf.length() - 1 ? "" : leaf.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private String assetFilename(String originalFilename, String prefix, String extension, byte[] bytes)
            throws Exception {
        String leaf = originalFilename == null ? "" : originalFilename.replace('\\', '/');
        leaf = leaf.substring(leaf.lastIndexOf('/') + 1);
        int extensionIndex = leaf.lastIndexOf('.');
        String baseName = extensionIndex > 0 ? leaf.substring(0, extensionIndex) : leaf;
        String safeName = baseName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (safeName.isBlank()) safeName = "image";
        if (safeName.length() > 48) safeName = safeName.substring(0, 48).replaceAll("-+$", "");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes), 0, 6);
        return prefix + "-" + safeName + "-" + hash + "." + extension;
    }

    private MediaType mediaType(String filename) {
        if (filename.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (filename.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (filename.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        if (filename.endsWith(".avif")) return MediaType.parseMediaType("image/avif");
        if (filename.endsWith(".svg")) return MediaType.parseMediaType("image/svg+xml");
        return MediaType.parseMediaType("image/x-icon");
    }
}
