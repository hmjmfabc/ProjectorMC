package top.hmjmfabc.projector.server;

import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;
import top.hmjmfabc.projector.Projector;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 【①】服务端的字体仓库：与客户端 {@code .minecraft/projector/fonts/} 对应的那一半。
 *
 * <p>目录：{@code <存档>/projector/fonts/}。</p>
 *
 * <p>职责有三：</p>
 * <ol>
 *   <li><b>列出</b>服务端已安装的字体，并算出每个文件的 SHA-1；</li>
 *   <li><b>落盘</b>管理员上传的字体；</li>
 *   <li><b>落盘后重新核验哈希</b>——把刚写下去的文件重新读一遍再算一次摘要，
 *       与客户端上报的哈希比对。两者不一致就说明文件在传输/写入过程中损坏了，
 *       此时把文件删掉并拒绝，绝不能把一个坏字体留在服务端（那会让所有人打不出字）。</li>
 * </ol>
 *
 * <p>字体 ID 的推导规则必须与客户端 {@code FontManager.refreshCustom()} 完全一致，
 * 否则「服务端有、客户端却说没有」。两边都用 {@code Fonts.custom(去掉扩展名的文件名)}。</p>
 */
public final class ServerFonts {

    private ServerFonts() {
    }

    /** 一条服务端字体记录。 */
    public record Info(String fontId, String fileName, String sha1, long size) {
    }

    public static Path fontDir(MinecraftServer server) {
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("projector").resolve("fonts");
    }

    /** 去掉扩展名的文件名（与客户端 {@code FontManager} 的 {@code stripExt} 保持一致）。 */
    public static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * 扫描结果缓存。
     *
     * <p><b>为什么必须有缓存：</b>{@link #scan} 会把每个字体文件整读进内存算 SHA-1。
     * 而 {@code checkFonts} 在<b>每一次控件新增/修改</b>时都会被调用，一次编辑可能
     * 触发十几次校验——不缓存的话，光哈希几 MB 的字体就能让每次保存都卡一下。</p>
     *
     * <p>TTL 取 5 秒：既能把一次编辑里的重复调用全部吃掉，又能让管理员上传字体后
     * 立刻生效（上传流程本身还会主动清缓存）。</p>
     */
    private static final long CACHE_TTL_MS = 5_000L;
    private static Map<String, Info> cache;
    private static long cacheAt;
    private static Object cacheServer;

    /** 【rc-76】上一次打日志时字体清单的「指纹」（id=sha1;…），避免每次扫描都刷屏。 */
    private static String loggedSignature = null;

    /** 让下一次 scan 重新读盘（上传字体之后调用）。 */
    public static synchronized void invalidate() {
        cache = null;
        cacheAt = 0L;
        cacheServer = null;
    }

    /** 扫描服务端字体目录（5 秒缓存）。 */
    public static synchronized Map<String, Info> scan(MinecraftServer server) {
        long now = System.currentTimeMillis();
        if (cache != null && cacheServer == server && now - cacheAt < CACHE_TTL_MS) {
            return cache;
        }
        Map<String, Info> fresh = scanUncached(server);
        cache = fresh;
        cacheAt = now;
        cacheServer = server;
        logFontsOnce(fresh);
        return fresh;
    }

    /**
     * 【rc-76】字体核验日志：把服务端**实际持有**的字体与它们的 SHA-1 打出来。
     *
     * <p>只在清单内容变化时打（用「id=sha1」拼出的指纹比对），所以不会因为 5 秒 TTL
     * 反复扫描而刷屏。玩家报「字体用不了」时，这一组就是服务端侧的基准。</p>
     */
    private static void logFontsOnce(Map<String, Info> fonts) {
        StringBuilder sig = new StringBuilder();
        for (Map.Entry<String, Info> e : fonts.entrySet()) {
            sig.append(e.getKey()).append('=').append(e.getValue().sha1()).append(';');
        }
        String signature = sig.toString();
        if (signature.equals(loggedSignature)) return;
        loggedSignature = signature;
        Projector.LOGGER.info("[Projector][服务端][字体] 字体核验：清单共 {} 个（内容变化时才打这一组）",
                fonts.size());
        if (fonts.isEmpty()) {
            Projector.LOGGER.info("[Projector][服务端][字体] 字体目录为空"
                    + "（客户端只能用内置字体；管理员可通过字体上传流程添加）");
            return;
        }
        for (Info info : fonts.values()) {
            Projector.LOGGER.info("[Projector][服务端][字体] 字体={} 文件={} 大小={}字节 sha1={}",
                    info.fontId(), info.fileName(), info.size(), info.sha1());
        }
    }

    private static Map<String, Info> scanUncached(MinecraftServer server) {
        Map<String, Info> out = new LinkedHashMap<>();
        Path dir = fontDir(server);
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String name = p.getFileName().toString();
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                if (!lower.endsWith(".ttf") && !lower.endsWith(".otf")) return;
                String safe = Sanitize.fontFileName(name);
                if (safe == null) {
                    Projector.LOGGER.warn("[Projector] 字体目录里存在非法文件名，已忽略: {}", name);
                    return;
                }
                try {
                    byte[] data = Files.readAllBytes(p);
                    String sha1 = sha1Hex(data);
                    if (sha1 == null) return;
                    String id = top.hmjmfabc.projector.common.widget.Fonts.custom(stripExt(safe));
                    out.put(id, new Info(id, safe, sha1, data.length));
                } catch (IOException ex) {
                    Projector.LOGGER.warn("[Projector] 读取服务端字体失败 {}: {}", name, ex.toString());
                }
            });
        } catch (IOException ex) {
            Projector.LOGGER.warn("[Projector] 扫描服务端字体目录失败: {}", ex.toString());
        }
        return out;
    }

    /** 服务端是否已安装某个字体 ID。 */
    public static boolean has(MinecraftServer server, String fontId) {
        if (fontId == null || fontId.isEmpty()) return false;
        return scan(server).containsKey(fontId);
    }

    /**
     * 落盘一个上传的字体，并<b>回读重新核验哈希</b>。
     *
     * @param claimedSha1 客户端声称的 SHA-1（可以为空，为空时跳过一致性比对）
     * @return 实际落盘并核验通过的记录；失败返回 null
     */
    @Nullable
    public static Info store(MinecraftServer server, String fontId, String fileName,
                             byte[] data, @Nullable String claimedSha1) {
        if (data == null || data.length == 0) return null;
        if (data.length > top.hmjmfabc.projector.network.Payloads.FontUpload.MAX_FONT_BYTES) return null;
        // 只接受真正的 TTF/OTF：0x00010000（TrueType）、"OTTO"（CFF）、"true"/"ttcf"
        if (!looksLikeFont(data)) {
            Projector.LOGGER.warn("[Projector] 上传的字体魔数不合法，已拒绝（{} 字节）", data.length);
            return null;
        }
        String safeName = Sanitize.fontFileName(fileName);
        if (safeName == null) {
            Projector.LOGGER.warn("[Projector] 上传的字体文件名非法，已拒绝: {}", fileName);
            return null;
        }
        Path dir = fontDir(server);
        Path target;
        try {
            Files.createDirectories(dir);
            target = Sanitize.resolveInside(dir, safeName);
        } catch (IOException ex) {
            Projector.LOGGER.error("[Projector] 创建字体目录失败", ex);
            return null;
        }
        if (target == null) {
            Projector.LOGGER.warn("[Projector] 字体路径越界，已拒绝: {}", safeName);
            return null;
        }
        String expected = sha1Hex(data);
        if (expected == null) return null;
        // 客户端报了哈希就当场比对（防止「内容与哈希不符」的伪造上传）
        if (claimedSha1 != null && !claimedSha1.isEmpty()
                && !expected.equalsIgnoreCase(claimedSha1)) {
            Projector.LOGGER.warn("[Projector] 字体上传哈希不匹配：声明 {} 实际 {}", claimedSha1, expected);
            return null;
        }
        Path tmp = dir.resolve(safeName + ".part");
        try {
            Files.write(tmp, data);
            try {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            // ---- 重新核验：把刚写下去的文件重新读一遍再算一次哈希 ----
            byte[] verify = Files.readAllBytes(target);
            String actual = sha1Hex(verify);
            if (actual == null || !actual.equalsIgnoreCase(expected) || verify.length != data.length) {
                Projector.LOGGER.error("[Projector] 字体落盘后哈希核验失败（期望 {} 实际 {}，{} vs {} 字节），已删除",
                        expected, actual, data.length, verify.length);
                try {
                    Files.deleteIfExists(target);
                } catch (IOException ignored) {
                }
                return null;
            }
            String id = top.hmjmfabc.projector.common.widget.Fonts.custom(stripExt(safeName));
            Projector.LOGGER.info("[Projector] 字体已保存并核验通过: {} -> {} ({} 字节, sha1={})",
                    fontId == null || fontId.isEmpty() ? id : fontId, target, verify.length, actual);
            invalidate();   // 字体集合变了：下一次 scan 必须重新读盘
            return new Info(id, safeName, actual, verify.length);
        } catch (IOException ex) {
            Projector.LOGGER.error("[Projector] 写入字体失败", ex);
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            return null;
        }
    }

    /** TTF / OTF 的魔数判定。 */
    public static boolean looksLikeFont(byte[] d) {
        if (d == null || d.length < 12) return false;
        int tag = ((d[0] & 0xFF) << 24) | ((d[1] & 0xFF) << 16) | ((d[2] & 0xFF) << 8) | (d[3] & 0xFF);
        return tag == 0x00010000            // TrueType
                || tag == 0x4F54544F        // 'OTTO' (CFF)
                || tag == 0x74727565        // 'true'
                || tag == 0x74746366;       // 'ttcf' (字体集合)
    }

    /** 计算 SHA-1（十六进制小写）；失败返回 null。 */
    @Nullable
    public static String sha1Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception ex) {
            return null;
        }
    }

    /** 流式算文件的 SHA-1（大文件不必整体读进内存）。 */
    @Nullable
    public static String sha1Hex(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception ex) {
            return null;
        }
    }
}
