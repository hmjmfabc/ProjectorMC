# 全量 Review —— Fabric 移植版 `27.2.4`（正式版）

> 按 AGENTS.md §1.7 的口径做**一遍**，三类检查面都过掉：
> ①规则/接口一致性　②调用方与死代码　③线程/性能/平台。
> 下面每条都写明**判据**（怎么验的）与**结论**，不写"应该没问题"。

---

## 0. 一句话结论

移植是「只换加载器那一层」：上游 164 个业务文件里 129 个**逐字节未改**，其余 35 个里
**变更 140 行、全部是机械改名或已记录的加载器适配**（下文 §2 逐条列出），没有夹带业务重写。
验证：全量编译 ✅、真实专用服务端启动 ✅、上游 38 套纯逻辑验证 ✅、产物结构校验 ✅。

---

## 1. 规则 / 接口一致性

| 检查项 | 判据 | 结论 |
|---|---|---|
| 上游文件改动范围 | `diff -rq` 上游 ↔ 移植版，再按行分类（import / 非 import） | 129 个文件零改动；35 个文件共 140 行变更，全部见 §2 |
| 没有残留 NeoForge 引用 | `grep -rn "net\.neoforged" src` | **0 处** |
| 事件接线完整（最容易"编译过、功能死"的坑） | 脚本枚举全部 `@SubscribeEvent` 参数类型与 `addListener(X.class,...)`：**26 个监听类型**逐一在触发点确认有 `new X(...)` | **26/26 全部有真实触发点** |
| dist 隔离 | 静态：公共入口/桥/`ServerPayloadContext` 里 `net.minecraft.client.*` 与 `projector.client.*` 引用 = 0；动态：专用服务端实跑 | ✅ 起服成功且 `本端=DEDICATED_SERVER` |
| 网络登记口径 | 与上游同一张 `CLIENTBOUND` 表；`playC2S/playS2C` 两端都登记类型，客户端方向接收器只在物理客户端登记 | ✅ 日志 `C2S 6 / S2C 6`、`客户端包 6 个` |
| 配置契约 | 生成 `config/projector-common.toml`，键数 vs 字段数 | **59 = 59**，8 个段落，键名与 NeoForge 版一致 |
| access widener ↔ accesstransformer | 三条音频字段一一对应 + 一条 `LocalPlayer#getPermissionLevel`；生产 jar 头为 `accesswidener v2 intermediary` | ✅ |
| 许可边界 | 内嵌库 = 6 个（与上游相同）；`com.cinemamod` / `org.cef` **零 import**（`tmp/v34` 136 项通过） | ✅ |
| 鸣谢文本（AGENTS §1.5） | 写进 `fabric.mod.json` 的 description | ✅ 原文一字未改 |
| 版本号两处一致（§1.4） | `gradle.properties: mod_version` ↔ `Projector.BUILD_TAG` | 均为 `27.2.4-snapshot-1` |
| 交付 `-all.jar` | `remapJar.archiveClassifier = 'all'` + 内嵌 6 个 jar | `projector-fabric-1.21.1-27.2.4-snapshot-1-all.jar` |

---

## 2. 有改动的 35 个文件（非机械部分全在这里）

**机械改名（完全等价，共 ~90 行）**：`net.neoforged.*` → `compat.*`，含
`ProjectorConfig` / `ChessAiDriver` / `ClientPayloadHandlers` / `ChessGameScreen` /
`PlaneDialogScreen` / `SequenceEditorScreen` / `SequencePreviewScreen` / `WidgetEditorScreen` /
`MediaUploader` / `WaterMediaBridge` / `McefBridge` / `BetterLoginBridge` /
`SupplierChildLinearLayout` / `ClientNetHandler` / `ProjectorNetwork` / `ServerNetHandler`，
以及 `CrashTaunt` / `FontSelectorScreen` / `MediaCache` / `VideoControls` / `MusicManager` /
`WebControls` / `ServerEvents` / `ClientInputHandler` 里那几行**全限定名**（同样是改名）。

**真正的适配改动（共 8 处，全部有理由）**：

| 文件 | 改了什么 | 为什么 |
|---|---|---|
| `Projector.java` | `addListener` 加显式事件类型参数；`modEventBus` 两处同理 | Java 的 lambda 类里只有 `accept(Object)`，**没有** NeoForge 依赖的泛型元数据（实测 `getDeclaredMethods()` 只有一条）⇒ 猜不出类型，改成显式传 `Class` |
| `client/ProjectorClient.java` | 同上；`migrateStaleKeyBindings` 用 `saveString()` 判 `I` 键 | `KeyMapping#getKey` 是 NeoForge 补丁方法，原版没有 |
| `client/ProjectorKeys.java` | 去掉 `KeyConflictContext.IN_GAME` 构造参数 | 原版 `KeyMapping` 没有这个重载；行为等价（界面里不生效由 `mc.screen != null` 保证） |
| `compat/betterlogin/client/ClientCommandBridge.java` | 改用 `ClientCommandManager` 建树；随之删除死代码 `ClientLoginCommand` | 客户端命令源类型是 `FabricClientCommandSource` |
| `compat/betterlogin/command/ServerLoginCommand.java` | `EnumArgument` → `StringArgumentType` + `suggests` + 解析失败 `sendFailure` | 自定义命令参数类型必须注册进 `ArgumentTypeInfos` 才能随命令树同步，1.21.1 的注册入口是私有的 |
| `compat/betterlogin/client/gui/LoginScreen.java` | `getMinecraft()` → `this.minecraft`；`isActiveAndMatches` → `matches(...)` | 两个都是 NeoForge 补丁方法 |
| `server/ProjectorData|MediaStore|OutboundMeter.java` | `new SavedData.Factory<>(…)` 补 `DataFixTypes.LEVEL` | 原版只有 3 参构造 |
| `client/music/AudioDecoder.java`（+ 新增 `WavReader.java`） | WAV 改为自解析 | **上游真 bug**，见 §5 |

---

## 3. 调用方与死代码

- **删掉的**：`compat/betterlogin/client/command/ClientLoginCommand.java` —— 客户端命令改写后
  已无人调用（留着就是死代码）。
- **新增后没人用的入口**全部复查过：`EventBus.size()` 用于启动日志、
  `PayloadRegistrar.version()` 用于网络登记日志、`RegisterKeyMappingsEvent.pending()` 由客户端入口消费、
  `CrashReportCallables.activeSections()` 由 `CrashReportMixin` 消费、
  `PacketDistributor.installServerSender` 由客户端入口调用。**没有留下纯摆设**。
- **保持 API 完整但本模组用不到的替身成员**（`InputEvent.Key.isCanceled`、
  `ClientPlayerNetworkEvent.getConnection`、`InteractiveEvent.shouldSwingHand`）保留并写清来由 ——
  它们与 NeoForge 的同名 API 对齐，删掉会让"对照上游"变难。
- `isPickBlock`（中键选取）**故意没接线**：读遍调用链确认它的值只进日志，不改变任何分支。

---

## 4. 线程 / 性能 / 平台

| 关注点 | 判据 | 结论 |
|---|---|---|
| 事件分发开销 | 每帧会走 3 条事件（HUD / 世界渲染 / 帧开始）+ 每刻 1 条 | `CopyOnWriteArrayList` 遍历 + `instanceof`，监听器个位数 ⇒ 可忽略；不用反射分发 |
| 网络线程 | `IPayloadContext.enqueueWork`：已在主线程就地执行，否则投递 | 与 NeoForge 语义一致，不会白白延后一 tick |
| 从工作线程发包 | `PacketDistributor.sendToServer` 不强制主线程（上传/下载都从工作线程发）；异常只记 debug | 与上游 `UploadWindow` 的设计一致 |
| 配置 IO | 只在静态初始化读一次；`save()` 才写盘；失败只打日志 | 不阻塞渲染/网络 |
| Mixin 注入点 | 全部 `@At("HEAD")`（除 `CrashReport` 的 `TAIL`），`defaultRequire: 1` | 注入失败会**大声崩**，不会静默失效 |
| 内存 | 未新增堆上常驻结构；WAV 转换是**流式**（8 KB 缓冲，边读边转） | 大 WAV 不会整段进内存 |
| 平台（Android） | 无新增原生库；WAV 不依赖 JDK 的 `com.sun.media.sound` | 比原来更抗安卓环境差异 |

---

## 5. 顺手修掉的上游 bug（WAV 放不出来）

**现象**：内嵌 javasound-aac 在 classpath 上时，`AudioSystem.getAudioInputStream(InputStream)`
对 WAV 抛 `UnsupportedAudioFileException: Stream of unsupported format`。

**定位过程**（逐个库单加，不靠猜）：

| 只加这个库 | 结果 |
|---|---|
| `mp3spi` | `NoClassDefFoundError: org/tritonus/share/sampled/file/TAudioFileReader` |
| `jlayer` | 正常 |
| `tritonus-share` | 正常 |
| `javasound-aac` | ✗ `UnsupportedAudioFileException`（就是它） |

**影响面**：NeoForge 版把这两个库用 jarJar 嵌进同一个 jar ⇒ 玩家的 `.wav` **一直放不出来**
（MP3/FLAC/AAC 走"直接 new 解码器"那条路，不受影响）。这不是移植引入的。

**修法**：新增 `client/music/WavReader` —— 自己按 RIFF 读头，8/16/24/32 位 PCM 与 32 位 float
统一转 16 位 PCM，**完全不碰 `AudioSystem` 的 provider 搜索**。
**验证**：五种位深都读出准确时长 10.000 s、非 WAV 干净拒绝；`tmp/v28` 由 2 项失败 → **184 项全通过**。
（另外单测确认：同一 classpath 下 `AudioSystem` 的**格式转换** API 没受影响，坏的只有
`getAudioInputStream(InputStream)` 这条 provider 搜索路径。）

**已同步到上游**：NeoForge 线 `27.2.4-snapshot-202` 用了同一份 `WavReader`（逐字节相同），
并在 `tmp/v28` 的 classpath 里**补上内嵌音频库**后跑到 184/184 —— 也就是说以后这条回归能被套件抓住。

---

## 6. 验证记录（可复现）

```bash
cd Source2-Fabric-1.21.1
./gradlew build                     # 编译 + 重映射 + 内嵌库
./gradlew runServer                 # 真实专用服务端起服（本机约 20 秒；起完 Ctrl-C）
./gradlew -q printRuntimeClasspath  # 取 classpath，用来跑上游 tmp/v* 纯逻辑套件
```

- 专用服务端日志要点：`本端=DEDICATED_SERVER`、`网络通道注册完成 (v4, …客户端包 6 个)`、
  `Fabric 网络登记完成 (协议 v4)：C2S 6 个 / S2C 6 个`、`/nmbl server` 已注册、
  `服务端就绪，已载入平面数据（主世界 0 个）`、`服务端出站日上限=10240 MB`。
- 纯逻辑套件：**38 套全通过 / 0 失败**；`v17` 是 NeoForge 专用（import NeoForge 网络类型）编不过，
  它的"dist 隔离"不变量由上面的服务端实跑覆盖。
- 同名包检查（AGENTS §1.3）：6 个内嵌库两两包名交集为空。

---

## 7. 仍然存在的风险（如实列出）

1. **客户端没实机跑过**：本机没有显示环境，`runClient` 走不下去。客户端 5 个 Mixin 的注入点
   已用 `javap` 逐个核对签名（`keyPress`/`onPress`/`onScroll`/`setScreen`/`startAttack`/`startUseItem`/
   `handleChatInput`），并且 `defaultRequire: 1` 保证注入失败会立刻崩而不是静默失效；
   但**"真的进了世界会不会画对"只能靠玩家实测**。
2. **协议版本不协商**：Fabric 没有这一层 ⇒ 两端版本不一致时不会像 NeoForge 那样直接拒绝连接。
3. **`InteractionKeyMappingTriggered` 的触发点略早**：原版 `startUseItem` 前面还有
   `isDestroying`/`isHandsBusy` 两道闸门，我们在 `HEAD` 就发事件。影响：极端情况下会"多触发一次"
   （例如正在破坏方块时右键），但那些情况下处理器本身也会走"没打着控件 ⇒ 放行"。
4. **`PlayerChangedDimensionEvent` 的来源多了"重生"**：Fabric 的 `AFTER_PLAYER_CHANGE_WORLD`
   在重生时也可能触发 ⇒ 多同步一次平面数据（无害，只是多几个包）。
