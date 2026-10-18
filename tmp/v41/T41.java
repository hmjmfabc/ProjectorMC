import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import top.hmjmfabc.projector.common.Plane;
import top.hmjmfabc.projector.common.PlaneBlock;
import top.hmjmfabc.projector.common.game.GameDifficulty;
import top.hmjmfabc.projector.common.game.GameKind;
import top.hmjmfabc.projector.common.game.GameSession;
import top.hmjmfabc.projector.common.sequence.SequenceAnim;
import top.hmjmfabc.projector.common.widget.LeaderboardWidget;
import top.hmjmfabc.projector.common.widget.MusicWidget;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** T41 —— 27.2.4-experiment-200：全量 review 后的 medium+ 修复回归。 */
public class T41 {

    static int pass = 0;
    static final List<String> fail = new java.util.ArrayList<>();

    static void chk(boolean ok, String what, String detail) {
        if (ok) { pass++; } else { fail.add(what + "  [" + detail + "]"); }
    }

    static String read(String rel) {
        try {
            return Files.readString(Path.of(rel));
        } catch (Exception e) {
            return "";
        }
    }

    public static void main(String[] a) {
        src("src/main/java/top/hmjmfabc/projector/");

        // ---------- 安全：三条闸门 ----------
        String handler = read("src/main/java/top/hmjmfabc/projector/network/ServerNetHandler.java");
        String perms = read("src/main/java/top/hmjmfabc/projector/server/PlanePermissions.java");
        chk(handler.contains("stripAdminOnlyCommands(w, player)"),
                "★ addWidget 也抹掉非管理员的绑定指令（以前只有 updateWidget 有）", "");
        chk(handler.contains("private static void stripAdminOnlyCommands"),
                "★ 抹除函数存在", "");
        chk(handler.contains("MAX_UPLOAD_ABSOLUTE_BYTES"), "上传总长有绝对上限", "");
        chk(handler.contains("(long) offset + chunk.length"), "★ 越界判断用 long（int 加法会绕回）", "");
        chk(!perms.contains("return !player.server.isDedicatedServer();"),
                "★ isAdmin 不再把「联机房间所有客人」当管理员", "");
        chk(perms.contains("isSingleplayerOwner"), "改用 isSingleplayerOwner 认房主", "");
        chk(perms.contains("canManageFonts"), "字体上传有独立闸门", "");
        chk(handler.contains("PlanePermissions.canManageFonts(player)"), "字体上传走该闸门", "");
        chk(handler.contains("MAX_FONT_FILES"), "字体目录有数量上限", "");

        // ---------- 媒体 ----------
        String cache = read("src/main/java/top/hmjmfabc/projector/client/media/MediaCache.java");
        chk(cache.contains("Math.min(CHUNK_BYTES, total - next)"),
                "★ 续传按实际请求字节数限速（不再用剩余总长）", "");
        chk(cache.contains("Math.min(CHUNK_BYTES, dl.total - next)"), "逐帧续传同理", "");
        chk(cache.contains("if (!video || frame < 0) {\n                LocalMedia.registerCache(hash, cached);"),
                "★ 单帧不按媒体哈希登记", "");
        // paceDelayMs 纯函数：200 KB @ 2 MB/s 应 ≈100 ms，而不是几十秒
        try {
            var m = Class.forName("top.hmjmfabc.projector.client.media.MediaCache")
                    .getDeclaredMethod("paceDelayMs", int.class, long.class);
            m.setAccessible(true);
            long d = (long) m.invoke(null, 200 * 1024, 2L * 1024 * 1024);
            chk(d >= 90 && d <= 110, "200KB @ 2MB/s 的间隔≈100ms", "d=" + d);
        } catch (Throwable t) {
            chk(false, "能调用 paceDelayMs", t.toString());
        }

        // ---------- 排行榜默认字体 ----------
        LeaderboardWidget lb = new LeaderboardWidget();
        chk(!"caviar_dreams".equals(lb.titleFontId) && !"caviar_dreams".equals(lb.nameFontId),
                "★ 排行榜默认字体不再是无中文字形的 Caviar Dreams",
                lb.titleFontId + "/" + lb.nameFontId);

        // ---------- AI：威胁延伸不许取负 / 极限档深度按棋种 ----------
        String ai = read("src/main/java/top/hmjmfabc/projector/common/game/GameAi.java");
        chk(!ai.contains("int ext = -negamax(board, side, 2"),
                "★ 极限档威胁延伸不再对同视角分数取负", "");
        chk(GameDifficulty.maxDepth(GameDifficulty.EXTREME, 2, GameKind.GO) == 3,
                "★ 围棋极限档深度 = 3（以前打不到分支、实际 6 层）",
                "" + GameDifficulty.maxDepth(GameDifficulty.EXTREME, 2, GameKind.GO));
        chk(GameDifficulty.maxDepth(GameDifficulty.EXTREME, 4, GameKind.XIANGQI) == 6,
                "象棋极限档仍为 6 层", "" + GameDifficulty.maxDepth(GameDifficulty.EXTREME, 4, GameKind.XIANGQI));

        // ---------- GameSession.load 校验 ----------
        CompoundTag bad = new CompoundTag();
        bad.putInt("kind", GameKind.GOMOKU);
        bad.putInt("turn", 7);
        bad.putInt("result", 99);
        bad.putIntArray("board", new int[15 * 15]);
        GameSession g = GameSession.load(bad);
        chk(g.turn == GameSession.SIDE_A, "★ 非法 turn 被夹回先手", "turn=" + g.turn);
        chk(g.result == 0, "★ 非法 result 被夹回 0", "result=" + g.result);

        // ---------- Plane.save：待重建标记要持久化 ----------
        Plane p = new Plane();
        p.id = java.util.UUID.randomUUID();
        p.dimension = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        p.anchor = new net.minecraft.core.BlockPos(0, 64, 0);
        p.face = net.minecraft.core.Direction.SOUTH;
        p.needsRebuild = true;
        chk(p.save().getInt("v") == 2, "★ 待重建平面存成 v=2（否则再也修不了）", "v=" + p.save().getInt("v"));
        p.needsRebuild = false;
        chk(p.save().getInt("v") == 3, "正常平面仍存 v=3", "v=" + p.save().getInt("v"));

        // ---------- 音乐播放列表上限 ----------
        MusicWidget mw = new MusicWidget();
        CompoundTag data = new CompoundTag();
        ListTag list = new ListTag();
        for (int i = 0; i < 500; i++) {
            CompoundTag e = new CompoundTag();
            e.putString("key", "k" + i);
            e.putString("title", "x".repeat(400));
            list.add(e);
        }
        data.put("list", list);
        mw.loadExtra(data);
        chk(mw.playlist.size() <= 200, "★ 播放列表条目被夹到 ≤200", "size=" + mw.playlist.size());
        chk(mw.playlist.isEmpty() || mw.playlist.get(mw.playlist.size() - 1)
                .getString("title").length() <= 256, "★ 每条标题被截到 ≤256", "");

        // ---------- 滑动动画方向 ----------
        SequenceAnim.State st = SequenceAnim.inEffect(SequenceAnim.ANIM_SLIDE, 0.0, 0, 100);
        chk(st.dx() < 0, "★ 0°（标注「从左」）起手在左侧（dx<0）", "dx=" + st.dx());

        // ---------- 其它源码级断言 ----------
        String text = read("src/main/java/top/hmjmfabc/projector/common/text/TextLayout.java");
        chk(text.contains("if (wrapWidth > 0 && penX > 0 && penX + adv > wrapWidth)"),
                "★ 缺字兜底也参与换行", "");
        String web = read("src/main/java/top/hmjmfabc/projector/client/render/WebWidgetRenderer.java");
        chk(web.contains("solidRect(collector, ctx, w, 0, 0, w.w, w.h"),
                "★ 网页底色传局部坐标", "");
        chk(!web.contains("w.localRotX()"), "★ 网页状态文字用画布轴心", "");
        String seq = read("src/main/java/top/hmjmfabc/projector/client/gui/SequenceEditorScreen.java");
        chk(seq.contains("lastPlayheadSendMs") && seq.contains("250L"),
                "★ 拖动播放头限流 250ms", "");
        String picker = read("src/main/java/top/hmjmfabc/projector/client/gui/MediaPickerScreen.java");
        chk(!picker.contains("this.height - pad - 120, Math.max(60, bw), 20, b -> {\n                    originalQuality"),
                "★ 原画上传不再与在线视频按钮重叠", "");
        String editor = read("src/main/java/top/hmjmfabc/projector/client/gui/WidgetEditorScreen.java");
        chk(editor.contains("private boolean canEdit()"), "★ 编辑器权限改为每次现算", "");
        chk(editor.contains("sendAction(next ? \"resume\" : \"pause\""), "★ 播放/暂停按钮会翻转并重建", "");
        String audio = read("src/main/java/top/hmjmfabc/projector/client/music/MusicAudioStream.java");
        chk(audio.contains("skipBytesOnPump") && !audio.contains("while (remaining > 0L && !closed) {\n                int want = (int) Math.min(remaining, 1 << 18)"),
                "★ 流内快进不在锁里做网络读", "");
        chk(audio.contains("READ_WAIT_MS = 2L"), "read() 等待压到 2ms", "");
        String mp = read("src/main/java/top/hmjmfabc/projector/client/music/MusicPlayer.java");
        chk(mp.contains("takePendingAudible"), "★ 已出声通知改由客户端线程消费", "");
        chk(mp.contains("state != State.FAILED &&"), "★ stop() 不再抹掉失败原因", "");
        String mm = read("src/main/java/top/hmjmfabc/projector/client/music/MusicManager.java");
        chk(mm.contains("ANCHORS.keySet().retainAll(aliveKeys)"), "★ 音乐缓存一起回收", "");
        chk(mm.contains("if (!notify) {\n            return;\n        }"), "★ seekAudioLocked 的 notify 生效", "");
        String store = read("src/main/java/top/hmjmfabc/projector/server/MediaStore.java");
        chk(handler.contains("IO_EXECUTOR"), "★ 媒体写盘移到专用线程", "");
        String gl = read("src/main/java/top/hmjmfabc/projector/client/font/GlyphAtlas.java");
        chk(gl.contains("public boolean isFull()"), "字形图集暴露 isFull", "");
        String tf = read("src/main/java/top/hmjmfabc/projector/client/font/TtfFont.java");
        chk(tf.contains("reportAtlasFull"), "★ 图集满不再永久拉黑字形", "");
        String online = read("src/main/java/top/hmjmfabc/projector/common/OnlineVideoLink.java");
        chk(online.indexOf("s = normalize(s);") < online.indexOf("if (!acceptable(s))"),
                "★ 先补协议再校验链接", "");
        String renderer = read("src/main/java/top/hmjmfabc/projector/client/render/WidgetRenderer.java");
        chk(!renderer.contains("ctx.depth(), w.x, w.y, w.rotX()"), "天气文字轴心已修", "");
        String wpr = read("src/main/java/top/hmjmfabc/projector/client/render/WorldPlaneRenderer.java");
        chk(wpr.contains("buildDepthIndex("), "★ 贴面深度改成每帧一次索引", "");
        String vo = read("src/main/java/top/hmjmfabc/projector/client/render/VideoOverlayRenderer.java");
        chk(vo.contains("PROXY"), "★ 视频悬浮层不再每帧 new 控件", "");

        System.out.println(fail.isEmpty()
                ? "== ALL PASS ==  " + pass + " passed, 0 failed"
                : "== FAILED ==  " + pass + " passed, " + fail.size() + " failed");
        for (String f : fail) {
            System.out.println("  \u274c " + f);
        }
        if (!fail.isEmpty()) {
            System.exit(1);
        }
    }

    static void src(String base) {
        // 占位：方便以后扩展成逐文件检查
    }
}
