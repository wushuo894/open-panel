package open.panel.service;

import open.panel.entity.PanelConfig;
import open.panel.repository.JsonConfigRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Service
public class ConfigBackupService {
    private static final String CONFIG_ENTRY = "open-panel.json";
    private static final String ASSET_PREFIX = "assets/uploads/";
    private static final long MAX_ARCHIVE_SIZE = 200L * 1024 * 1024;
    private static final long MAX_CONFIG_SIZE = 5L * 1024 * 1024;
    private static final long MAX_ASSET_SIZE = 20L * 1024 * 1024;
    private static final long MAX_EXPANDED_SIZE = 256L * 1024 * 1024;
    private static final int MAX_ENTRIES = 2048;
    private static final Set<String> ASSET_EXTENSIONS = Set.of(
            "avif", "png", "webp", "jpg", "jpeg", "gif", "svg", "ico");

    private final JsonConfigRepository repository;
    private final Path configDirectory;
    private final Path uploadDirectory;

    public ConfigBackupService(JsonConfigRepository repository,
                               @Value("${open-panel.config-dir}") String configDir) throws IOException {
        this.repository = repository;
        this.configDirectory = Path.of(configDir).toAbsolutePath().normalize();
        this.uploadDirectory = configDirectory.resolve("uploads");
        Files.createDirectories(uploadDirectory);
    }

    public void exportTo(OutputStream output) {
        String configJson = repository.exportJson();
        PanelConfig snapshot = repository.parse(configJson);
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            writeEntry(zip, CONFIG_ENTRY, configJson.getBytes(StandardCharsets.UTF_8));
            for (String filename : referencedAssets(snapshot)) {
                Path asset = uploadDirectory.resolve(filename).normalize();
                if (!asset.startsWith(uploadDirectory) || !Files.isRegularFile(asset)) {
                    throw new IllegalStateException("配置引用的资源不存在: " + filename);
                }
                zip.putNextEntry(new ZipEntry(ASSET_PREFIX + filename));
                Files.copy(asset, zip);
                zip.closeEntry();
            }
            zip.finish();
        } catch (IOException exception) {
            throw new IllegalStateException("导出配置备份失败: " + exception.getMessage(), exception);
        }
    }

    public PanelConfig importArchive(MultipartFile file) {
        validateUpload(file);
        Path staging = null;
        try {
            staging = Files.createTempDirectory(configDirectory, "backup-import-");
            ImportedArchive archive = readArchive(file, staging);
            PanelConfig imported;
            try {
                imported = repository.parse(archive.configJson());
            } catch (Exception exception) {
                throw new IllegalArgumentException("ZIP 中的 open-panel.json 无效", exception);
            }
            Set<String> referenced = referencedAssets(imported);
            if (!archive.assets().keySet().containsAll(referenced)) {
                Set<String> missing = new LinkedHashSet<>(referenced);
                missing.removeAll(archive.assets().keySet());
                throw new IllegalArgumentException("ZIP 缺少配置引用的资源: " + missing.iterator().next());
            }
            restoreAssets(archive.assets());
            repository.replace(imported);
            return repository.get();
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (ZipException exception) {
            throw new IllegalArgumentException("导入文件不是有效的 ZIP 压缩包", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("导入配置备份失败: " + exception.getMessage(), exception);
        } finally {
            deleteTree(staging);
        }
    }

    private ImportedArchive readArchive(MultipartFile file, Path staging) throws IOException {
        String configJson = null;
        Map<String, Path> assets = new LinkedHashMap<>();
        long expandedSize = 0;
        int entryCount = 0;
        try (BufferedInputStream buffered = new BufferedInputStream(file.getInputStream())) {
            buffered.mark(4);
            byte[] signature = buffered.readNBytes(4);
            buffered.reset();
            if (!isZipSignature(signature)) {
                throw new IllegalArgumentException("导入文件不是有效的 ZIP 压缩包");
            }
            try (ZipInputStream zip = new ZipInputStream(buffered, StandardCharsets.UTF_8)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (++entryCount > MAX_ENTRIES) throw new IllegalArgumentException("ZIP 文件条目过多");
                    String name = entry.getName();
                    if (entry.isDirectory()) {
                        if (!"assets/".equals(name) && !ASSET_PREFIX.equals(name)) {
                            throw new IllegalArgumentException("ZIP 包含不支持的目录: " + name);
                        }
                    } else if (CONFIG_ENTRY.equals(name)) {
                        if (configJson != null) throw new IllegalArgumentException("ZIP 包含重复的 open-panel.json");
                        byte[] bytes = readEntry(zip, MAX_CONFIG_SIZE, "配置文件超过 5 MB");
                        expandedSize = addExpandedSize(expandedSize, bytes.length);
                        configJson = new String(bytes, StandardCharsets.UTF_8);
                    } else if (name != null && name.startsWith(ASSET_PREFIX)) {
                        String filename = name.substring(ASSET_PREFIX.length());
                        validateAssetFilename(filename);
                        if (assets.containsKey(filename)) throw new IllegalArgumentException("ZIP 包含重复资源: " + filename);
                        byte[] bytes = readEntry(zip, MAX_ASSET_SIZE, "ZIP 中的图片超过 20 MB");
                        expandedSize = addExpandedSize(expandedSize, bytes.length);
                        validateImage(filename);
                        Path target = staging.resolve(filename);
                        Files.write(target, bytes);
                        assets.put(filename, target);
                    } else {
                        throw new IllegalArgumentException("ZIP 包含不支持的文件: " + name);
                    }
                    zip.closeEntry();
                }
            }
        }
        if (configJson == null) throw new IllegalArgumentException("ZIP 缺少 open-panel.json");
        return new ImportedArchive(configJson, assets);
    }

    private void restoreAssets(Map<String, Path> assets) throws IOException {
        for (Map.Entry<String, Path> asset : assets.entrySet()) {
            Path target = uploadDirectory.resolve(asset.getKey()).normalize();
            if (!target.startsWith(uploadDirectory)) throw new IllegalArgumentException("资源路径无效");
            Path temporary = Files.createTempFile(uploadDirectory, "backup-", ".tmp");
            Files.copy(asset.getValue(), temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private Set<String> referencedAssets(PanelConfig config) {
        Set<String> result = new LinkedHashSet<>();
        if (config.getSite() != null) {
            addAsset(result, config.getSite().getIcon());
            addAsset(result, config.getSite().getBackground());
        }
        if (config.getPage() != null && config.getPage().getCover() != null
                && config.getPage().getCover().getWallpapers() != null) {
            config.getPage().getCover().getWallpapers().forEach(value -> addAsset(result, value));
        }
        if (config.getCards() != null) {
            config.getCards().forEach(card -> addAsset(result, card.getIconUrl()));
        }
        return result;
    }

    private void addAsset(Set<String> assets, String value) {
        if (value == null || value.isBlank()) return;
        String normalized = value.strip().replace('\\', '/').replaceFirst("^/", "");
        if (!normalized.startsWith(ASSET_PREFIX)) return;
        String filename = normalized.substring(ASSET_PREFIX.length());
        validateAssetFilename(filename);
        assets.add(filename);
    }

    private byte[] readEntry(InputStream input, long limit, String message) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long size = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            size += read;
            if (size > limit) throw new IllegalArgumentException(message);
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private long addExpandedSize(long current, long added) {
        long total = current + added;
        if (total > MAX_EXPANDED_SIZE) throw new IllegalArgumentException("ZIP 解压后的总大小超过 256 MB");
        return total;
    }

    private void validateUpload(MultipartFile file) {
        String filename = file.getOriginalFilename();
        if (file.isEmpty() || file.getSize() > MAX_ARCHIVE_SIZE) {
            throw new IllegalArgumentException("ZIP 备份为空或超过 200 MB");
        }
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new IllegalArgumentException("仅支持导入 .zip 配置备份");
        }
    }

    private void validateAssetFilename(String filename) {
        if (filename == null || filename.isBlank() || filename.length() > 255
                || filename.contains("/") || filename.contains("\\") || filename.contains("..")
                || !filename.matches("[a-zA-Z0-9.-]+")) {
            throw new IllegalArgumentException("ZIP 中的资源文件名无效");
        }
    }

    private void validateImage(String filename) {
        String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!ASSET_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("ZIP 中包含不支持的图片格式: " + filename);
        }
    }

    private boolean isZipSignature(byte[] bytes) {
        return bytes.length == 4 && bytes[0] == 'P' && bytes[1] == 'K'
                && ((bytes[2] == 3 && bytes[3] == 4)
                || (bytes[2] == 5 && bytes[3] == 6)
                || (bytes[2] == 7 && bytes[3] == 8));
    }

    private void writeEntry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private record ImportedArchive(String configJson, Map<String, Path> assets) {
    }
}
