package top.hmjmfabc.projector;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * 服务端/通用配置。所有会显著影响内存与带宽的开关都放在这里，
 * 方便低配设备（例如 Android 启动器）自行降档。
 */
public final class ProjectorConfig {

    public static final ModConfigSpec SPEC;
    public static final ProjectorConfig INSTANCE;

    static {
        Pair<ProjectorConfig, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(ProjectorConfig::new);
        INSTANCE = pair.getLeft();
        SPEC = pair.getRight();
    }

    // ---- 平面 ----
    public final ModConfigSpec.IntValue maxPlaneBlocks;
    public final ModConfigSpec.IntValue maxPlanesPerWorld;
    public final ModConfigSpec.IntValue maxPlaneSpan;
    public final ModConfigSpec.BooleanValue sameBlockOnly;
    public final ModConfigSpec.IntValue selectDistance;
    public final ModConfigSpec.IntValue maxWidgetsPerPlane;

    // ---- 媒体 ----
    public final ModConfigSpec.IntValue maxImageBytes;
    public final ModConfigSpec.IntValue maxVideoBytes;
    public final ModConfigSpec.IntValue uploadChunkBytes;
    public final ModConfigSpec.IntValue maxDecodedImageSize;
    public final ModConfigSpec.IntValue videoFrameCacheFrames;
    /** 【27.1.1】所有视频帧槽位的总内存预算（MB），跨媒体生效。 */
    public final ModConfigSpec.IntValue videoMaxFrameMemoryMb;
    public final ModConfigSpec.IntValue maxVideoFps;
    public final ModConfigSpec.IntValue maxMediaPerWorld;

    // ---- ① 字体核验 ----
    public final ModConfigSpec.BooleanValue verifyFontHashes;

    // ---- ② 配额 / 压缩 / 流量 ----
    public final ModConfigSpec.IntValue adminMaxImageBytes;
    public final ModConfigSpec.IntValue adminMaxVideoBytes;
    public final ModConfigSpec.IntValue autoCompressVideoBytes;
    public final ModConfigSpec.BooleanValue allowOriginalVideoUpload;
    public final ModConfigSpec.IntValue blockVideoBytes;
    public final ModConfigSpec.BooleanValue blockOversizeVideo;
    public final ModConfigSpec.LongValue dailyOutboundLimitBytes;
    public final ModConfigSpec.BooleanValue logOutboundTransfers;

    /** 【②】是否只允许管理员上传媒体（默认 false = 普通玩家也能上传，受各自配额限制）。 */
    public final ModConfigSpec.BooleanValue onlyAdminCanUpload;

    /** 【②】客户端上传节奏（字节/秒）：防止发送速度远超上行带宽，把几十 MB 堆在写缓冲里。 */
    public final ModConfigSpec.IntValue uploadPaceBytesPerSec;
    /** 【rc-83】客户端下载媒体的节奏上限（字节/秒，0 = 不限）。 */
    public final ModConfigSpec.IntValue downloadPaceBytesPerSec;

    /** 【⑤】内容保护豁免所需的最低权限等级（默认 2；改成 4 就能把等级 2 的 OP 也挡在外面）。 */
    public final ModConfigSpec.IntValue contentProtectMinLevel;

    // ---- ⑫ 客户端缓存档位（COMMON 配置是「每台机器一份」，正合适） ----
    public final ModConfigSpec.IntValue cacheTier;
    public final ModConfigSpec.BooleanValue allowBackgroundVideoCache;

    // ---- 视频转换 ----
    public final ModConfigSpec.IntValue convertFps;
    public final ModConfigSpec.IntValue convertMaxSide;
    public final ModConfigSpec.IntValue convertQuality;

    // ---- 渲染 ----
    // ---- 音乐控件（27.1.1 新增）----
    /** 音量倍率（0~1）。 */
    public final ModConfigSpec.DoubleValue musicVolume;
    /** 听不见的距离（方块）。 */
    public final ModConfigSpec.IntValue musicHearDistance;
    /** 同时最多播放几首（超过时新的会顶掉旧的）。 */
    public final ModConfigSpec.IntValue musicMaxConcurrent;
    /** 网易云搜索返回条数。 */
    public final ModConfigSpec.IntValue musicSearchLimit;
    /** HTTP 代理（host:port），留空 = 直连。 */
    public final ModConfigSpec.ConfigValue<String> musicProxyAddress;
    /** 网易云 Cookie（可选，填了才能拿到高码率/VIP 歌曲）。 */
    public final ModConfigSpec.ConfigValue<String> musicNeteaseCookie;

    public final ModConfigSpec.IntValue renderDistance;
    public final ModConfigSpec.IntValue glyphCachePages;
    public final ModConfigSpec.IntValue atlasPageSize;
    public final ModConfigSpec.BooleanValue avoidFullBrightLight;

    private ProjectorConfig(ModConfigSpec.Builder b) {
        b.comment("平面（Plane）相关限制").push("planes");
        maxPlaneBlocks = b.comment("单个平面最多包含的方块面数量。过大将拖慢圈选与同步。")
                .defineInRange("maxPlaneBlocks", 1024, 1, 8192);
        maxPlanesPerWorld = b.comment("每个维度最多允许存在的平面数量。")
                .defineInRange("maxPlanesPerWorld", 512, 1, 65536);
        // 泛洪是「沿同一朝向的整格方块无限扩散」的，遇到一整面长墙会一路跑到底
        //（实测跑出 254x9 的细长平面）。这个上限按「每个轴向的最大格数」截断，
        // 让「对着墙按 U」得到一块尺寸合理的屏幕，而不是整面墙。
        maxPlaneSpan = b.comment("圈选矩形向锚点每一侧最多外扩多少格（0 = 不限制）。\n"
                        + "圈选逻辑是「从准心出发长出一个矩形，一条边上所有格子都合格才外扩」。\n"
                        + "默认 16 = 最多 33x33 格。只有在 sameBlockOnly 关闭、或整面墙都是\n"
                        + "同一种方块时，这个上限才会真正起作用。")
                .defineInRange("maxPlaneSpan", 16, 0, 512);

        // 圈选边界：这是唯一能把「贴在大墙上的那块屏幕」和「整面墙」区分开的判据。
        // 关掉它，任何整格方块都会连进来，屏幕上就会一直长到 maxPlaneSpan 为止。
        sameBlockOnly = b.comment("圈选时是否要求相邻方块与准心命中的方块是**同一种方块**。\n"
                        + "关闭（默认）：允许多种方块拼成一块屏幕——这是设计意图。\n"
                        + "边界由「矩形生长」自然给出：一条边上只要有任何一格不是合格的面\n"
                        + "（空气、不同高度的方块、半砖等），整条边就不会外扩。\n"
                        + "所以一块悬空的 3x2 面板按 U 就得到 3x2，不会长到旁边的墙上。\n"
                        + "只有当你想要「贴在同一材质大墙上的那块屏幕」时才需要打开它。")
                .define("sameBlockOnly", false);
        selectDistance = b.comment("按 U 圈选平面时的准心射线长度（方块）。")
                .defineInRange("selectDistance", 48, 4, 256);
        maxWidgetsPerPlane = b.comment("单个平面最多允许的控件数量。")
                .defineInRange("maxWidgetsPerPlane", 128, 1, 1024);
        b.pop();

        b.comment("媒体（图片/视频）相关限制").push("media");
        // 【②】maxImageBytes / maxVideoBytes = **普通玩家**的上限；
        // 管理员另有 adminMax* 两项。单人存档两边都不生效（用户明确要求）。
        maxImageBytes = b.comment("【普通玩家】单张图片允许的最大字节数（默认 4 MB）。\n"
                        + "管理员上限见 adminMaxImageBytes；单人存档不受此限制。")
                .defineInRange("maxImageBytes", 4 * 1024 * 1024, 0, 2147483647);
        maxVideoBytes = b.comment("【普通玩家】单个视频允许的最大字节数（默认 64 MB）。\n"
                        + "管理员上限见 adminMaxVideoBytes；单人存档不受此限制。")
                .defineInRange("maxVideoBytes", 64 * 1024 * 1024, 0, 2147483647);
        uploadChunkBytes = b.comment("上传分包大小（字节）。网络差时可调小。")
                .defineInRange("uploadChunkBytes", 20 * 1024, 1024, 512 * 1024);
        maxDecodedImageSize = b.comment("解码后图片的最大边长（像素）。超出会被降采样，用于限制显存。")
                .defineInRange("maxDecodedImageSize", 1024, 64, 4096);
        videoFrameCacheFrames = b.comment("视频解码帧缓存数量（每帧约为 边长^2*4 字节显存）。")
                .defineInRange("videoFrameCacheFrames", 12, 2, 48);
        videoMaxFrameMemoryMb = b.comment("所有视频帧槽位的总内存预算（MB）。同时画多个视频时按此上限分摊，"
                        + "避免槽位总占用失控（每个槽位同时占一份像素缓冲与一份显存）。")
                .defineInRange("videoMaxFrameMemoryMb", 64, 8, 1024);
        maxVideoFps = b.comment("视频播放帧率上限。")
                .defineInRange("maxVideoFps", 20, 1, 60);
        maxMediaPerWorld = b.comment("存档内允许保存的媒体文件数量上限。")
                .defineInRange("maxMediaPerWorld", 256, 0, 4096);

        // ---- ① 字体哈希核验 ----
        verifyFontHashes = b.comment("【① 联机字体核验】客户端与服务端分离时，是否核验两端已安装字体的哈希。\n"
                        + "开启（默认）后：服务端会把自己 <存档>/projector/fonts/ 里的字体清单\n"
                        + "（字体 ID + 文件名 + SHA-1）发给客户端，客户端只能使用两端一致的字体。\n"
                        + "管理员不受限制：他选一个服务端没有的字体时，该字体会被上传到服务端，\n"
                        + "上传完成后服务端再核验一遍哈希，确保文件没有损坏。\n"
                        + "关掉它则退回旧行为（各用各的字体，画面可能不一致）。")
                .define("verifyFontHashes", true);

        // ---- ② 配额 / 压缩 / 流量 ----
        adminMaxImageBytes = b.comment("【管理员】单张图片上限（默认 16 MB）。")
                .defineInRange("adminMaxImageBytes", 16 * 1024 * 1024, 0, 2147483647);
        adminMaxVideoBytes = b.comment("【管理员】单视频上限（默认 256 MB）。")
                .defineInRange("adminMaxVideoBytes", 256 * 1024 * 1024, 0, 2147483647);
        autoCompressVideoBytes = b.comment("视频超过这个大小（默认 32 MB）时，上传前自动压画质压到这么大。\n"
                        + "只有在「服务端未启用原画上传」或「客户端没勾选原画上传」时才会压。\n"
                        + "压画质的方式是**按帧抽稀**（保留每第 N 帧）：MJPEG / ZIP 帧序列都能做，\n"
                        + "不需要重新解码，所以又快又不掉画质。0 = 不自动压缩。")
                .defineInRange("autoCompressVideoBytes", 32 * 1024 * 1024, 0, 2147483647);
        allowOriginalVideoUpload = b.comment("是否允许客户端「原画上传」：勾选后视频按允许的大小原样上传，不压画质。\n"
                        + "服务端不开启时，客户端界面上那个勾选框会是灰的。默认关闭。")
                .define("allowOriginalVideoUpload", false);
        blockVideoBytes = b.comment("超过这个大小的视频**直接阻断上传**，任何人不例外（默认 256 MB）。")
                .defineInRange("blockVideoBytes", 256 * 1024 * 1024, 0, 2147483647);
        blockOversizeVideo = b.comment("是否启用上面那条「超大视频直接阻断」。默认启用。")
                .define("blockOversizeVideo", true);
        contentProtectMinLevel = b.comment("【⑤ 内容保护门槛】能「绕过内容保护」所需的最低权限等级。\n"
                        + "平面创建者永远可以改自己的平面；这个数字管的是**其它管理员**。\n"
                        + "默认 2（与「管理员 = 权限等级 ≥ 2」的既有约定一致）。\n"
                        + "如果你希望「内容保护开着时，连等级 2 的 OP 也改不了」，就把它改成 4。\n"
                        + "⚠ 无论取几，平面创建者与配置文件控制台始终可以改。")
                .defineInRange("contentProtectMinLevel", 2, 1, 4);
        uploadPaceBytesPerSec = b.comment("【②】客户端上传的**节奏上限**（字节/秒，默认 4 MB/s）。\n"
                        + "为什么要限：发送是非阻塞的，一旦发得比真实上行快，未发出的分片会堆在\n"
                        + "客户端网络写缓冲里（几十 MB），既吃内存也容易被对端当成洪水。\n"
                        + "限速后发送速度与上行大致匹配。0 = 不限速（本地网络会自动不限速）。")
                .defineInRange("uploadPaceBytesPerSec", 4 * 1024 * 1024, 0, 2147483647);
        downloadPaceBytesPerSec = b.comment("【rc-83】客户端**下载**媒体的节奏上限（字节/秒，默认 2 MB/s）。\n"
                        + "为什么要限：媒体是几十 MB 的批量流量，而它和「挖方块、圈选平面」这些\n"
                        + "操作包走同一条连接。不限速时一批媒体就能把链路灌满，操作包与保活包被饿死，\n"
                        + "玩家看到的就是「延迟特别大，连圈选平面、破坏方块都点不动」。\n"
                        + "默认 2 MB/s ≈ 12 秒拿完一份 24 MB 的视频，期间游戏依然跟手。\n"
                        + "0 = 不限速（例如服务端在内网时）。\n"
                        + "注意：这只作用于客户端**请求的节奏**（单片之间的间隔），不影响单帧请求。")
                .defineInRange("downloadPaceBytesPerSec", 2 * 1024 * 1024, 0, 2147483647);
        onlyAdminCanUpload = b.comment("【②】是否**只允许管理员**在服务器上传图片/视频。\n"
                        + "默认 **false = 放开**：普通玩家也能上传，但各自受配额限制\n"
                        + "（普通玩家 maxImageBytes / maxVideoBytes，管理员 adminMaxImageBytes / adminMaxVideoBytes，\n"
                        + "默认分别是 4 MB / 64 MB 与 16 MB / 256 MB）。\n"
                        + "改成 true 就恢复成「只有权限等级 4 的 OP 能添加媒体」。\n"
                        + "单人存档与联机房间不受此开关影响（那里的玩家本来就视作管理员，且不限流量）。")
                .define("onlyAdminCanUpload", false);
        dailyOutboundLimitBytes = b.comment("【②】服务端因 Projector 产生的**日**出站流量上限（字节，默认 10 GB = 10737418240）。\n"
                        + "「1 日」= 当天 0:00 到次日 0:00。达到上限后，服务端在当日剩余时间里\n"
                        + "拒绝向任何客户端下发任何媒体文件（图片与视频），次日 0:00 自动恢复。\n"
                        + "目的是省下宝贵的服务端出站带宽。0 = 不限制。\n"
                        + "⚠ 上限必须是 long：默认值 10 GB 本身就超过 int 的 2147483647，\n"
                        + "  所以范围写的是 0 ~ Long.MAX_VALUE（曾经误写成 int 上限，\n"
                        + "  结果 NeoForge 把 10 GB「纠正」成 2 GB，日限额名不副实）。")
                .defineInRange("dailyOutboundLimitBytes", 10L * 1024 * 1024 * 1024, 0L, Long.MAX_VALUE);
        logOutboundTransfers = b.comment("每次下发媒体都往**服务端日志**写一条明细：\n"
                        + "文件大小、哈希、玩家 ID、UUID、IP 地址、当日累计用量。默认开启。")
                .define("logOutboundTransfers", true);
        allowBackgroundVideoCache = b.comment("【⑫】是否允许客户端在后台提前缓存**视频**（不只是图片）。\n"
                        + "默认不允许（视频很吃带宽）。不允许时，即使客户端把缓存档位设成「激进」，\n"
                        + "也会自动回滚到「平衡」——只提前缓存图片。")
                .define("allowBackgroundVideoCache", false);

        cacheTier = b.comment("【⑫ 客户端】媒体缓存档位（本项只对客户端有意义）：\n"
                        + "  0 = 保守：所有媒体都只在需要渲染时才缓存（可能长时间加载不出来）；\n"
                        + "  1 = 平衡（默认，推荐）：后台提前缓存服务端**图片**，视频仍按需缓存；\n"
                        + "  2 = 激进：提前缓存全部媒体（不推荐，很费服务端带宽；除非服务端在内网）。\n"
                        + "若服务端没开「允许客户端后台缓存视频」，选 2 也会自动回滚到 1。")
                .defineInRange("cacheTier", 1, 0, 2);
        b.pop();

        b.comment("视频转换（MP4 等 -> MJPEG / ZIP 帧序列）").push("convert");
        convertFps = b.comment("转换后的帧率。帧率越高越流畅、文件越大（每帧都是一张 JPEG）。")
                .defineInRange("fps", 10, 1, 30);
        convertMaxSide = b.comment("转换后画面的最大边长（像素）。视频会被等比缩放到不超过该值。")
                .defineInRange("maxSide", 512, 64, 2048);
        convertQuality = b.comment("MJPEG 画质，1（最好/最大）~ 31（最差/最小）。")
                .defineInRange("quality", 5, 1, 31);
        b.pop();

        b.comment("音乐控件（本地音频 / 网易云音乐）").push("music");
        musicVolume = b.comment("音量倍率（0~1）。还会再乘上游戏「唱片机/音符盒」那一档的音量设置。")
                .defineInRange("volume", 0.8, 0.0, 1.0);
        musicHearDistance = b.comment("多远之后听不见（方块）。音乐是**世界里的声源**，\n"
                        + "离得越远越轻，超过这个距离就完全听不到。")
                .defineInRange("hearDistance", 32, 4, 256);
        musicMaxConcurrent = b.comment("本客户端同时最多播放几首音乐（默认 1）。\n"
                        + "同一时刻有多个音乐控件在放时，超出的部分不会出声（也不会去下载）。")
                .defineInRange("maxConcurrent", 1, 1, 8);
        musicSearchLimit = b.comment("网易云关键字搜索一次返回多少条（1~30）。")
                .defineInRange("searchLimit", 10, 1, 30);
        musicProxyAddress = b.comment("音乐模块用的 HTTP 代理，格式 host:port；留空 = 直连。\n"
                        + "只影响音乐（网易云接口与音频下载），不影响游戏本体。")
                .define("proxyAddress", "");
        musicNeteaseCookie = b.comment("网易云 Cookie（可选）。留空时是游客态：\n"
                        + "免费歌曲可以正常播放，VIP/高码率歌曲会失败（会给出提示）。\n"
                        + "填法：浏览器登录 music.163.com 后复制整条 Cookie（含 MUSIC_U=...）。")
                .define("neteaseCookie", "");
        b.pop();

        b.comment("渲染相关").push("render");
        renderDistance = b.comment("平面内容的最远渲染距离（方块）。超出距离的平面不再绘制。")
                .defineInRange("renderDistance", 64, 8, 256);
        glyphCachePages = b.comment("字体字形图集的最大页数（每页 atlasPageSize^2 x 4 字节显存；"
                        + "1024 时一页 4 MB）。页数不够时长文本会退化成方框。")
                .defineInRange("glyphCachePages", 4, 1, 16);
        atlasPageSize = b.comment("字形图集单页边长（像素）。")
                .defineInRange("atlasPageSize", 1024, 256, 4096);
        // 【用户 ④】Projector 的大改渲染与光影兼容，但控件被光影错误识别为光源
        //（只显示发光、没有实际亮度）。根因是顶点光照填了「天空光 15 + 方块光 15」
        // 这个「全亮」签名，光影会把它当自发光。降 1 档方块光即可绕开，
        // 亮度差异肉眼不可辨。
        avoidFullBrightLight = b.comment("【光影兼容】是否避免把控件标记成「全亮光照」。\n"
                        + "光影包（Iris / OptiFine）会把「天空光 15 + 方块光 15」当成自发光，\n"
                        + "于是平面上的控件看起来在发光、却没有真实亮度，非常突兀。\n"
                        + "打开（默认）时改用「天空光 15 + 方块光 14」：肉眼完全看不出差别，\n"
                        + "但已经不再是全亮签名，光影会按普通表面处理它。\n"
                        + "若你的光影包出现内容变暗等异常，关掉这一项即可恢复旧行为。")
                .define("avoidFullBrightLight", true);
        b.pop();
    }
}
