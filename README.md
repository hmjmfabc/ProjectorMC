# 投影仪 / Projector —— Fabric 1.21.1 版

> 本目录是 **Fabric 1.21.1** 的移植版，源自 NeoForge 版 `27.2.4`（正式版）。
> 当前：**`27.2.4`（正式版，不带 Build 号）**；产物：`build/libs/projector-fabric-1.21.1-27.2.4-all.jar`。
> 覆盖移植线的 snapshot-1 / snapshot-2 两个快照构建，见 `CHANGELOG.md`。
> 模组功能与 NeoForge 版**完全一致**（164 个业务源码文件一行业务逻辑都没改，见下文「移植方式」）。

---

## 1. 玩家怎么装

| 需要 | 说明 |
|---|---|
| Minecraft **1.21.1** | 必须与版本号一致 |
| **Fabric Loader ≥ 0.16** | FCL / PojavLauncher 里选 Fabric 1.21.1 即可 |
| **Fabric API** | 必装（`fabric-api-0.116.x+1.21.1` 或更新） |
| 本模组 jar | **`-all.jar`**（含全部内嵌解码库），放进 `mods/` |
| 可选：WaterMedia | 装了才能播放视频（MP4/WebM/MKV/直播流…） |
| 可选：网页渲染前置 | 装了才能用「网页控件」；Android 上基本不可用（官方 README 明写） |

- **客户端与服务端都要装**本模组（数据模型服务端权威）。
- 安卓上若装了视频前置，还需要它的**安卓兼容层**，否则一旦播视频会崩游戏（日志最前面会有一段 ERROR 提示，这是原模组就有的引导）。
- 配置文件：`config/projector-common.toml`（首次启动自动生成，含 59 个键与注释；键名与 NeoForge 版**完全一致**，改动后重启生效）。

---

## 2. 构建

```bash
cd Source2-Fabric-1.21.1
./gradlew build            # 首次需要联网：Gradle 9.8 + Loom + Minecraft + Fabric API
./gradlew build --offline  # 依赖缓存好之后（日常用这个）
```

- 产物：`build/libs/projector-fabric-1.21.1-<版本>-all.jar`（**交付这个**）。
- `-sources.jar` 只是源码包，玩家不要装。
- 环境：JDK 21 + Gradle 9.8 + Fabric Loom 1.17.21 + **Mojang 官方映射（mojmap）**。
  ⚠ 必须用 mojmap：上游源码全部按 mojmap 书写，换 Yarn 等于把 164 个文件里的每个 MC 类名都改一遍。
- `libs/` 里的 `jflac-codec` / `javasound-aac` 与两个字体 ttf 一样**不入库**，需自行补齐（见仓库 README「从源码构建」）。

---

## 3. 移植方式（给维护者）

**原则：业务代码不动，只换"加载器那一层"。**

```
src/main/java/top/hmjmfabc/projector/
├── (上游 164 个文件的原有包结构，全部照搬)
├── compat/            ← 【新增】NeoForge API 的替身（镜像 NeoForge 的包结构，便于逐文件对照）
│   ├── event/         ← SubscribeEvent / IEventBus / EventBus（极简事件总线）
│   ├── fml/           ← Mod / ModContainer / ModList / FMLEnvironment / FMLPaths /
│   │                     CrashReportCallables / ModConfig / event.lifecycle.*
│   ├── distmarker/    ← Dist / OnlyIn
│   └── neo/           ← NeoForge（事件总线实例）/ common.ModConfigSpec（TOML 读写）/
│                         network.* / event.* / client.event.* / client.settings…
└── fabric/            ← 【新增】Fabric 入口与接线
    ├── ProjectorFabric        主入口（构造 Projector、跑各阶段、登记网络）
    ├── ProjectorFabricClient  客户端入口（构造 ProjectorClient、登记渲染/输入/按键/客户端命令）
    ├── FabricCommonEvents     Fabric 回调 → 模组总线（服务端那一半）
    ├── FabricNetworking       PayloadTypeRegistry / ServerPlayNetworking 登记
    ├── FabricClientNetworking 客户端方向的接收器（**客户端专用类**，dist 隔离）
    ├── Server/ClientPayloadContext  IPayloadContext 的两个实现
    └── mixin/                 NeoForge 的 5 个"钩子事件"用 Mixin 补上（见下表）
```

### NeoForge 事件 → Fabric 对应物

| NeoForge | Fabric |
|---|---|
| `ServerTickEvent.Post` | `ServerTickEvents.END_SERVER_TICK` |
| `ClientTickEvent.Post` | `ClientTickEvents.END_CLIENT_TICK` |
| `ServerStartedEvent` / `ServerStoppingEvent` | `ServerLifecycleEvents.SERVER_STARTED` / `SERVER_STOPPING` |
| `PlayerEvent.PlayerLoggedIn/Out` | `ServerPlayConnectionEvents.JOIN` / `DISCONNECT` |
| `PlayerEvent.PlayerChangedDimensionEvent` | `ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD` |
| `BlockEvent.BreakEvent` | `PlayerBlockBreakEvents.BEFORE`（返回 false = 取消） |
| `RegisterCommandsEvent` | `CommandRegistrationCallback.EVENT` |
| `RegisterClientCommandsEvent` | `ClientCommandRegistrationCallback.EVENT` |
| `ClientPlayerNetworkEvent.LoggingIn/Out` | `ClientPlayConnectionEvents.JOIN` / `DISCONNECT` |
| `RenderLevelStageEvent(AFTER_ENTITIES)` | `WorldRenderEvents.AFTER_ENTITIES` |
| `RenderFrameEvent.Pre` | `WorldRenderEvents.START` |
| `RenderGuiEvent.Post` | `HudRenderCallback` |
| `RegisterKeyMappingsEvent` | `KeyBindingHelper.registerKeyBinding` |
| `InputEvent.Key` / `MouseButton.Pre` / `MouseScrollingEvent` | **Mixin**：`KeyboardHandler.keyPress` / `MouseHandler.onPress` / `MouseHandler.onScroll` |
| `InputEvent.InteractionKeyMappingTriggered` | **Mixin**：`Minecraft.startAttack` / `startUseItem` |
| `ScreenEvent.Opening` | **Mixin**：`Minecraft.setScreen` |
| `ClientChatEvent` | **Mixin**：`ChatScreen.handleChatInput` |
| `FMLCommonSetupEvent` / `FMLClientSetupEvent` | 入口里直接分发（`enqueueWork` = `Minecraft.execute`） |
| `PayloadRegistrar.playToServer/playToClient` | `PayloadTypeRegistry.playC2S/playS2C` + `Server/ClientPlayNetworking.registerGlobalReceiver` |
| `PacketDistributor.sendToPlayer/sendToServer` | `ServerPlayNetworking.send` / `ClientPlayNetworking.send` |
| `ModConfigSpec`（TOML 配置） | `compat.neo.common.ModConfigSpec` 自实现（同键名、同路径、同夹取语义） |
| `accesstransformer.cfg` | `projector.accesswidener`（一一对应） |
| `jarJar` | Loom `include`（嵌进 `META-INF/jars/`，Fabric Loader 会把非模组的嵌套 jar 加入 classpath） |

### 没有 Mixin 就要命的地方（记录一下）

- `startAttack`/`startUseItem` 里 `setSwingHand(false)` 是**只读的**：逐处核对过 10 个调用点，
  全部紧跟 `setCanceled(true)`，而"取消"本身就让原版那一步不发生 ⇒ 挥手自然不会播。
- `isPickBlock` 没接线：它的值只进日志，不改变任何分支（`WebInput.press(...)` 的第 4 个参数）。

---

## 4. 与 NeoForge 版的差异（全部是加载器层，功能不变）

1. **配置文件位置**：两边都是 `config/projector-common.toml`（NeoForge 的 COMMON 配置也是这里），键名与注释一致。
2. **键位冲突上下文**：原版 `KeyMapping` 没有 NeoForge 的 `IN_GAME` 上下文参数 ⇒ 用原版 4 参数构造。
   实际行为等价（世界里能按、界面里不生效），`ClientInputHandler` 本来就有 `mc.screen != null` 保护。
3. **`/nmbl server level set <等级>`**：NeoForge 的 `EnumArgument` 在 Fabric 侧没有等价物（自定义命令参数类型
   必须注册进 `ArgumentTypeInfos` 才能随命令树同步，1.21.1 的注册入口是私有的）⇒ 改用 `StringArgumentType`
   + `suggests` 补全 + 解析失败时 `sendFailure` 列出可选取值。功能与提示都不缺。
4. **`/nmbl login`（客户端命令）**：Fabric 的客户端命令源类型是 `FabricClientCommandSource` ⇒ 用
   `ClientCommandManager.literal(...)` 建树（原来的 `ClientLoginCommand` 因此没人调用，已删除，避免死代码）。
5. **协议版本**：NeoForge 的 `registrar(version)` 会做版本协商；Fabric 没有这一层 ⇒ 版本号只进日志。
   两端版本不一致时不会像 NeoForge 那样拒绝连接。
6. **`SavedData.Factory`**：原版只有 3 参构造（含 `DataFixTypes`）⇒ 三处存档数据显式传
   `DataFixTypes.LEVEL`（Fabric 官方对自定义存档数据的推荐值；对本模组的自定义键无影响）。
7. **`Player#getPermissionLevel`**：原版是 `protected` ⇒ 用 access widener 放开（对应 NeoForge 的补丁）。
8. **`Screen#getMinecraft` / `KeyMapping#getKey` / `KeyMapping#isActiveAndMatches`**：都是 NeoForge 的补丁方法
   ⇒ 换成原版等价写法（`this.minecraft` / `saveString()` / `matches(keyCode, scanCode)`）。
9. **Mixin 目标名**：Loom 1.17 在 `remapJar` 阶段**直接改写**混入类里的字符串目标（实测
   `keyPress→method_1466`、`setScreen→method_1507`、`startAttack→method_1536`、`getDetails→method_555`），
   所以 jar 里**不需要 refmap**，也没有生成。
10. **`IPayloadContext.enqueueWork`**：已经在主线程就地执行，否则投递给主线程（原版语义一致）。

---

## 5. 验证（本轮实际做过什么）

| 验证项 | 结果 |
|---|---|
| 全量编译（214 个 java 文件 / 164 个业务文件） | ✅ `BUILD SUCCESSFUL` |
| **真实启动专用服务端**（`runServer`，带本模组） | ✅ 起服成功（`Done (1.342s)`），日志：`本端=DEDICATED_SERVER`、网络登记 `C2S 6 / S2C 6`、`/nmbl server` 注册、服务端就绪并载入平面数据、日出站上限 10 GB（配置读对了） |
| dist 隔离（公共代码不碰客户端类） | ✅ 服务端实跑证明；静态审计也过（公共入口零 `net.minecraft.client.*` 引用） |
| 配置生成 | ✅ 59 个键 / 8 个段落，键名与 NeoForge 版一致 |
| 事件接线覆盖（26 个监听类型逐一核对） | ✅ 100% 有真实触发点 |
| 上游纯逻辑验证套件（39 个套件目录） | ✅ **38 套全通过 / 0 失败**；`v17` 是 NeoForge 专用（import NeoForge 网络类型）编不过，它的不变量已由上面的"服务端实跑"覆盖 |
| 嵌套库同名包冲突（AGENTS §1.3） | ✅ 6 个内嵌库两两交集为空 |
| access widener 生产重映射 | ✅ jar 内已是 `accesswidener v2 intermediary` |
| Mixin 目标重映射 | ✅ 已核实为 intermediary 名字 |

---

## 5.1 顺手修掉的一个上游 bug（WAV 放不出来）

内嵌的 **javasound-aac** 会在 classpath 上注册一个 `AudioFileReader`；只要它在，
`AudioSystem.getAudioInputStream(InputStream)` 对 **WAV** 就一律抛
`UnsupportedAudioFileException: Stream of unsupported format`。
逐个库单测确认过：只加 mp3spi = `NoClassDefFoundError: TAudioFileReader`，
只加 javasound-aac = 上面那个异常，jlayer / tritonus-share 单独加都正常。

⇒ 也就是说：**音频库跟着模组走的时候（NeoForge 版的 jarJar 同样如此），玩家的 .wav 一直放不出来。**
本移植版改用自带的 `client/music/WavReader`（按 RIFF 规范自己读头，把 8/16/24/32 位 PCM
与 32 位 float 统一转成 16 位 PCM），**完全不碰 `AudioSystem` 的 provider 搜索**。
实测：8/16/24/32 位 PCM 与 32 位 float 五种写法都能读出准确时长（10.000 s），
非 WAV 输入干净拒绝；`tmp/v28`（音乐控件 184 项）在该 classpath 下由 **FAILED 2 项 → 全通过**。

> 这条修复在共享代码里（`client/music/`）：**上游 NeoForge 线已经同步落成 `27.2.4-snapshot-202`**
> （`Source2/build/libs/projector-27.2.4-snapshot-202-all.jar`），两条线的这个文件现在逐字节一致。

## 6. 已知限制 / 等玩家实测

- 视频仍需要外部解码（WaterMedia），网页控件仍需要外部前置 —— 与 NeoForge 版同一取舍。
- 玩家侧还没实机进世界测过；**最需要实测的三件事**：①圈选/渲染与六朝向 ②世界里点视频/棋盘/网页 ③音乐控件（内嵌解码库是否被正确加载）。
- 若音乐控件不出声、日志里出现 `NoClassDefFoundError: javazoom/...`，说明嵌套 jar 没进 classpath ⇒ 需要复核 `fabric.mod.json` 的 `jars` 段（本版已确认有 6 项）。

---

## 7. 许可与鸣谢

- 代码 **Apache-2.0**（`LICENSE`）；内嵌第三方库许可见 `NOTICE`。
- `assets/projector/font/` 的两个 ttf 是第三方资产，不进仓库（只在发布 jar 里）。
- 使用 DeepSeek v4.1 Flash 与 DeepSeek Harness 编写。
  灵感来源于游戏 Dancing Line 与 Through The Fog 的关卡内百分比标记。
- 玩家手册见 `PROJECTOR_GUIDE.md`（与 NeoForge 版共用同一份）。
