package top.hmjmfabc.projector.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import top.hmjmfabc.projector.Projector;

import java.util.UUID;

/**
 * 所有网络包的统一定义。
 *
 * <p>模组采用「服务端权威」模型：平面结构、权限、内容由服务端保存并校验，
 * 客户端只负责交互与渲染。共 8 个包，全部走 {@code play} 阶段。</p>
 */
public final class Payloads {

    private Payloads() {
    }

    /** 构造一个包类型。名字刻意不叫 {@code type()}，避免与 record 的 {@code type()} 访问器冲突。 */
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String path) {
        return new CustomPacketPayload.Type<>(Projector.id(path));
    }

    // ------------------------------------------------------------------
    // 1. 圈选后请求把该面注册为平面
    // ------------------------------------------------------------------
    public record CreatePlane(
            ResourceLocation dimension,
            long anchor,
            String face,
            double hitX, double hitY, double hitZ) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<CreatePlane> TYPE =
                payloadType("create_plane");

        public static final StreamCodec<FriendlyByteBuf, CreatePlane> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeResourceLocation(p.dimension);
                    buf.writeLong(p.anchor);
                    buf.writeUtf(p.face, 16);
                    buf.writeDouble(p.hitX);
                    buf.writeDouble(p.hitY);
                    buf.writeDouble(p.hitZ);
                },
                buf -> new CreatePlane(buf.readResourceLocation(), buf.readLong(), buf.readUtf(16),
                        buf.readDouble(), buf.readDouble(), buf.readDouble()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------------------
    // 2. 服务端要求客户端打开某个平面的对话框
    // ------------------------------------------------------------------
    public record OpenDialog(UUID planeId, boolean isNew) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<OpenDialog> TYPE =
                payloadType("open_dialog");

        public static final StreamCodec<FriendlyByteBuf, OpenDialog> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUUID(p.planeId);
                    buf.writeBoolean(p.isNew);
                },
                buf -> new OpenDialog(buf.readUUID(), buf.readBoolean()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------------------
    // 3. 平面全量同步（玩家进入维度时）
    // ------------------------------------------------------------------
    public record SyncPlanes(ResourceLocation dimension, CompoundTag data) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<SyncPlanes> TYPE =
                payloadType("sync_planes");

        public static final StreamCodec<FriendlyByteBuf, SyncPlanes> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeResourceLocation(p.dimension);
                    buf.writeNbt(p.data);
                },
                buf -> new SyncPlanes(buf.readResourceLocation(), buf.readNbt()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------------------
    // 4. 平面编辑请求（rename/protect/addWidget/...）
    // ------------------------------------------------------------------
    public record PlaneEdit(UUID planeId, String op, CompoundTag args) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<PlaneEdit> TYPE =
                payloadType("plane_edit");

        public static final StreamCodec<FriendlyByteBuf, PlaneEdit> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUUID(p.planeId);
                    buf.writeUtf(p.op, 32);
                    buf.writeNbt(p.args);
                },
                buf -> new PlaneEdit(buf.readUUID(), buf.readUtf(32), buf.readNbt()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------------------
    // 5. 媒体上传（首包带元数据，后续分包发送原始字节）
    // ------------------------------------------------------------------
    public record MediaUpload(int session, String hash, String name, boolean video,
                              int width, int height, int frameCount, double fps,
                              long totalSize, long[] frameTable,
                              int offset, byte[] data, boolean last) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<MediaUpload> TYPE =
                payloadType("media_upload");

        public static final StreamCodec<FriendlyByteBuf, MediaUpload> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.session);
                    buf.writeBoolean(p.offset == 0);
                    if (p.offset == 0) {
                        buf.writeUtf(p.hash, 64);
                        buf.writeUtf(p.name, 128);
                        buf.writeBoolean(p.video);
                        buf.writeVarInt(p.width);
                        buf.writeVarInt(p.height);
                        buf.writeVarInt(p.frameCount);
                        buf.writeDouble(p.fps);
                        buf.writeVarLong(p.totalSize);
                        long[] ft = p.frameTable == null ? new long[0] : p.frameTable;
                        buf.writeVarInt(ft.length);
                        for (long l : ft) buf.writeLong(l);
                    }
                    buf.writeVarInt(p.offset);
                    buf.writeByteArray(p.data);
                    buf.writeBoolean(p.last);
                },
                buf -> {
                    int session = buf.readVarInt();
                    boolean head = buf.readBoolean();
                    String hash = "";
                    String name = "";
                    boolean video = false;
                    int w = 0, h = 0, fc = 1;
                    double fps = 10;
                    long total = 0;
                    long[] ft = new long[0];
                    if (head) {
                        hash = buf.readUtf(64);
                        name = buf.readUtf(128);
                        video = buf.readBoolean();
                        w = buf.readVarInt();
                        h = buf.readVarInt();
                        fc = buf.readVarInt();
                        fps = buf.readDouble();
                        total = buf.readVarLong();
                        int n = buf.readVarInt();
                        // 上界校验：否则一个十几字节的包就能让 JVM 申请天文数字的数组
                        if (n < 0 || n > 400_000) {
                            throw new io.netty.handler.codec.DecoderException("frameTable too large: " + n);
                        }
                        ft = new long[n];
                        for (int i = 0; i < n; i++) ft[i] = buf.readLong();
                    }
                    int offset = buf.readVarInt();
                    byte[] data = buf.readByteArray();
                    boolean last = buf.readBoolean();
                    return new MediaUpload(session, hash, name, video, w, h, fc, fps, total, ft, offset, data, last);
                });

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------------------
    // 6. 客户端请求媒体数据（客户端本地没有该资源时）
    // ------------------------------------------------------------------
    public record MediaRequest(String hash, int frame, int offset, int length) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<MediaRequest> TYPE =
                payloadType("media_request");

        public static final StreamCodec<FriendlyByteBuf, MediaRequest> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.hash, 64);
                    buf.writeVarInt(p.frame);
                    buf.writeVarInt(p.offset);
                    buf.writeVarInt(p.length);
                },
                buf -> new MediaRequest(buf.readUtf(64), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------------------
    // 7. 服务端返回媒体数据分片
    // ------------------------------------------------------------------
    public record MediaData(String hash, int frame, int offset, int total,
                            boolean video, int width, int height, int frameCount, double fps,
                            byte[] data, boolean last, boolean missing) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<MediaData> TYPE =
                payloadType("media_data");

        public static final StreamCodec<FriendlyByteBuf, MediaData> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.hash, 64);
                    buf.writeVarInt(p.frame);
                    buf.writeVarInt(p.offset);
                    buf.writeVarInt(p.total);
                    buf.writeBoolean(p.missing);
                    if (!p.missing) {
                        buf.writeBoolean(p.video);
                        buf.writeVarInt(p.width);
                        buf.writeVarInt(p.height);
                        buf.writeVarInt(p.frameCount);
                        buf.writeDouble(p.fps);
                    }
                    buf.writeByteArray(p.data == null ? new byte[0] : p.data);
                    buf.writeBoolean(p.last);
                },
                buf -> {
                    String hash = buf.readUtf(64);
                    int frame = buf.readVarInt();
                    int offset = buf.readVarInt();
                    int total = buf.readVarInt();
                    boolean missing = buf.readBoolean();
                    boolean video = false;
                    int w = 0, h = 0, fc = 1;
                    double fps = 10;
                    if (!missing) {
                        video = buf.readBoolean();
                        w = buf.readVarInt();
                        h = buf.readVarInt();
                        fc = buf.readVarInt();
                        fps = buf.readDouble();
                    }
                    byte[] data = buf.readByteArray();
                    boolean last = buf.readBoolean();
                    return new MediaData(hash, frame, offset, total, video, w, h, fc, fps, data, last, missing);
                });

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------------------
    // 8. 自定义字体上传
    // ------------------------------------------------------------------
    public record FontUpload(String fontId, String fileName, byte[] data) implements CustomPacketPayload {

        /** 单个字体的体积上限（字节）。 */
        public static final int MAX_FONT_BYTES = 8 * 1024 * 1024;

        public static final CustomPacketPayload.Type<FontUpload> TYPE =
                payloadType("font_upload");

        public static final StreamCodec<FriendlyByteBuf, FontUpload> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.fontId, 64);
                    buf.writeUtf(p.fileName, 128);
                    buf.writeByteArray(p.data);
                },
                buf -> new FontUpload(buf.readUtf(64), buf.readUtf(128), buf.readByteArray()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------------------
    // 10. 【①②⑫】服务端 → 客户端的「环境清单」
    //
    //  一次把客户端决定「能不能用 / 要不要提前下载」所需的全部信息发过去：
    //    media  : [{h: SHA-1, s: 字节数, v: 是否视频}]  ← 客户端拿它在本地比对缓存，
    //             命中就不下载（这正是「服务端先发哈希、客户端完成校验」）
    //    fonts  : [{id: 字体ID, n: 文件名, sha1: 哈希, s: 字节数}]
    //    flags  : 各种开关与配额（原画上传、后台缓存视频、当日流量是否已超限…）
    //
    //  媒体可能很多，所以按 part / last 分片发送。
    // ------------------------------------------------------------------
    public record ServerInfo(CompoundTag data, int part, boolean last) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<ServerInfo> TYPE = payloadType("server_info");

        public static final StreamCodec<FriendlyByteBuf, ServerInfo> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeNbt(p.data);
                    buf.writeVarInt(p.part);
                    buf.writeBoolean(p.last);
                },
                buf -> new ServerInfo(buf.readNbt(), buf.readVarInt(), buf.readBoolean()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------------------
    // 11. 【①】服务端 → 客户端：字体已落盘并核验完毕
    // ------------------------------------------------------------------
    /**
     * 【rc-77】上传分片确认（服务端 → 客户端）。
     *
     * <p>为什么必须有这个包：{@code sendToServer} 是**非阻塞**的，客户端一口气把几十 MB
     * 塞进 socket 写缓冲，真实上行却只有一两 MB/s ⇒ 数据在客户端侧排队，
     * 连**保活包都排在后面** ⇒ 服务端 20~30 秒收不到保活 ⇒ 判 {@code Timed out} 踢人，
     * 而这一整份文件还白传了（用户重试 ⇒ 流量成倍）。
     * 有了逐片确认，客户端最多只领先窗口大小，保活包随时能挤进去，也不再白传。</p>
     *
     * <p>{@code alreadyHave=true} 表示<b>服务端已经存过同哈希的文件</b>：客户端必须立刻停手
     * （一个字节都不该再发），直接按成功处理——这就是「重复上传把流量吃光」的修法。</p>
     */
    public record MediaAck(int session, int received, boolean done, boolean alreadyHave,
                           String reason) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<MediaAck> TYPE = payloadType("media_ack");

        public static final StreamCodec<FriendlyByteBuf, MediaAck> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.session);
                    buf.writeVarInt(p.received);
                    buf.writeBoolean(p.done);
                    buf.writeBoolean(p.alreadyHave);
                    buf.writeUtf(p.reason == null ? "" : p.reason, 128);
                },
                buf -> new MediaAck(buf.readVarInt(), buf.readVarInt(), buf.readBoolean(),
                        buf.readBoolean(), buf.readUtf(128)));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------------------
    public record FontStored(String fontId, String fileName, String sha1, boolean ok,
                             String message) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<FontStored> TYPE = payloadType("font_stored");

        public static final StreamCodec<FriendlyByteBuf, FontStored> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.fontId, 64);
                    buf.writeUtf(p.fileName, 128);
                    buf.writeUtf(p.sha1 == null ? "" : p.sha1, 64);
                    buf.writeBoolean(p.ok);
                    buf.writeUtf(p.message == null ? "" : p.message, 256);
                },
                buf -> new FontStored(buf.readUtf(64), buf.readUtf(128), buf.readUtf(64),
                        buf.readBoolean(), buf.readUtf(256)));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------------------
    // 9. 特殊控件即时操作（重置进度等）
    // ------------------------------------------------------------------
    public record WidgetAction(UUID planeId, UUID widgetId, String action, CompoundTag args) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<WidgetAction> TYPE =
                payloadType("widget_action");

        public static final StreamCodec<FriendlyByteBuf, WidgetAction> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUUID(p.planeId);
                    buf.writeUUID(p.widgetId);
                    buf.writeUtf(p.action, 32);
                    buf.writeNbt(p.args);
                },
                buf -> new WidgetAction(buf.readUUID(), buf.readUUID(), buf.readUtf(32), buf.readNbt()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
