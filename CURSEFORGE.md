# Projector

**Everything is a screen.**

Projector turns any flat block surface in Minecraft into a working display. Walls, floors, ceilings,
stair sides, slab tops, anvil surfaces, workstation faces - if a block has a face, you can put text,
images, video, music, a live clock, a scoreboard or a playable board game on it. No special screen
block required: your existing build becomes the screen.

Made for NeoForge 1.21.1 (NeoForge 21.1.x, Java 21). Client and server both need the mod.

---

## Why it feels different

- **Any surface, not just full blocks.** Partial shapes such as stairs, slabs and anvils work; widgets
  are laid out on a canvas measured in 1/16-block units, so a widget edge can sit inside a single block.
- **Everything is adjustable, nothing is snap-to-grid.** Size, position, rotation, opacity and layer are
  continuous sliders with a live preview that uses the same renderer as the world.
- **A shared screen, not a private HUD.** Plane contents live on the server. Everyone standing in front of
  the same wall sees the same text, the same video frame, the same song at the same position and the same
  chess position.
- **Built to survive real servers.** Content protection, delete protection, per-plane naming, creator
  transfer, upload quotas, distance-based syncing and de-duplicated media transfers.

---

## Quick start

1. Install the mod on the client and on the server (use the `-all` jar, see Install below).
2. Look at a wall from up to 48 blocks away and press **U**.
3. The plane under your crosshair highlights and is selected. Press **Esc** to cancel.
4. Press **U** again to open the plane dialog: rename it, add widgets, toggle content protection, or
   transfer/delete the plane.
5. Right-click any widget in the world to open its own editor.

---

## Controls

| Input | What it does |
| --- | --- |
| **U** (default keybind) | Select the plane you are looking at; press again to open the plane dialog. |
| **Esc** | Cancel the current selection. |
| **Right-click a widget** | Open that widget's editor. |
| **Left-click an interactive widget** | Play/pause a video or a song, take the item out of a display frame, operate a web page, make a move on a board. |
| **Click a board** | Play: select a piece, then a legal destination. |
| **I** (default keybind) | Type into a focused web page. |
| **O** (default keybind) | Print the media folder paths to chat. |

Every keybind can be re-bound in Options > Controls.

---

## Widgets

| Widget | What you get |
| --- | --- |
| **Text** | Any size, any angle, any layer. Colours, optional outline, per-line wrapping. Supports Minecraft format codes (`&` for the section sign, `&&` for a literal `&`). |
| **Image** | PNG, JPG/JPEG, BMP, GIF, TGA. Uploaded to the server once, de-duplicated by SHA-1, cached on every client that walks past. |
| **Video** | MP4, WebM, MKV, MOV, HLS and other formats, decoded by the optional WaterMedia mod. In-world play button and scrub bar, per-plane progress shared by everyone. Also accepts online video links (see External services). |
| **Music** | Local audio, or NetEase Cloud Music search by keyword/URL/ID. Playback is server-authoritative, so the whole room hears the same second of the same song. Playlists with order, list-loop, single-loop and shuffle modes arrive in the 27.2.2 series. |
| **Clock** | In-game time. Three styles (simple, spelled-out, stacked), optional title text and an optional period label. |
| **Weather** | Current weather as icon plus text. |
| **Progress** | Two-line percentage widget: a small caption above a large number, with a duration and a reset button. |
| **Timer** | Countdown or stopwatch, with control buttons in the world. |
| **Leaderboard** | Live readout of a scoreboard objective, refreshed from the server's scoreboard. |
| **Board games** | Five games against the built-in AI: tic-tac-toe, gomoku, xiangqi, go (9x9) and chess. Three difficulty levels. |
| **Web page** | Embeds a live web page as a wall surface, using the optional MCEF mod. Windows, macOS and Linux only - see Known limitations. |
| **Display frame** | A "flattened" item frame: shows an item sprite with an outline traced from the item's own texture; items fly in and out as a light orb. Added in the 27.2.2 series. |

---

## Animations

Every widget can be driven by a small timeline: an entrance animation, an exit animation and a loop
animation, each with its own delay and duration, plus drag-to-arrange clips.

- 13 entrance animations, 13 exit animations, 8 loop animations.
- Runs on the plane, so all players see the same animation at the same moment.
- A full-screen preview mode lets you walk around inside the animation while it plays.

---

## Multiplayer, permissions and safety rails

| Role / switch | Effect |
| --- | --- |
| Per-plane **content protection** | Only the creator and admins may add, edit or remove widgets. |
| Per-plane **delete protection** | Non-admin players cannot delete the plane. Only a level-4 operator can toggle it. |
| **Plane naming** | Names appear on the selection frame, and in the dialog. |
| **Creator transfer** | Hand a plane over to any online player. |
| Level **2+** operators | Count as admins for content protection, unless the server raises the bar. |
| Level **4** operators | Manage delete protection and permissions. |

By default any player may delete any plane that is not delete-protected (that is the expected behaviour
for creative and shared-world servers). Every refusal is written to the server log with the reason, so
nothing fails silently.

Planes are synced by distance: a player receives a plane when they come within range and it is removed
again when they leave, so a large world does not push every plane to everyone.

---

## Install

1. Install **NeoForge 21.1.x** for **Minecraft 1.21.1**.
2. Drop the **`-all`** jar into `mods/` on both the client and the server. The `-all` file contains the
   audio and configuration libraries the mod needs; the other jar does not.
3. Start the game. No setup is required.
4. Optional, only if you want more:
   - **WaterMedia** - required for video playback.
   - **WaterMedia Android Bridge** - additional requirement on Android launchers (see Known limitations).
   - **MCEF** - required for the web page widget, desktop operating systems only.

Keep exactly one Projector jar in `mods/`.

---

## Server configuration

A config file is generated at `<world>/serverconfig/projector-common.toml`. Defaults are sensible; these
are the values most servers end up touching.

| Key | Default | Meaning |
| --- | --- | --- |
| `planes.maxPlaneBlocks` | 1024 | Maximum block faces in one plane. |
| `planes.maxPlaneSpan` | 16 | Maximum plane edge, in blocks beyond the first (16 allows up to 33x33). `0` = unlimited. |
| `planes.maxPlanesPerWorld` | 512 | Plane budget per dimension. |
| `planes.selectDistance` | 48 | How far away you can select a plane. |
| `planes.syncDistance` | 512 | How far away a plane is synced to a player. `0` = always. |
| `planes.contentProtectMinLevel` | 2 | Permission level that bypasses content protection. |
| `planes.sameBlockOnly` | false | Restrict plane selection to a single block type. |
| `media.maxImageBytes` | 4 MiB | Per-image upload ceiling. |
| `media.maxVideoBytes` | 64 MiB | Per-video upload ceiling. |
| `media.onlyAdminCanUpload` | false | Restrict uploading to admins. |
| `media.clientCacheMb` | 2048 | Client-side media cache size. |
| `music.hearDistance` | 64 | How far the music widget can be heard. |
| `music.maxConcurrent` | 1 | How many music widgets may play at once. |
| `render.renderDistance` | 128 | How far away planes are drawn. |
| `render.surfaceBias` | 0.02 | Lift off the block face. Raise it if widgets flicker with shaders. |
| `render.backfaceSeeThroughRange` | 0 | How close you must be for a plane to be visible through walls. `0` = never. |
| `online.maxDownloadMb` | 512 | Per-file cap for online video downloads. |
| `online.keepDays` | 7 | How long downloaded online video is kept. |

---

## Compatibility

- **Minecraft 1.21.1 / NeoForge 21.1.x**, Java 21.
- **Iris and OptiFine-style shaders**: supported. Three render switches exist because shader packs treat
  brightness differently - `avoidFullBrightLight`, `realLightForWidgets` and `surfaceBias`.
- **Sodium** and other performance mods: no known conflicts.
- **Android launchers** (FCL, PojavLauncher and similar): supported, including touch controls.
- **Dedicated servers**: plane data is server-authoritative, so single-player, LAN and dedicated servers
  all behave the same way.

---

## Known limitations

These are deliberate design decisions or hard platform limits, not bugs:

- **No built-in video decoder.** Video needs **WaterMedia**. On Android you also need the
  **WaterMedia Android Bridge**, otherwise decoding fails - the mod warns you about this at startup.
- **The web page widget does not work on Android.** MCEF requires a desktop Chromium runtime, so the
  widget reports that it is unavailable instead of pretending to play.
- **One song, one position, for everybody.** Music and video progress are shared on purpose: a plane is a
  public screen, not a private player.
- **Board games are intentionally simplified.** Chess has no en passant and treats a stalemate as a loss;
  go is 9x9 without komi or handicap; gomoku has no forbidden-move rules. Hidden-information games are not
  offered, because everyone sees the same screen.
- **The progress widget uses a font without CJK glyphs**, so keep its caption in English, digits or
  another Latin script.
- **Plane contents are pixel-based, not vector-based.** Very large text at very small plane sizes will
  look chunky, the same as any texture would.

---

## External services

The mod talks to the network only for features you deliberately use, and never sends telemetry:

- **NetEase Cloud Music** public endpoints - search, song details, lyrics and audio links - when you use
  the music widget. A cookie can be configured in `music.neteaseCookie` to raise the range of playable
  songs.
- **Bilibili** - when you paste a Bilibili share link into a video widget, the mod resolves the playable
  stream or downloads the file, honouring the caps in the `online` config section.
- Nothing else. Images, videos and music files you upload live on your own server.

---

## Version status

- **27.2** is the current released line (NeoForge 1.21.1).
- Later builds in the 27.2.2 series are testing builds: the web widget, the display frame configuration
  screen, the music playlist screen and the NetEase login button are the features being polished. Fixed
  details are listed in the changelog in the repository.

---

## Credits and third-party notices

This mod started as a personal project and thanks:

- **Net Music Mod** by TartaricAcid and contributors - the music module is derived from it
  (MIT). See the links at the bottom.
- **WaterMedia** - optional video backend. Not bundled and not linked: it is used only when installed, via
  its publicly documented API.
- **WaterMedia Android Bridge** - optional, Android video decoding. Detected by mod id only; no code from
  it is used.
- **MCEF** - optional web renderer used by the web page widget. Not bundled.
- Bundled helper libraries: jlayer, mp3spi, tritonus-share (LGPL-2.1), jflac (BSD), javasound-aac
  (LGPL-2.1) and night-config (LGPL-3.0). Full notices ship with the mod.
- Minecraft, NeoForge and their logos are trademarks of their respective owners. This project is not
  affiliated with, endorsed by or sponsored by Mojang or Microsoft.

The mod's own code is licensed under **Apache-2.0**.

Written with DeepSeek V4.1 Flash and DeepSeek Harness. Inspired by the in-level percentage markers of
Dancing Line and Through The Fog.

---

## Links

- Source code and full documentation: https://github.com/hmjmfabc/ProjectorMC
- Bug reports and feature requests: https://github.com/hmjmfabc/ProjectorMC/issues
- Net Music Mod, the origin of the music module: https://github.com/TartaricAcid/NetMusic
