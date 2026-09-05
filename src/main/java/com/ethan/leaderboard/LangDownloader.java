package com.ethan.leaderboard;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * zh_cn.json 自动下载：文件缺失时在独立后台线程从 Mojang 官方源拉取
 * Minecraft 客户端官方中文语言文件，超时或失败回退 BMCLAPI 国内镜像。
 * 下载链：版本清单 -> 版本 JSON -> asset index -> 语言文件本体。
 * 全部请求带连接与读取超时，绝不阻塞服务器启动；
 * 先写临时文件再原子移动，失败不留半个文件。
 */
public final class LangDownloader {
    private static final String MANIFEST_OFFICIAL =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String MANIFEST_MIRROR =
            "https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json";
    private static final String RESOURCES_OFFICIAL = "https://resources.download.minecraft.net/";
    private static final String RESOURCES_MIRROR = "https://bmclapi2.bangbang93.com/assets/";
    private static final String LANG_ASSET_KEY = "minecraft/lang/zh_cn.json";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    /** 下载任务单线程执行器，同一时间最多一个下载在跑 */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "server-leaderboard-lang-download");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private LangDownloader() {
    }

    /** 启动时调用：文件已存在则不动，尊重服主手动放置的文件 */
    public static void downloadIfMissing(MinecraftServer server) {
        if (Files.exists(Lang.langPath())) {
            return;
        }
        start(server, null);
    }

    /** /leaderboard lang update：强制重新下载并覆盖现有文件 */
    public static void forceUpdate(MinecraftServer server, ServerCommandSource feedback) {
        start(server, feedback);
    }

    /** 是否有下载正在进行（指令侧用于提示） */
    public static boolean isRunning() {
        return RUNNING.get();
    }

    private static void start(MinecraftServer server, ServerCommandSource feedback) {
        if (!RUNNING.compareAndSet(false, true)) {
            if (feedback != null) {
                feedback.sendMessage(Text.literal("中文翻译表下载已在进行中").formatted(Formatting.YELLOW));
            }
            return;
        }
        EXECUTOR.submit(() -> {
            try {
                byte[] content = downloadChain();
                writeAtomically(Lang.langPath(), content);
                // 文件 mtime 变化后 reloadIfChanged 会真正重读，Lang 内部线程安全
                Lang.reloadIfChanged();
                int count = Lang.entryCount();
                ServerLeaderboardMod.LOGGER.info("[排行榜] 中文翻译表已自动下载并加载，共 {} 条", count);
                if (feedback != null) {
                    server.execute(() -> feedback.sendMessage(
                            Text.literal("中文翻译表已更新，共 " + count + " 条").formatted(Formatting.GREEN)));
                }
            } catch (Exception e) {
                ServerLeaderboardMod.LOGGER.warn(
                        "[排行榜] 自动下载中文翻译表失败，官方与镜像源均不可用，"
                                + "可手动将客户端语言文件保存为 {}: {}", Lang.langPath(), e.toString());
                if (feedback != null) {
                    server.execute(() -> feedback.sendMessage(
                            Text.literal("中文翻译表下载失败，详情见服务器日志").formatted(Formatting.RED)));
                }
            } finally {
                RUNNING.set(false);
            }
        });
    }

    /** 官方源完整走一遍下载链，失败则镜像源再走一遍 */
    private static byte[] downloadChain() throws IOException {
        try {
            return downloadChain(true);
        } catch (Exception officialFailure) {
            try {
                return downloadChain(false);
            } catch (Exception mirrorFailure) {
                mirrorFailure.addSuppressed(officialFailure);
                if (mirrorFailure instanceof IOException io) {
                    throw io;
                }
                throw new IOException(mirrorFailure);
            }
        }
    }

    /**
     * 单源下载链：清单 -> 版本 JSON -> asset index -> 语言文件。
     * 镜像源把 Mojang 的域名替换为 BMCLAPI 镜像域名。
     */
    private static byte[] downloadChain(boolean official) throws IOException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        String mcVersion = minecraftVersionId();
        if (mcVersion == null) {
            throw new IOException("无法确定当前 Minecraft 版本号");
        }

        // 1. 版本清单
        JsonObject manifest = fetchJson(client, official ? MANIFEST_OFFICIAL : MANIFEST_MIRROR);
        JsonArray versions = manifest.has("versions") && manifest.get("versions").isJsonArray()
                ? manifest.getAsJsonArray("versions") : null;
        if (versions == null) {
            throw new IOException("版本清单格式异常，缺少 versions 数组");
        }
        String versionUrl = null;
        for (JsonElement e : versions) {
            if (e.isJsonObject() && mcVersion.equals(e.getAsJsonObject().get("id").getAsString())) {
                versionUrl = e.getAsJsonObject().get("url").getAsString();
                break;
            }
        }
        if (versionUrl == null) {
            throw new IOException("版本清单中找不到版本 " + mcVersion);
        }

        // 2. 版本 JSON，取 assetIndex
        JsonObject versionJson = fetchJson(client, mirror(versionUrl, official));
        if (!versionJson.has("assetIndex") || !versionJson.get("assetIndex").isJsonObject()) {
            throw new IOException("版本 JSON 缺少 assetIndex");
        }
        JsonObject assetIndex = versionJson.getAsJsonObject("assetIndex");
        String assetUrl = assetIndex.get("url").getAsString();

        // 3. asset index，找语言文件 hash
        JsonObject assets = fetchJson(client, mirror(assetUrl, official));
        if (!assets.has("objects") || !assets.get("objects").isJsonObject()) {
            throw new IOException("asset index 缺少 objects");
        }
        JsonElement langEntry = assets.getAsJsonObject("objects").get(LANG_ASSET_KEY);
        if (langEntry == null || !langEntry.isJsonObject() || !langEntry.getAsJsonObject().has("hash")) {
            throw new IOException("asset index 中找不到 " + LANG_ASSET_KEY);
        }
        String hash = langEntry.getAsJsonObject().get("hash").getAsString();

        // 4. 语言文件本体
        String base = official ? RESOURCES_OFFICIAL : RESOURCES_MIRROR;
        return fetchBytes(client, base + hash.substring(0, 2) + "/" + hash);
    }

    /** 镜像源下把 Mojang 官方域名替换为 BMCLAPI 镜像域名（piston-meta 与 piston-data 同源镜像） */
    private static String mirror(String url, boolean official) {
        if (official) {
            return url;
        }
        return url.replace("piston-meta.mojang.com", "bmclapi2.bangbang93.com")
                .replace("piston-data.mojang.com", "bmclapi2.bangbang93.com");
    }

    /** 从 Fabric 元数据取 MC 版本 id，避免依赖 Yarn 的 GameVersion 方法签名 */
    private static String minecraftVersionId() {
        return FabricLoader.getInstance().getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse(null);
    }

    private static JsonObject fetchJson(HttpClient client, String url) throws IOException {
        String body = new String(fetchBytes(client, url), java.nio.charset.StandardCharsets.UTF_8);
        try {
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            throw new IOException("JSON 解析失败: " + url, e);
        }
    }

    private static byte[] fetchBytes(HttpClient client, String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(READ_TIMEOUT)
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断: " + url, e);
        }
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + ": " + url);
        }
        return response.body();
    }

    /** 先写同目录临时文件再移动，避免部分失败留下半个文件 */
    private static void writeAtomically(Path target, byte[] content) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, content);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 服务器停止时关闭下载线程 */
    public static void shutdown() {
        EXECUTOR.shutdown();
        try {
            if (!EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
