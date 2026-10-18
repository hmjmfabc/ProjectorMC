# CHANGELOG — 投影仪 / Projector · Fabric 1.21.1

> 当前：**`27.2.4`（正式版，不带 Build 号）**。
> 版本号规则见上游 `AGENTS.md §1.4`；本线继承 NeoForge 正式版 `27.2.4` 的全部功能，
> 另外包含两项修复（WAV 音频、视频控件红块卡死），这两项**上游也各有一份**（`27.2.4-snapshot-202/203`）。
> 下面按「正式版 → 两个快照构建」倒序排列。

---

## 27.2.4（正式版）

### 这是什么

把 NeoForge 正式版 `27.2.4` 整个模组（164 个业务源码文件 / 48,571 行）移植到 **Fabric 1.21.1**：
**玩法与功能完全一致**，业务逻辑一行未改，只换"加载器那一层"（事件、网络、配置、按键、Mixin 钩子）。

- 12 类控件：文本 / 图片 / 视频 / 音乐 / 网页 / 时钟 / 天气 / 百分比进度 / 计时器 / 排行榜 / 棋类 / 展示框；
- 圈选（U）、取消（Esc）、对话框、内容保护、创建者转让 / 删除、管理员与创建者分级权限；
- 自定义 TTF 字体、`&` 格式化代码、打字动画、流程（时间轴）动画、人机 / 双人 / AI 自走棋类；
- 媒体从 `.minecraft/projector/media` 导入，在线视频可直接粘贴直链；音乐控件含本地音频与网易云；
- 完整支持 Android（触控启动器）与低配设备。

### 新增

- **Fabric 1.21.1 版本本身**（此前只有 NeoForge 版）。
- 6 个音频 / 二维码库以内嵌 jar 随包发布（`META-INF/jars/`），玩家无需另装。

### 修复

- **`.wav` 音频放不出来**（上游同源 bug，上游修于 `27.2.4-snapshot-202`）。

  根因：内嵌的 **javasound-aac** 在 `META-INF/services/` 注册了一个 `AudioFileReader`，
  只要它在 classpath 上，JDK 的 `AudioSystem.getAudioInputStream(InputStream)` 对 WAV 就抛
  `UnsupportedAudioFileException`（逐库单加定位：mp3spi 单独加是 `NoClassDefFoundError`，
  jlayer / tritonus-share 单独加正常）。
  修法：新增 `client/music/WavReader` 自己按 RIFF 读头，8/16/24/32 位 PCM 与 32 位 float
  统一转 16 位 PCM（流式，不碰 `AudioSystem` 的 provider 搜索）。
  验证：五种位深都读出准确时长、非 WAV 干净拒绝；音乐控件套件 `tmp/v28` 由 2 项失败 → 184 项全通过。

- **视频控件的红色「放不了」占位卡死、永远不出画面**（上游同源 bug，上游修于 `27.2.4-snapshot-203`）。

  根因**不是解码器**：玩家在编辑器里逐字打链接时，每敲一个字符都会拿 `https://`、`https://b2`
  这类半截地址去开一次，两次都算"打不开"⇒ 失败计数到上限就把这个控件**永久禁用**，
  之后填完整链接也不再尝试；而编辑器状态行还写着「流式播放（未下载，交给解码器）」，
  两条提示互相矛盾，把玩家引去查解码器。

  修法：半截/不合法地址**不算失败**（新增 `looksOpenable`，要求 http(s) 且 host 像域名或 IP）；
  失败改成**冷却**（5s → 10s → 20s → 40s → 60s 封顶）；**换链接 / 点播放键 / 删了重建**都会立刻复位；
  失败日志带上一次的真实异常；会话超过 3 秒还没出帧会报一次（限流）。
  回归：`tmp/v43`（19 条）上游与本树均通过。

### 与 NeoForge 版的差异（全部在加载器层，功能不变）

1. 配置文件同样是 `config/projector-common.toml`，键名与注释一致（59 个键）。
2. 按键仍是 U / Esc / O / I；原版 `KeyMapping` 没有 NeoForge 的 `IN_GAME` 上下文参数，用原版 4 参数构造（行为等价）。
3. `/nmbl server level set <等级>` 改用普通单词参数，**填错时会列出可选取值**（原来用自定义枚举参数）。
4. `/nmbl login` 行为不变（改用 Fabric 的客户端命令源）。
5. 协议版本号只进日志（Fabric 没有 NeoForge 那样的版本协商，两端版本不一致不会拒绝连接）。
6. 存档数据显式传 `DataFixTypes.LEVEL`（原版 `SavedData.Factory` 只有 3 参构造）。
7. 管理员判定用 access widener 放开 `LocalPlayer#getPermissionLevel`（对应 NeoForge 的补丁）。
8. Mixin 目标名由 Loom 在 `remapJar` 阶段直接改写为 intermediary，因此 jar 里不需要 refmap。

### 验证

- 全量编译 + 重映射通过；真实拉起**专用服务端**起服成功（`本端=DEDICATED_SERVER`、网络 `C2S 6 / S2C 6`、
  平面数据载入、配置读出 10 GB 日出站上限）。
- 上游纯逻辑验证套件：**38 套全通过 / 0 失败**（在 Fabric 类路径上跑；`tmp/v17` 为 NeoForge 专用测试）。
- 6 个内嵌库两两**无同名包**；access widener 与 Mixin 目标在生产 jar 里均已重映射。
- 玩家实测：进世界、圈选、平面渲染、世界里点视频/棋盘、音乐控件、`.wav` 播放、在线视频 均通过。

---

## 27.2.4-snapshot-2（快照，带 Build 号）

> 玩家实测反馈：视频控件的占位块是红色、显示「交给解码器」，永远不出画面。
> 修法与上游 `27.2.4-snapshot-203` 相同，详见上面正式版小节。

## 27.2.4-snapshot-1（快照，带 Build 号）

> Fabric 线的**首个移植构建**：NeoForge 版 `27.2.4` 的全部功能 + 移植层（`compat/` 与 `fabric/`）。
> 另外修掉上游一个真 bug：内嵌 javasound-aac 让 `AudioSystem` 对 WAV 抛异常（见上面正式版小节）。
