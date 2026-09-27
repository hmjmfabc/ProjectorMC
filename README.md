# 投影仪 / Projector

> **万物皆屏幕！**
> 在 Minecraft 里，把任意平面变成可以贴图、放视频、写字的屏幕。

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green.svg)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.x-orange.svg)

**当前版本：27.1** · 仓库：<https://github.com/hmjmfabc/ProjectorMC>

---

## 这是什么

**投影仪（Projector）** 让你在任意平面（完整方块墙、铁砧顶面、工作台侧面、楼梯面……）上
粘贴图片、播放视频、书写任意字体的文字，并在上面摆放各种特殊控件：
时钟、天气、百分比进度、计时器、排行榜、流程动画、棋类游戏。

字号与图片大小**无极调节**，方向**任意摆放**，操作方式接近 P 图与视频剪辑。

## 主要功能

| 分类 | 内容 |
|---|---|
| 平面 | 任意方块表面（含楼梯等不完整平面）、U 键圈选、平面对话框（命名 / 内容保护 / 转让 / 删除） |
| 控件 | 文本、图片、视频、**音乐**、时钟、天气、百分比进度、计时器、排行榜、棋类游戏 |
| 文字 | 自定义 TTF 字体、无极字号、任意旋转、多行排版、`&4`/`&l` 等格式化代码、`&z` 彩色渐变、`&s..e..` 双色渐变、调色盘 |
| 视频 | **MP4 / MKV / WebM / MOV 等常见格式直接播放**（装了 WaterMedia 时原画质播放；没装则用内置**纯 Java 转换器**转一次，27.1.2 起**不再依赖 ffmpeg**）。`.mjpg` / ZIP 帧序列始终可直接播放 |
| 音乐 | 圆角矩形播放条（播放键 + 语音条式波形 + 歌名/歌词 + 时间）；本地音频（MP3 / FLAC / WAV）与**网易云音乐**（关键字搜索或粘 ID / 分享链接）；世界里点播放键即可启停，所有人按距离远近听到，带歌词显示 |
| 流程（时间轴） | 为控件编排出现 / 隐藏 / 移动 / 缩放 / 变色动画，带时间轴编辑器与预览界面 |
| 棋类 | 井字棋、五子棋、象棋、围棋、国际象棋；人机 / 双人 / 斗蛐蛐（AI 自走）；三档 AI |
| 联机 | 服务端权威数据、SHA-1 哈希校验与本地缓存复用、上传下载限速、日出站流量上限、两侧对称的传输日志 |
| 平台 | 兼容 Android 触控启动器（FCL / PojavLauncher）与桌面端；**支持专用服务端（Dedicated Server）** |

完整玩法说明见 **[PROJECTOR_GUIDE.md](PROJECTOR_GUIDE.md)**；历次新增功能与修复见 **[CHANGELOG.md](CHANGELOG.md)**。

## 安装

1. 安装 **NeoForge 21.1.x**（Minecraft **1.21.1**）。
2. 把 `projector-<版本>-all.jar` 放进 `mods/` 文件夹。
   - **必须用 `-all.jar`**：它里面有 Jar-in-Jar 打包的 JCodec（视频转换）与音频解码库
     （音乐控件），缺少它们视频转换与音乐播放不可用。
3. 联机时**客户端与服务端都要安装同一版本**（数据模型是服务端权威）。

## 从源码构建

```bash
git clone https://github.com/hmjmfabc/ProjectorMC.git
cd ProjectorMC
mkdir -p libs            # 音乐控件要的两个解码库（见下）
# 把 jflac-codec-1.5.3-SNAPSHOT.jar 放进 libs/
./gradlew build          # 需要 JDK 21；首次会联网拉取 NeoForge 依赖与 mp3spi
```

音乐控件用到两个第三方解码库：`mp3spi`（含 jlayer / tritonus-share，Gradle 会从
Maven Central 自动下载）与 `jflac-codec`（**不进仓库**，需自行放进 `libs/`，来源见 [NOTICE](NOTICE)）。
缺少它们时**仍然能编译运行**，只是音乐控件无法解码音频。

> ⚠ **不要再把 `javasound-aac`（AAC/M4A 解码）加回来**：它和内置 JCodec 自带的那份
> `net.sourceforge.jaad.*` 是**同名包**，两个 jar-in-jar 库导出同一个 JPMS 包会让
> ModLauncher 在**启动阶段**直接崩（`java.lang.module.ResolutionException`）。
> 因此音频格式只有 **MP3 / FLAC / WAV**。

产物：

```
build/libs/projector-<版本>-all.jar   ← 发布用的完整包（含 JCodec）
build/libs/projector-<版本>.jar       ← 不含依赖，不要用于安装
```

## 版本号规则

| 阶段 | 形式 | 说明 |
|---|---|---|
| 快照版 | `27.x-snapshot-[Build]` | 添加并测试新客户端功能 |
| 预发布版 | `27.x-pre-[Build]` | 测试服务端功能 |
| 备选发布版 | `27.x-rc-[Build]` | 最后的稳定性测试 |
| 正式版 | `27.x` 或 `27.x.y` | 正式发布，不显示 Build 号 |

除正式版外，每构建一次 Build 号 +1。

## 目录结构

```
src/main/java/top/hmjmfabc/projector/   模组源码
  common/    平面模型、画布坐标、控件数据模型、棋类规则、流程数据
  client/    渲染、媒体（缓存 / 下载 / 视频解码 / 转换）、界面、字体
  server/    服务端存储、权限、出站计量、字体核验
  network/   网络包与处理
src/main/resources/                     资源（语言文件、模组图标、天气图标、mods.toml）
  assets/projector/font/                字体目录（**仓库不含字体文件**，见下方说明）
tmp/                                    纯逻辑验证脚本（可直接用 JDK 跑，见下）
logo/                                   模组图标源文件
```

## 关于字体文件（仓库不含）

`src/main/resources/assets/projector/font/` 下的两个字体**不在本仓库中**，因为它们不是本项目的作品：

- `minecraft.ttf`：Minecraft 自带的字体资源（Mojang 资产）；
- `caviar_dreams.ttf`：第三方字体（Caviar Dreams，版权归其作者）。

想从源码构建出与官方发布完全一致的包，请自行把这两个文件放进该目录；
缺少时仍可正常编译运行，文字会退回 Minecraft 自带字体渲染。
官方发布的 `-all.jar` 中已包含它们。

## 验证脚本

项目带一套**纯逻辑验证**（不需要启动游戏）：用真实的 Minecraft + NeoForge 当 classpath，
直接跑生产代码并断言行为。

```bash
bash tmp/run-verify.sh tmp/v6 T6     # 跑单个套件
bash tmp/run-all.sh                  # 跑全部套件
```

> 注意：脚本默认按 Termux/Android 的 Gradle 缓存路径编写，其它环境请按需调整
> `run-verify.sh` 里的缓存路径。

## 许可证

本项目以 **Apache License 2.0** 发布，见 [LICENSE](LICENSE)；第三方组件与资源的归属见 [NOTICE](NOTICE)。

- **代码**：Apache-2.0
- **内置的 JCodec**：FreeBSD（BSD 2-Clause）
- **内置的音频解码库**（只有音乐控件使用）：mp3spi / jlayer / tritonus-share 与
  javasound-aac 为 **GNU LGPL 2.1**（**已移除，见上**），jflac-codec 为 BSD 风格许可；
  它们以**未经修改的独立 jar** 形式嵌在 `META-INF/jarjar/` 里，可以单独替换。
- **与 [WaterMedia](https://modrinth.com/mod/watermedia) 的兼容是「可选运行时互操作」**：
  没有引用、编译或打包它的任何源码/二进制（它是 PolyForm Strict 许可），
  只在检测到它已安装时，按它公开文档里的 API 名字**反射调用**。
- **音乐控件的播放实现参考/移植自 [网络音乐机 Net Music Mod](https://github.com/TartaricAcid/NetMusic)**
  （MIT 许可）。**只有音乐模块引用了该项目**，其余功能均为本项目独立实现。
- **`assets/projector/font/` 下的字体**：版权归各自作者，**不在**本项目的 Apache-2.0 授权范围内；
  再分发前请自行确认其授权条款。
- Minecraft、NeoForge 及其标识归各自所有者；本项目与 Mojang / Microsoft 无关联。

## 鸣谢

使用 **DeepSeek v4.1 Flash** 与 **DeepSeek Harness** 编写。
灵感来源于游戏 *Dancing Line* 与 *Through The Fog* 的关卡内百分比标记。
