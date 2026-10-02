import top.hmjmfabc.projector.common.sequence.SequenceAnim;
import top.hmjmfabc.projector.common.sequence.SequenceClip;
import top.hmjmfabc.projector.common.sequence.SequenceTrack;
import top.hmjmfabc.projector.common.text.FormatCodes;
import top.hmjmfabc.projector.common.text.TextLayout;
import top.hmjmfabc.projector.common.widget.ChessWidget;
import top.hmjmfabc.projector.common.widget.MusicWidget;
import top.hmjmfabc.projector.common.widget.TextWidget;
import net.minecraft.nbt.CompoundTag;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * T36 —— 【27.2-pre-136】这一批五件事的纯逻辑套件：
 *
 * <p>①<b>动画种类扩充</b>：13 种入场/出场 + 8 种循环；编号 0~4 必须与旧版一致
 * （老存档不能因为扩表而变成别的动画）；每个种类的求值都要满足
 * 「入场 p=0 是起始态、p=1 完全就位」「出场 p=0 就在位、p=1 完全消失」这两条不变量。</p>
 * <p>②<b>预览与播放同源</b>：{@code SequenceTrack} 必须调用 {@code SequenceAnim} 的那三个
 * 求值函数（源码断言）—— 预览界面里看到的样子就是世界里播放的样子。</p>
 * <p>③<b>棋类视觉</b>：透明底 + 白线 + 白边框；旧存档里写死的「黑底/灰线」要自动迁移。</p>
 * <p>④<b>边框调色</b>：音乐与棋类都要有独立的边框色字段（且旧存档不能因此变样）。</p>
 * <p>⑤<b>文字打字动画</b>：按可见字数取前缀，格式化代码不算字数、也不能被截成半截。</p>
 * <p>外加性能改动的「不许退化」断言：排版缓存 / 颜色前缀记忆化 / 天气每 tick 一次。</p>
 */
public class T36 {

    private static int passed = 0;
    private static final List<String> failed = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("== T36 27.2-pre-136（动画扩充 / 棋类视觉 / 边框调色 / 打字动画 / 性能）==");
        animTable();
        animInvariants();
        loopTable();
        clipBounds();
        sameTruth();
        chessVisual();
        chessMigration();
        musicBorder();
        typewriter();
        formatHelpers();
        perfSources();
        editorSources();

        System.out.println(failed.isEmpty()
                ? "== ALL PASS ==  " + passed + " passed, 0 failed"
                : "== FAILED ==  " + passed + " passed, " + failed.size() + " failed");
        for (String f : failed) {
            System.out.println("  \u274c " + f);
        }
        if (!failed.isEmpty()) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ ① 动画表

    private static void animTable() {
        check("入场/出场动画 13 种", SequenceAnim.ANIM_NAMES.length == 13);
        check("循环动画 8 种", SequenceAnim.LOOP_NAMES.length == 8);
        check("说明文字表与名字表一样长",
                SequenceAnim.ANIM_HINTS.length == SequenceAnim.ANIM_NAMES.length
                        && SequenceAnim.LOOP_HINTS.length == SequenceAnim.LOOP_NAMES.length);

        // ★ 老存档兼容：0~4 的编号与含义一个字都不能变
        check("0=无 / 1=滑动 / 2=渐显渐隐 / 3=放大缩小 / 4=下落 的编号未变",
                SequenceAnim.ANIM_NONE == 0 && SequenceAnim.ANIM_SLIDE == 1
                        && SequenceAnim.ANIM_FADE == 2 && SequenceAnim.ANIM_SCALE == 3
                        && SequenceAnim.ANIM_DROP == 4);
        check("循环 0~3 的编号未变",
                SequenceAnim.LOOP_NONE == 0 && SequenceAnim.LOOP_SWING == 1
                        && SequenceAnim.LOOP_PULSE == 2 && SequenceAnim.LOOP_FLOAT == 3);
        check("新增编号 5~12 各自不同且都在表内",
                SequenceAnim.ANIM_SPIN == 5 && SequenceAnim.ANIM_SHAKE == 12
                        && SequenceAnim.ANIM_SHAKE < SequenceAnim.ANIM_NAMES.length);
        check("出场名字表与入场一样长（编号只有一个字不同）",
                SequenceAnim.OUT_ANIM_NAMES.length == SequenceAnim.ANIM_NAMES.length
                        && SequenceAnim.OUT_ANIM_HINTS.length == SequenceAnim.ANIM_NAMES.length);
        int nameDiff = 0;
        for (int i = 0; i < SequenceAnim.ANIM_NAMES.length; i++) {
            if (!SequenceAnim.ANIM_NAMES[i].equals(SequenceAnim.OUT_ANIM_NAMES[i])) nameDiff++;
        }
        check("入场/出场名字表只在编号 4 上不同，且出场叫「上升」",
                nameDiff == 1 && SequenceAnim.animName(SequenceAnim.ANIM_DROP).equals("下落")
                        && SequenceAnim.outAnimName(SequenceAnim.ANIM_DROP).equals("上升"));
        check("出场说明里写明了「与入场的下落正好相反」",
                SequenceAnim.outAnimHint(SequenceAnim.ANIM_DROP).contains("相反"));
        check("出场取名越界也回落「无」",
                SequenceAnim.outAnimName(-1).equals("无") && SequenceAnim.outAnimName(99).equals("无"));
        check("取名越界回落「无」",
                SequenceAnim.animName(-1).equals(SequenceAnim.ANIM_NAMES[0])
                        && SequenceAnim.animName(99).equals(SequenceAnim.ANIM_NAMES[0]));
        check("说明越界给空串", SequenceAnim.animHint(-5).isEmpty() && SequenceAnim.loopHint(99).isEmpty());
        check("方向参数只给滑动/抖动（编辑器据此决定要不要显示方向按钮）",
                SequenceAnim.usesSlideAngle(SequenceAnim.ANIM_SLIDE)
                        && SequenceAnim.usesSlideAngle(SequenceAnim.ANIM_SHAKE)
                        && !SequenceAnim.usesSlideAngle(SequenceAnim.ANIM_FADE));
    }

    /**
     * 每个动画的两条不变量：入场 p=1 完全就位、出场 p=0 就在位、出场 p=1 消失。
     *
     * <p>【27.2-pre-138】「下落/上升」这对镜像、以及「滑动带渐显渐隐」也在这里被钉住
     * （见下面 {@code mirrored} 与滑动那几条）。</p>
     */
    private static void animInvariants() {
        boolean okIn = true;
        boolean okOut = true;
        boolean okOutEnd = true;
        String bad = "";
        for (int id = 0; id < SequenceAnim.ANIM_NAMES.length; id++) {
            SequenceAnim.State at1 = SequenceAnim.inEffect(id, 1.0, 0, 40);
            if (!at1.visible() || !nearly(at1.dx(), 0) || !nearly(at1.dy(), 0)
                    || !nearly(at1.dDepth(), 0) || !nearly(at1.effScaleX(), 1)
                    || !nearly(at1.effScaleY(), 1) || !nearly(at1.alpha(), 1)
                    || !nearly(at1.rotDeg(), 0)) {
                okIn = false;
                bad = "入场 " + SequenceAnim.animName(id) + " p=1 -> " + describe(at1);
            }
            SequenceAnim.State out0 = SequenceAnim.outEffect(id, 0.0, 0, 40);
            if (!out0.visible() || !nearly(out0.dx(), 0) || !nearly(out0.dy(), 0)
                    || !nearly(out0.effScaleX(), 1) || !nearly(out0.effScaleY(), 1)
                    || !nearly(out0.alpha(), 1) || !nearly(out0.rotDeg(), 0)
                    || !nearly(out0.dDepth(), 0)) {
                okOut = false;
                bad = "出场 " + SequenceAnim.animName(id) + " p=0 -> " + describe(out0);
            }
            SequenceAnim.State out1 = SequenceAnim.outEffect(id, 1.0, 0, 40);
            // p=1 的判据：要么完全透明、要么某个方向的尺寸归零（擦除/翻页/弹出就是这种）
            // 「无」不出场（本来就没有动画，片段结束时直接不画），所以跳过它
            boolean gone = id == SequenceAnim.ANIM_NONE
                    || out1.alpha() < 0.02 || out1.effScaleX() < 0.02 || out1.effScaleY() < 0.02
                    || Math.abs(out1.dDepth()) > 1.0 || Math.abs(out1.dx()) > 1.0
                    || Math.abs(out1.dy()) > 1.0;
            if (!gone) {
                okOutEnd = false;
                bad = "出场 " + SequenceAnim.animName(id) + " p=1 还看得见 -> " + describe(out1);
            }
        }
        check("入场：每个动画 p=1 都完全就位（位移 0 / 缩放 1 / 透明 1 / 旋转 0）", okIn, bad);
        check("出场：每个动画 p=0 都还在原位", okOut, bad);
        check("出场：每个动画 p=1 都看不到了（透明或尺寸归零或已移走）", okOutEnd, bad);

        // 逐个动画的「特征量」断言：这些是最容易被写成反向的地方
        SequenceAnim.State wipe0 = SequenceAnim.inEffect(SequenceAnim.ANIM_WIPE, 0.0, 0, 40);
        SequenceAnim.State wipe7 = SequenceAnim.inEffect(SequenceAnim.ANIM_WIPE, 0.7, 0, 40);
        check("擦除展开：横向从 0 拉开（scaleX 0 → >0），纵向不变",
                nearly(wipe0.effScaleX(), 0) && nearly(wipe0.effScaleY(), 1)
                        && wipe7.effScaleX() > 0 && nearly(wipe7.effScaleY(), 1));
        SequenceAnim.State flip0 = SequenceAnim.inEffect(SequenceAnim.ANIM_FLIP, 0.0, 0, 40);
        check("翻页展开：纵向从 0 拉开（scaleY 0），横向略微变宽（像纸牌翻面）",
                nearly(flip0.effScaleY(), 0) && flip0.effScaleX() > 1.0
                        && nearly(SequenceAnim.inEffect(SequenceAnim.ANIM_FLIP, 1.0, 0, 40).effScaleX(), 1));

        SequenceAnim.State spin0 = SequenceAnim.inEffect(SequenceAnim.ANIM_SPIN, 0.0, 0, 40);
        SequenceAnim.State spin9 = SequenceAnim.inEffect(SequenceAnim.ANIM_SPIN, 0.9, 0, 40);
        check("旋转进入：起始转 360°、越接近就位转角越小、结尾为 0",
                nearly(Math.abs(spin0.rotDeg()), 360) && Math.abs(spin9.rotDeg()) < 40
                        && nearly(SequenceAnim.inEffect(SequenceAnim.ANIM_SPIN, 1.0, 0, 40).rotDeg(), 0));

        SequenceAnim.State b0 = SequenceAnim.inEffect(SequenceAnim.ANIM_BOUNCE, 0.0, 90, 40);
        check("弹跳落下：起始在上方 40 单位（dy=+40，画布 y 向上）、结束归零",
                nearly(b0.dy(), 40) && nearly(SequenceAnim.bounceCurve(1.0), 0));

        SequenceAnim.State pop = SequenceAnim.inEffect(SequenceAnim.ANIM_POP, 0.72, 0, 40);
        check("弹出：中途会超过 1（回弹），不是单调放大", pop.effScaleX() > 1.0);

        SequenceAnim.State fly0 = SequenceAnim.inEffect(SequenceAnim.ANIM_FLY, 0.0, 0, 40);
        check("飞入：从平面**里面**出来（起始 dDepth 为负 = 在平面后方）",
                fly0.dDepth() < -1.0 && nearly(fly0.dDepth(), -SequenceAnim.FLY_BLOCKS));
        SequenceAnim.State drop0 = SequenceAnim.inEffect(SequenceAnim.ANIM_DROP, 0.0, 0, 40);
        check("下落：起始在平面前方 10 格（dDepth = +DROP_BLOCKS，与旧版一致）",
                nearly(drop0.dDepth(), SequenceAnim.DROP_BLOCKS));

        // ---- 【27.2-pre-138】出场「上升」= 入场「下落」的镜像 ----
        boolean mirrored = true;
        for (int i = 0; i <= 10; i++) {
            double p = i / 10.0;
            SequenceAnim.State in = SequenceAnim.inEffect(SequenceAnim.ANIM_DROP, 1 - p, 0, 40);
            SequenceAnim.State out = SequenceAnim.outEffect(SequenceAnim.ANIM_DROP, p, 0, 40);
            // 「入场倒着放 == 出场正着放」：同一个 p 上深度与透明度都逐点相等
            // （smoothstep 关于 (0.5,0.5) 对称 ⇒ ease(1-p) = 1-ease(p)，
            //   所以入场的 alpha=ease(1-p) 正好等于出场的 alpha=1-ease(p)）
            if (!nearly(in.dDepth(), out.dDepth()) || !nearly(in.alpha(), out.alpha())) {
                mirrored = false;
            }
        }
        check("出场「上升」的深度曲线 = 入场「下落」倒着放（逐点相等）", mirrored);
        check("出场「上升」朝**平面前方**走（dDepth 0 → +10），不再钻进墙里",
                nearly(SequenceAnim.outEffect(SequenceAnim.ANIM_DROP, 0.0, 0, 40).dDepth(), 0)
                        && nearly(SequenceAnim.outEffect(SequenceAnim.ANIM_DROP, 1.0, 0, 40).dDepth(),
                        SequenceAnim.DROP_BLOCKS));

        // ---- 【27.2-pre-138】滑动要带渐显/渐隐 ----
        SequenceAnim.State sl0 = SequenceAnim.inEffect(SequenceAnim.ANIM_SLIDE, 0.0, 0, 40);
        SequenceAnim.State sl5 = SequenceAnim.inEffect(SequenceAnim.ANIM_SLIDE, 0.5, 0, 40);
        SequenceAnim.State sl1 = SequenceAnim.inEffect(SequenceAnim.ANIM_SLIDE, 1.0, 0, 40);
        check("入场滑动：一边滑一边渐显（alpha 0 → 0.5 → 1）",
                sl0.alpha() < 1.0e-6 && nearly(sl5.alpha(), 0.5) && nearly(sl1.alpha(), 1));
        SequenceAnim.State so0 = SequenceAnim.outEffect(SequenceAnim.ANIM_SLIDE, 0.0, 0, 40);
        SequenceAnim.State so5 = SequenceAnim.outEffect(SequenceAnim.ANIM_SLIDE, 0.5, 0, 40);
        SequenceAnim.State so1 = SequenceAnim.outEffect(SequenceAnim.ANIM_SLIDE, 1.0, 0, 40);
        check("出场滑动：一边滑走一边渐隐（alpha 1 → 0.5 → 0）",
                nearly(so0.alpha(), 1) && nearly(so5.alpha(), 0.5) && so1.alpha() < 1.0e-6);
        check("滑动仍然在动（渐显不是把位移吃掉了）",
                nearly(sl0.dx(), 40) && nearly(sl0.dy(), 0) && nearly(sl1.dx(), 0));

        SequenceAnim.State rise0 = SequenceAnim.inEffect(SequenceAnim.ANIM_RISE, 0.0, 0, 40);
        check("从下方升起：起始 dy 为负（在下方），方向参数不参与",
                rise0.dy() < -1.0 && nearly(rise0.dy(), -40));
        SequenceAnim.State shk = SequenceAnim.inEffect(SequenceAnim.ANIM_SHAKE, 0.4, 0, 40);
        check("抖动进入：中途有横向偏移（dx ≠ 0）", Math.abs(shk.dx()) > 0.5);

        check("滑动方向被真正使用：0° 沿 +x、90° 沿 +y",
                nearly(SequenceAnim.inEffect(SequenceAnim.ANIM_SLIDE, 0.0, 0, 40).dx(), 40)
                        && nearly(SequenceAnim.inEffect(SequenceAnim.ANIM_SLIDE, 0.0, 90, 40).dy(), 40));
        check("未知编号一律「无」（不抛异常）",
                SequenceAnim.inEffect(999, 0.3, 0, 40).isNormal()
                        && SequenceAnim.outEffect(-3, 0.3, 0, 40).isNormal());
    }

    private static void loopTable() {
        SequenceAnim.State spin = SequenceAnim.loopEffect(SequenceAnim.LOOP_SPIN, Math.PI / 2, 8);
        check("自转：相位 π/2 时转了 90°", nearly(Math.abs(spin.rotDeg()), 90));
        boolean blinkDim = false;
        boolean blinkFull = false;
        for (int i = 0; i <= 20; i++) {
            double phase = Math.PI * 2 * i / 20.0;
            double a = SequenceAnim.loopEffect(SequenceAnim.LOOP_BLINK, phase, 100).alpha();
            blinkDim |= a < 0.2;
            blinkFull |= a > 0.98;
        }
        check("闪烁（幅度 100）：最暗时几乎全灭、最亮时完全显示", blinkDim && blinkFull);
        check("闪烁幅度 0 时完全不闪",
                nearly(SequenceAnim.loopEffect(SequenceAnim.LOOP_BLINK, 1.0, 0).alpha(), 1));
        boolean hopUp = true;
        for (int i = 0; i <= 20; i++) {
            double phase = Math.PI * 2 * i / 20.0;
            if (SequenceAnim.loopEffect(SequenceAnim.LOOP_HOP, phase, 10).dy() < -1.0e-9) {
                hopUp = false;
            }
        }
        check("跳动：只往上跳、不会跑到基线以下", hopUp);
        SequenceAnim.State shake = SequenceAnim.loopEffect(SequenceAnim.LOOP_SHAKE, Math.PI / 2, 20);
        check("抖动：相位 π/2 时横向偏到最大（20 × 0.18 = 3.6）", nearly(shake.dx(), 3.6));
        check("循环里未知编号不抛异常且无效果",
                SequenceAnim.loopEffect(999, 1.0, 10).isNormal());
    }

    private static void clipBounds() {
        check("片段接受新增编号（sanitize 不会把它们夹回「无」）",
                SequenceClip.validAnim(SequenceAnim.ANIM_SHAKE) == SequenceAnim.ANIM_SHAKE
                        && SequenceClip.validLoop(SequenceAnim.LOOP_HOP) == SequenceAnim.LOOP_HOP);
        SequenceClip c = new SequenceClip();
        c.inAnim = 12;
        c.outAnim = 7;
        c.loopAnim = 7;
        c.sanitize();
        check("sanitize 之后三个编号都保住了", c.inAnim == 12 && c.outAnim == 7 && c.loopAnim == 7);
        CompoundTag t = c.save();
        SequenceClip back = SequenceClip.load(t);
        check("存档往返保住了新编号", back.inAnim == 12 && back.outAnim == 7 && back.loopAnim == 7);
        SequenceClip bad = SequenceClip.load(new CompoundTag());
        check("空存档 -> 一律「无」", bad.inAnim == 0 && bad.outAnim == 0 && bad.loopAnim == 0);
    }

    // ------------------------------------------------------------------ ② 预览与播放同源

    private static void sameTruth() {
        String track = readFile("src/main/java/top/hmjmfabc/projector/common/sequence/SequenceTrack.java");
        String picker = readFile("src/main/java/top/hmjmfabc/projector/client/gui/SequenceAnimPickerScreen.java");
        check("运行期走 SequenceAnim.inEffect/outEffect/loopEffect（不是自己写一套 switch）",
                track.contains("SequenceAnim.inEffect(") && track.contains("SequenceAnim.outEffect(")
                        && track.contains("SequenceAnim.loopEffect("));
        check("预览界面走同一组函数",
                picker.contains("SequenceAnim.inEffect(") && picker.contains("SequenceAnim.outEffect(")
                        && picker.contains("SequenceAnim.loopEffect("));
        check("运行期里不再残留「按种类 switch 位移」的第二套几何",
                !track.contains("case SequenceAnim.ANIM_SLIDE ->"));
        // 端到端：真的跑一个流程，验证擦除动画在运行期也把宽度拉开
        SequenceTrack tr = new SequenceTrack();
        TextWidget w = new TextWidget();
        w.text = "x";
        w.w = 16;
        w.h = 16;
        SequenceClip clip = new SequenceClip(w.id, 0, 4);
        clip.inAnim = SequenceAnim.ANIM_WIPE;
        clip.inDur = 1.0;
        clip.outAnim = SequenceAnim.ANIM_NONE;
        tr.add(clip);
        tr.playFromStart(0);
        double at0 = tr.stateOf(w, 0L).effScaleX();
        double at10 = tr.stateOf(w, 10L).effScaleX();
        double at20 = tr.stateOf(w, 20L).effScaleX();
        check("运行期擦除：t=0 宽度 0、t=0.5s 半开（缓动）、t=1s 全开",
                nearly(at0, 0) && nearly(at10, 0.5) && nearly(at20, 1));
        // 横向与纵向缩放必须分别作用（drawAnimated 里用的是 effScaleX/effScaleY）
        String renderer = readFile("src/main/java/top/hmjmfabc/projector/client/render/WidgetRenderer.java");
        check("渲染端用 effScaleX/effScaleY 分别缩放（不再只认一个 scale）",
                renderer.contains("st.effScaleX()") && renderer.contains("st.effScaleY()"));
    }

    // ------------------------------------------------------------------ ③ 棋类视觉

    private static void chessVisual() {
        ChessWidget c = new ChessWidget();
        check("棋盘底色默认全透明（用户要求：黑底改透明底）", (c.boardColor >>> 24) == 0);
        check("格线默认白色（与音乐控件同一套白线）", c.lineColor == 0xFFFFFFFF);
        check("边框默认白色且默认打开", c.borderColor == 0xFFFFFFFF && c.showBorder);
        check("最后一手高亮默认半透明白", (c.highlightColor >>> 24) < 0xFF
                && (c.highlightColor & 0xFFFFFF) == 0xFFFFFF);

        String src = readFile("src/main/java/top/hmjmfabc/projector/client/render/WidgetRenderer.java");
        check("渲染端：底色全透明时不提交底色四边形",
                src.contains("if ((w.boardColor >>> 24) != 0)"));
        check("渲染端：画了四条边框线（用 w.borderColor）",
                src.contains("w.showBorder && (w.borderColor >>> 24) != 0")
                        && src.contains("int bc = QuadCollector.withAlpha(w.borderColor, w.alpha);"));
        check("渲染端：选中格子的高亮改成白色（旧的是黄色 0x90FFD479）",
                src.contains("0x99FFFFFF") && !src.contains("0x90FFD479"));

        // 存档往返
        ChessWidget c2 = new ChessWidget();
        c2.boardColor = 0x40123456;
        c2.lineColor = 0xFF00FF00;
        c2.borderColor = 0xFFFF0000;
        c2.showBorder = false;
        CompoundTag t = new CompoundTag();
        t = c2.save();
        ChessWidget c3 = new ChessWidget();
        c3.loadExtra(t);
        check("棋盘的底色/格线/边框/开关都能存读",
                c3.boardColor == 0x40123456 && c3.lineColor == 0xFF00FF00
                        && c3.borderColor == 0xFFFF0000 && !c3.showBorder);
    }

    private static void chessMigration() {
        check("旧默认黑底迁移成透明底", ChessWidget.migrateBoard(0xFF12121C) == 0);
        check("旧默认灰线迁移成白色", ChessWidget.migrateLine(0xFF7A7A92) == 0xFFFFFFFF);
        check("玩家自己挑的颜色一个字都不动",
                ChessWidget.migrateBoard(0xFF00FF00) == 0xFF00FF00
                        && ChessWidget.migrateLine(0x80123456) == 0x80123456);

        // 旧存档（写着旧默认值）走一遍 loadExtra：必须看到迁移结果
        CompoundTag old = new CompoundTag();
        old.putInt("boardColor", 0xFF12121C);
        old.putInt("lineColor", 0xFF7A7A92);
        old.putInt("hlColor", 0x80FFD479);
        old.putInt("colorA", 0xFFFF6666);
        old.putInt("colorB", 0xFF66AAFF);
        ChessWidget c = new ChessWidget();
        c.loadExtra(old);
        check("旧存档：黑底 -> 透明、灰线 -> 白、黄高亮 -> 半透明白",
                c.boardColor == 0 && c.lineColor == 0xFFFFFFFF && c.highlightColor == 0x59FFFFFF);
        check("旧存档：边框默认白色（以前没有这个字段）", c.borderColor == 0xFFFFFFFF);
        check("旧存档：棋子颜色不受影响", c.colorA == 0xFFFF6666 && c.colorB == 0xFF66AAFF);
        CompoundTag fresh = new CompoundTag();
        fresh = new ChessWidget().save();
        ChessWidget c2 = new ChessWidget();
        c2.loadExtra(fresh);
        check("新存档（自带新默认值）读回来还是透明底 + 白线 + 白边框",
                c2.boardColor == 0 && c2.lineColor == 0xFFFFFFFF && c2.borderColor == 0xFFFFFFFF);
    }

    // ------------------------------------------------------------------ ④ 边框调色

    private static void musicBorder() {
        MusicWidget m = new MusicWidget();
        check("音乐控件有独立的边框色，默认白色", m.borderColor == 0xFFFFFFFF);
        String src = readFile("src/main/java/top/hmjmfabc/projector/client/render/MusicWidgetRenderer.java");
        check("音乐边框用的是 borderColor（不再是 textColor）",
                src.contains("QuadCollector.withAlpha(w.borderColor, w.alpha)"));
        // 旧存档：没有 borderColor 字段 -> 跟随文字色，观感与以前完全一致
        CompoundTag old = new CompoundTag();
        old.putInt("textColor", 0xFFFF0000);
        MusicWidget m2 = new MusicWidget();
        m2.loadExtra(old);
        check("旧存档：边框跟随文字色（不会因为新字段而突然变白）", m2.borderColor == 0xFFFF0000);
        m2.borderColor = 0xFF00FF00;
        CompoundTag t = new CompoundTag();
        t = m2.save();
        MusicWidget m3 = new MusicWidget();
        m3.loadExtra(t);
        check("新字段能存读", m3.borderColor == 0xFF00FF00);

        String editor = readFile("src/main/java/top/hmjmfabc/projector/client/gui/WidgetEditorScreen.java");
        check("编辑器：调色盘有「部位」选择器（音乐 2 个 / 棋盘 4 个颜色）",
                editor.contains("private int colorPart;") && editor.contains("private static String[] colorParts(Widget target)"));
        check("编辑器：音乐分支与棋盘分支都插入了调色盘",
                editor.contains("y = colorPalette(cx, y, cw, mw);")
                        && editor.contains("y = colorPalette(cx, y, cw, ch);"));
        check("编辑器：音乐与棋盘各有一个「回得到无色」的边框开关（调色盘全是不透明色）",
                editor.contains("mw.borderColor = (mw.borderColor >>> 24) == 0 ? 0xFFFFFFFF : 0x00000000;")
                        && editor.contains("ch.borderColor = on ? 0x00000000 : 0xFFFFFFFF;"));
        check("编辑器：棋盘有「底色：透明/有色」开关（点过色块之后还能回到透明底）",
                editor.contains("ch.boardColor = (ch.boardColor >>> 24) == 0 ? 0x80000000 : 0x00000000;"));
        check("编辑器：applyColor 按部位写入（音乐边框 / 棋盘四个色）",
                editor.contains("mw.borderColor = argb") && editor.contains("cw.borderColor = argb")
                        && editor.contains("cw.lineColor = argb") && editor.contains("cw.boardColor = argb")
                        && editor.contains("cw.highlightColor = argb"));
    }

    // ------------------------------------------------------------------ ⑤ 打字动画

    private static void typewriter() {
        check("可见字数：格式化代码不算",
                TextLayout.visibleCount("&a你好") == 2
                        && TextLayout.visibleCount("&#FF0000abc") == 3
                        && TextLayout.visibleCount("&z彩虹") == 2);
        check("可见字数：&& 算一个（它渲染出来就是一个 &）",
                TextLayout.visibleCount("a&&b") == 3);
        check("可见字数：换行也算一个（打字时换行也是一次敲键）",
                TextLayout.visibleCount("a\nb") == 3);
        check("可见字数：空串/缺省为 0",
                TextLayout.visibleCount("") == 0 && TextLayout.visibleCount(null) == 0);

        check("取前 0 个字 = 空串", TextLayout.visiblePrefix("&a你好", 0).isEmpty());
        check("取前 1 个字：颜色代码带上、只出一个字",
                TextLayout.visiblePrefix("&a你好", 1).equals("&a你"));
        check("取前 2 个字 = 全文", TextLayout.visiblePrefix("&a你好", 2).equals("&a你好"));
        check("超过总字数时给全文（不会多加也不会报错）",
                TextLayout.visiblePrefix("&a你好", 99).equals("&a你好"));
        check("16 色码与 &#RRGGBB 都不会被截成半截",
                TextLayout.visiblePrefix("&#FF0000ab", 1).equals("&#FF0000a")
                        && TextLayout.visiblePrefix("&l&nab", 1).equals("&l&na"));
        // ⚠ 取出来的前缀是**给排版器吃的源码**，所以 && 必须原样保留两个字符：
        // 只写一个 '&' 的话，后面那个字会被当成颜色代码吃掉（"&a" 是绿色）
        check("&& 取一个字时保留成 &&（排版出来才是一个 &，不会被当成颜色代码）",
                TextLayout.visiblePrefix("&&ab", 1).equals("&&")
                        && FormatCodes.strip(TextLayout.visiblePrefix("&&ab", 1)).equals("&")
                        && TextLayout.visiblePrefix("&&ab", 2).equals("&&a"));
        // 代理对（emoji）不能被劈成半个
        String emoji = "\uD83D\uDE00x";
        String half = TextLayout.visiblePrefix(emoji, 1);
        check("代理对整取（emoji 不会变成半个字符）",
                half.codePointCount(0, half.length()) == 1 && half.equals("\uD83D\uDE00"));
        check("前缀是幂等的：再取一次同样的字数结果不变",
                TextLayout.visiblePrefix(TextLayout.visiblePrefix("&a你好世界", 2), 2)
                        .equals("&a你好"));

        // 控件侧：打字进度
        TextWidget w = new TextWidget();
        w.text = "&a你好世界再见";     // 6 个可见字
        check("关掉打字动画时永远「全部显示」",
                w.typedChars(0L) == Integer.MAX_VALUE);
        w.typewriter = true;
        w.typeSpeed = 2.0;          // 每秒 2 个字
        w.typeLoop = false;
        check("t=0 一个字都没打出来", w.typedChars(0L) == 0);
        check("t=1s 打了 2 个字", w.typedChars(20L) == 2);
        check("超过总字数后回到「全部显示」", w.typedChars(200L) == Integer.MAX_VALUE);
        w.typeLoop = true;
        w.typeHold = 1.0;
        // 一轮 = 6/2 + 1 = 4 秒；t=4.5s 时是新一轮的 0.5s -> 1 个字
        check("循环模式会从头再来", w.typedChars((long) (4.5 * 20)) == 1);
        check("typeSpeed 非法时回落到 6 字/秒（不除零、不卡死）",
                newSpeedCheck());
        CompoundTag t = new CompoundTag();
        t = w.save();
        TextWidget w2 = new TextWidget();
        w2.loadExtra(t);
        check("打字设置能存读",
                w2.typewriter && nearly(w2.typeSpeed, 2.0) && w2.typeLoop && nearly(w2.typeHold, 1.0));
        TextWidget w3 = new TextWidget();
        w3.loadExtra(new CompoundTag());
        check("旧存档（没有这些键）默认关闭打字动画，观感与以前一致", !w3.typewriter);

        String src = readFile("src/main/java/top/hmjmfabc/projector/client/render/WidgetRenderer.java");
        check("渲染端：文字控件走 typedPrefix（打字动画真的接上了）",
                src.contains("String shown = typedPrefix(w);")
                        && src.contains("return top.hmjmfabc.projector.common.text.TextLayout.visiblePrefix(w.text, n);"));
        String editor = readFile("src/main/java/top/hmjmfabc/projector/client/gui/WidgetEditorScreen.java");
        check("编辑器：文字分支有「打字动画」开关与速度滑块",
                editor.contains("tw.typewriter = !tw.typewriter")
                        && editor.contains("tw.typeSpeed = v") && editor.contains("tw.typeHold = v"));
    }

    private static boolean newSpeedCheck() {
        TextWidget w = new TextWidget();
        w.text = "abc";
        w.typewriter = true;
        w.typeSpeed = 0;
        // 回落到 6 字/秒：1 秒能打 6 个字 > 总共 3 个 ⇒ 「全部显示」
        return w.typedChars(20L) == Integer.MAX_VALUE;
    }

    // ------------------------------------------------------------------ 格式化（顺带）

    private static void formatHelpers() {
        check("codeLengthAt：&a=2 / &#RRGGBB=8 / &&=2 / &z=2",
                FormatCodes.codeLengthAt("&a", 0) == 2
                        && FormatCodes.codeLengthAt("&#FF0000", 0) == 8
                        && FormatCodes.codeLengthAt("&&", 0) == 2
                        && FormatCodes.codeLengthAt("&z", 0) == 2);
        check("codeLengthAt：不认识的代码返回 0（与 parse 的「原样输出」一致）",
                FormatCodes.codeLengthAt("&q", 0) == 0 && FormatCodes.codeLengthAt("x", 0) == 0
                        && FormatCodes.codeLengthAt("&#GGGGGG", 0) == 0);
        check("codeLengthAt：双色渐变整段算一个代码（&s#RRGGBB&#BBGGRR = 17 个字符）",
                FormatCodes.codeLengthAt("&s#FF0000&#00FF00", 0) == 17
                        && FormatCodes.codeLengthAt("&s#FF0000e#00FF00", 0) == 17);
        check("codeLengthAt：越界不炸",
                FormatCodes.codeLengthAt("&", 0) == 0 && FormatCodes.codeLengthAt(null, 0) == 0
                        && FormatCodes.codeLengthAt("&a", 5) == 0);

        // 音乐控件的时间格式：换成手写补零之后必须与旧 String.format 逐字一致
        check("m:ss 格式：0 -> 0:00、61000 -> 1:01、3599000 -> 59:59",
                musicTime(0).equals("0:00") && musicTime(61_000).equals("1:01")
                        && musicTime(3_599_000).equals("59:59"));
        check("h:mm:ss 格式：3661000 -> 1:01:01、负数按 0 处理",
                musicTime(3_661_000).equals("1:01:01") && musicTime(-5).equals("0:00"));
    }

    private static String musicTime(long ms) {
        try {
            return (String) Class.forName("top.hmjmfabc.projector.client.render.MusicWidgetRenderer")
                    .getMethod("formatTime", long.class).invoke(null, ms);
        } catch (Throwable t) {
            failed.add("调用 MusicWidgetRenderer.formatTime 失败: " + t);
            return "";
        }
    }

    // ------------------------------------------------------------------ 性能：不许退化

    private static void perfSources() {
        String text = readFile("src/main/java/top/hmjmfabc/projector/client/render/TextRenderer.java");
        check("文字排版有 LRU 缓存（本批最值钱的一处优化）",
                text.contains("LAYOUT_CACHE_MAX") && text.contains("runsFor(")
                        && text.contains("new java.util.LinkedHashMap<>(32, 0.75f, true)"));
        check("排版缓存：缺字（codePoint<0）的排版不入缓存",
                text.contains("hasMissing") && text.contains("if (!hasMissing)"));
        check("排版缓存：FontMetrics 包装按字体复用（不再每次 new 匿名对象）",
                text.contains("METRICS") && text.contains("metricsOf("));
        check("drawInBox 走缓存入口（不再直接调 layoutRuns）",
                text.contains("runsFor(font, text, fontSize, wrapWidth, lineSpacing)")
                        && !text.contains("TextLayout.layoutRuns(text, FontManager.metrics(font)"));

        String w = readFile("src/main/java/top/hmjmfabc/projector/client/render/WidgetRenderer.java");
        check("colorPrefix 有记忆化（棋盘 32 个棋子/排行榜每行都调它）",
                w.contains("COLOR_CACHE_SIZE") && w.contains("COLOR_CACHE_VAL"));
        check("图片诊断先比签名再拼日志（不再每帧 String.format）",
                w.contains("IMAGE_REPORT.put(w.id, sig)") && !w.contains("IMAGE_REPORT.put(w.id, line)"));
        check("棋盘诊断同样先比签名", w.contains("ChessSig") && w.contains("CHESS_REPORT.put(w.id, sig)"));
        check("天气按 tick 缓存（不再每帧做生物群系查询）",
                w.contains("private static WeatherState computeWeather()") && w.contains("weatherCached"));
        check("文字控件的底色**先画**（以前排在文字后面，会把文字盖住）",
                w.indexOf("if (w.background != 0) {\n            solidRect(collector, ctx, w, w.x, w.y, w.x + w.w, w.y + w.h, w.background);")
                        < w.indexOf("String shown = typedPrefix(w);"));

        String chess = readFile("src/main/java/top/hmjmfabc/projector/common/widget/ChessWidget.java");
        check("棋盘的「合法落点」提示按局面签名缓存（不再每帧跑一遍着法生成）",
                chess.contains("hintSignature()") && chess.contains("cacheHints(")
                        && chess.contains("if (hintSig == hintSignature())"));

        String board = readFile("src/main/java/top/hmjmfabc/projector/client/LeaderboardSource.java");
        check("排行榜读取有 TTL 缓存（每帧一次全量读取 + 排序变成 0.5 秒一次）",
                board.contains("CACHE_TTL_MS") && board.contains("computeRows("));

        String music = readFile("src/main/java/top/hmjmfabc/projector/client/render/MusicWidgetRenderer.java");
        check("音乐：单行省略号走「一次排版 + 一次遍历」（不再逐个候选重排）",
                music.contains("truncateByRuns(") && music.contains("TextRenderer.runsFor("));
        check("音乐：时间格式不再用 String.format",
                music.contains("private static String two(long v)") && !music.contains("String.format(java.util.Locale.ROOT, \"%d:%02d"));
    }

    // ------------------------------------------------------------------ 编辑器：不再「切换动画」

    private static void editorSources() {
        String seq = readFile("src/main/java/top/hmjmfabc/projector/client/gui/SequenceEditorScreen.java");
        check("三个「切换XX动画」按钮已删除",
                !seq.contains("\\u5207\\u6362\\u5165\\u573a\\u52a8\\u753b")
                        && !seq.contains("\\u5207\\u6362\\u51fa\\u573a\\u52a8\\u753b")
                        && !seq.contains("\\u5207\\u6362\\u5faa\\u73af\\u52a8\\u753b"));
        check("三个入口都改成「选择动画…」并打开二级界面",
                seq.contains("\\u9009\\u62e9\\u52a8\\u753b\\u2026")
                        && seq.contains("SequenceAnimPickerScreen.MODE_IN")
                        && seq.contains("SequenceAnimPickerScreen.MODE_OUT")
                        && seq.contains("SequenceAnimPickerScreen.MODE_LOOP"));
        check("选择结果统一走 pickAnim（赋值 + sanitize + 提交，只有一份）",
                seq.contains("private void pickAnim(SequenceClip c, int mode, int id)")
                        && seq.contains("c.outAnim = SequenceClip.validAnim(id)")
                        && seq.contains("c.loopAnim = SequenceClip.validLoop(id)"));
        check("滑动方向按钮改成「按动画类型决定是否显示」",
                seq.contains("SequenceAnim.usesSlideAngle(c.inAnim)"));
        check("闪烁的幅度上限给到 100（否则「闪烁」看不出来）",
                seq.contains("SequenceAnim.LOOP_BLINK ? 100"));

        String picker = readFile("src/main/java/top/hmjmfabc/projector/client/gui/SequenceAnimPickerScreen.java");
        check("二级界面：三种模式（入场/出场/循环）都有标题", picker.contains("MODE_OUT") && picker.contains("MODE_LOOP"));
        check("二级界面：出场模式用 OUT_ANIM_NAMES / outAnimHint（否则会把「上升」写成「下落」）",
                picker.contains("case MODE_OUT -> SequenceAnim.OUT_ANIM_NAMES;")
                        && picker.contains("case MODE_OUT -> SequenceAnim.outAnimHint(id);"));
        check("流程编辑器：出场标签用 outAnimName",
                seq.contains("SequenceAnim.outAnimName(c.outAnim)"));
        check("二级界面：每个动画一行 + 一个会动的预览框",
                picker.contains("rowBoxes") && picker.contains("drawPreview("));
        check("二级界面：列出全部动画（用名字表的长度决定行数）",
                picker.contains("names().length"));
        check("二级界面：末位片段不出场的提示写在界面上",
                picker.contains("\\u6700\\u540e\\u4e00\\u4e2a\\u7247\\u6bb5\\u4e0d\\u4f1a\\u51fa\\u573a"));
    }

    // ------------------------------------------------------------------ 工具

    private static boolean nearly(double a, double b) {
        return Math.abs(a - b) < 1.0e-6;
    }

    private static String describe(SequenceAnim.State s) {
        return "dx=" + s.dx() + " dy=" + s.dy() + " dDepth=" + s.dDepth()
                + " scaleX=" + s.effScaleX() + " scaleY=" + s.effScaleY()
                + " alpha=" + s.alpha() + " rot=" + s.rotDeg();
    }

    private static String readFile(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (Exception e) {
            failed.add("读不到 " + path);
            return "";
        }
    }

    private static void check(String name, boolean ok) {
        check(name, ok, "");
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  \u2705 " + name);
        } else {
            failed.add(name + (detail.isEmpty() ? "" : "  [" + detail + "]"));
        }
    }
}
