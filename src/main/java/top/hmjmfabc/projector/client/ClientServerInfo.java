package top.hmjmfabc.projector.client;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 【①②⑫】客户端保存的「服务端环境清单」。
 *
 * <p>服务端在玩家登录（以及媒体/字体集合变化）时把它发过来，内容有三块：</p>
 * <ul>
 *   <li><b>media</b>：所有媒体的 {@code SHA-1 → (字节数, 是否视频)}。
 *       这是用户 ② 的核心——<b>服务端先把哈希给客户端，由客户端在本地比对缓存</b>：
 *       命中就直接用本地文件（零出站流量），没命中才向服务端请求下载。</li>
 *   <li><b>fonts</b>：服务端 {@code <存档>/projector/fonts/} 里的字体（ID + 文件名 + SHA-1）。</li>
 *   <li><b>flags</b>：原画上传、后台缓存视频、当日流量是否超限、各类配额、是否管理员。</li>
 * </ul>
 *
 * <p>它只保存「服务端声明有什么」，不保存二进制——二进制仍然走既有的
 * {@code MediaRequest/MediaData} 通道。</p>
 */
public final class ClientServerInfo {

    /** 一条媒体记录。 */
    public record Media(String hash, long size, boolean video) {
    }

    /** 一条服务端字体记录。 */
    public record Font(String id, String fileName, String sha1, long size) {
    }

    private static final Map<String, Media> MEDIA = new LinkedHashMap<>();
    private static final Map<String, Font> FONTS = new LinkedHashMap<>();
    private static CompoundTag flags = new CompoundTag();
    private static boolean received;

    private ClientServerInfo() {
    }

    /** 是否已经收到过服务端的清单（单人存档下也会收到，因为集成服务器同样会发）。 */
    public static boolean received() {
        return received;
    }

    public static Map<String, Media> media() {
        return MEDIA;
    }

    public static Map<String, Font> fonts() {
        return FONTS;
    }

    /** 收到分片。第一片带 fonts+flags，最后一片到达后才算「完整」。 */
    public static void accept(CompoundTag tag, boolean last) {
        if (tag == null) return;
        int part = tag.getInt("part");
        if (part == 0) {
            MEDIA.clear();
        }
        ListTag media = tag.getList("media", Tag.TAG_COMPOUND);
        for (int i = 0; i < media.size(); i++) {
            CompoundTag m = media.getCompound(i);
            String h = m.getString("h");
            if (h == null || h.isEmpty()) continue;
            MEDIA.put(h.toLowerCase(Locale.ROOT), new Media(h, m.getLong("s"), m.getBoolean("v")));
        }
        if (tag.contains("fonts")) {
            FONTS.clear();
            ListTag fonts = tag.getList("fonts", Tag.TAG_COMPOUND);
            for (int i = 0; i < fonts.size(); i++) {
                CompoundTag f = fonts.getCompound(i);
                String id = f.getString("id");
                if (id == null || id.isEmpty()) continue;
                FONTS.put(id, new Font(id, f.getString("n"), f.getString("sha1"), f.getLong("s")));
            }
            // 【rc-76】字体核验日志：逐个比对「服务端哈希 vs 本机哈希」，
            // 结论直接决定这个字体能不能用（见 fontAvailable）。
            logFontVerify();
        }
        if (tag.contains("flags")) {
            flags = tag.getCompound("flags");
        }
        if (last) {
            received = true;
        }
    }

    public static void clear() {
        MEDIA.clear();
        FONTS.clear();
        flags = new CompoundTag();
        received = false;
    }

    // ------------------------------------------------------------------
    // flags 读取
    // ------------------------------------------------------------------

    private static boolean flagBool(String key, boolean def) {
        return flags == null || !flags.contains(key) ? def : flags.getBoolean(key);
    }

    private static long flagLong(String key, long def) {
        return flags == null || !flags.contains(key) ? def : flags.getLong(key);
    }

    /** 服务端是否启用「原画上传」。 */
    public static boolean allowOriginalUpload() {
        return flagBool("allowOriginal", false);
    }

    /** 服务端是否允许客户端后台缓存视频（⑫）。 */
    public static boolean allowBackgroundVideoCache() {
        return flagBool("allowBgVideoCache", false);
    }

    /** 服务端是否要求字体哈希核验（①）。 */
    public static boolean verifyFonts() {
        return flagBool("verifyFonts", true);
    }

    public static boolean unlimited() {
        return flagBool("unlimited", false);
    }

    public static boolean admin() {
        return flagBool("admin", false);
    }

    /** 【rc-77】服务端清单里有没有这份媒体（用来跳过重复上传）。 */
    public static boolean hasMedia(String hash) {
        return hash != null && !hash.isEmpty() && MEDIA.containsKey(hash.toLowerCase(Locale.ROOT));
    }

    /** 【rc-77】服务端清单里这份媒体的大小；没有则返回 -1。 */
    public static long mediaSize(String hash) {
        if (hash == null || hash.isEmpty()) return -1L;
        Media m = MEDIA.get(hash.toLowerCase(Locale.ROOT));
        return m == null ? -1L : m.size();
    }

    /**
     * 【②】服务端是否限制「只有管理员能上传媒体」。
     *
     * <p>默认 false = 放开（普通玩家也能上传，受 {@link #imageLimit()}/{@link #videoLimit()} 限制）。
     * 服务端打开 {@code media.onlyAdminCanUpload} 时这里才是 true，界面上才需要拦人。</p>
     */
    public static boolean onlyAdminCanUpload() {
        return flagBool("onlyAdminUpload", false);
    }

    public static boolean quotaExhausted() {
        return flagBool("quotaExhausted", false);
    }

    public static long dailyLimit() {
        return flagLong("dailyLimit", 0);
    }

    public static long dailyUsed() {
        return flagLong("dailyUsed", 0);
    }

    public static long autoCompressVideoBytes() {
        return flagLong("autoCompressVideo", 32L * 1024 * 1024);
    }

    /** 玩家本次上传适用的图片上限（字节）。 */
    public static long imageLimit() {
        if (unlimited()) return Long.MAX_VALUE;
        return flagLong(admin() ? "adminMaxImage" : "playerMaxImage", 4L * 1024 * 1024);
    }

    /** 玩家本次上传适用的视频上限（字节）。 */
    public static long videoLimit() {
        if (unlimited()) return Long.MAX_VALUE;
        return flagLong(admin() ? "adminMaxVideo" : "playerMaxVideo", 64L * 1024 * 1024);
    }

    /** 硬性阻断阈值；0 表示不阻断。 */
    public static long blockVideoBytes() {
        if (!flagBool("blockVideoOn", true)) return 0;
        return flagLong("blockVideo", 256L * 1024 * 1024);
    }

    // ------------------------------------------------------------------
    // ① 字体可用性
    // ------------------------------------------------------------------

    /**
     * 某个字体 ID 在「本机 + 服务端」两边是否都可用。
     *
     * <p>内置字体（打包在 jar 里）永远可用；自定义字体则要求服务端清单里
     * 也有同 ID 且（若开了核验）本地文件的 SHA-1 与服务端一致。</p>
     */
    public static boolean fontAvailable(String fontId) {
        if (fontId == null || fontId.isEmpty()) return false;
        if (isBuiltin(fontId)) return true;
        if (!received()) return true;      // 还没收到清单（单人存档早期）时不阻拦
        Font f = FONTS.get(fontId);
        if (f == null) {
            // 【rc-76】禁止使用「服务端没有的字体」要留痕：以前是静默 false，玩家只看到字体不生效
            denyFont(fontId, "服务端字体清单里没有这个 ID（服务端未安装该字体）", null, null);
            return false;
        }
        if (!verifyFonts()) return true;
        String local = localFontSha1(fontId);
        // 本地算不出哈希（文件不在/读不到）时，只要两边「文件名一致」就放行：
        // 更严格地拒绝只会让玩家连自己上传过的字体都用不了。
        boolean ok = local == null || local.equalsIgnoreCase(f.sha1());
        if (!ok) {
            denyFont(fontId, "本机字体与服务端同名字体内容不一致（哈希校验不通过）", f.sha1(), local);
        }
        return ok;
    }

    /** 【rc-76】「这个字体不能用」的日志（同一个字体只打一次，避免每帧刷屏）。 */
    private static void denyFont(String fontId, String reason,
                                 @Nullable String serverSha1, @Nullable String localSha1) {
        if (fontId == null || !DENIED_LOGGED.add(fontId)) return;
        top.hmjmfabc.projector.Projector.LOGGER.warn("[Projector][客户端][字体] 禁止使用字体 字体={} 原因={}"
                        + " 服务端sha1={} 本机sha1={}（已改用内置字体显示）",
                fontId, reason, serverSha1 == null ? "（无）" : serverSha1,
                localSha1 == null ? "（无）" : localSha1);
    }

    /** 服务端清单里有没有这个字体（管理员上传判断用）。 */
    public static boolean fontOnServer(String fontId) {
        return fontId != null && FONTS.containsKey(fontId);
    }

    /** 上一次核验日志对应的清单指纹（内容变化才重打）。 */
    private static String loggedFontSignature = null;

    /** 已经就「不可用」打过日志的字体（避免同一个字体反复刷屏）。 */
    private static final java.util.Set<String> DENIED_LOGGED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 【rc-76】把「服务端字体清单 vs 本机字体文件」逐个核验并打日志。
     *
     * <p>输出三态：{@code 一致 ✅}（可用）、{@code 本机缺失 ❌}（这名玩家用不了）、
     * {@code 不一致 ❌}（同名字体但内容不同，核验开着时用不了）。</p>
     */
    private static void logFontVerify() {
        StringBuilder sig = new StringBuilder();
        for (Font f : FONTS.values()) sig.append(f.id()).append('=').append(f.sha1()).append(';');
        String signature = sig.toString();
        if (signature.equals(loggedFontSignature)) return;
        loggedFontSignature = signature;
        if (FONTS.isEmpty()) {
            top.hmjmfabc.projector.Projector.LOGGER.info("[Projector][客户端][字体] 服务端字体核验：服务端没有自定义字体"
                    + "（只能用内置字体；开了核验开关时自定义字体一律不可用）");
            return;
        }
        top.hmjmfabc.projector.Projector.LOGGER.info("[Projector][客户端][字体] 服务端字体核验：共 {} 个（核验开关={}）",
                FONTS.size(), verifyFonts());
        for (Font f : FONTS.values()) {
            String local = localFontSha1(f.id());
            String verdict;
            if (local == null) {
                verdict = "本机缺失 ❌（用不了：请把字体文件放进本机 projector/font 目录）";
            } else if (local.equalsIgnoreCase(f.sha1())) {
                verdict = "一致 ✅（可用）";
            } else {
                verdict = "不一致 ❌（同名字体但内容不同，核验开着时不可用）";
            }
            top.hmjmfabc.projector.Projector.LOGGER.info("[Projector][客户端][字体] 字体={} 文件={} 服务端sha1={} 本机sha1={} 结果={}",
                    f.id(), f.fileName(), f.sha1(), local == null ? "（无）" : local, verdict);
        }
    }

    private static boolean isBuiltin(String fontId) {
        return top.hmjmfabc.projector.common.widget.Fonts.MINECRAFT_AE.equals(fontId)
                || top.hmjmfabc.projector.common.widget.Fonts.CAVIAR_DREAMS.equals(fontId);
    }

    /** 本机字体文件对应的 ID 列表（与服务端的 ID 推导规则一致）。 */
    public static List<String> localFontIds() {
        List<String> out = new ArrayList<>();
        for (String id : top.hmjmfabc.projector.client.font.FontManager.availableIds()) {
            if (!isBuiltin(id)) out.add(id);
        }
        return out;
    }

    /** 本机某个自定义字体的 SHA-1（文件不在时返回 null）。 */
    @Nullable
    public static String localFontSha1(String fontId) {
        try {
            java.nio.file.Path dir = top.hmjmfabc.projector.client.media.LocalMedia.fontDir();
            if (!java.nio.file.Files.isDirectory(dir)) return null;
            // 字体 ID 形如 custom:<名字>，取冒号后的部分作为文件名主体
            String base = fontId;
            int colon = base.indexOf(':');
            if (colon >= 0) base = base.substring(colon + 1);
            try (var stream = java.nio.file.Files.list(dir)) {
                for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) stream::iterator) {
                    String name = p.getFileName().toString();
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (!lower.endsWith(".ttf") && !lower.endsWith(".otf")) continue;
                    int dot = name.lastIndexOf('.');
                    String stem = dot > 0 ? name.substring(0, dot) : name;
                    if (!stem.equalsIgnoreCase(base)) continue;
                    return top.hmjmfabc.projector.server.ServerFonts.sha1Hex(p);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 服务端清单里所有字体 ID（调试/界面提示用）。 */
    public static Set<String> serverFontIds() {
        return new HashSet<>(FONTS.keySet());
    }
}
