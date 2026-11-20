# Minecraft 游戏内广播

Minecraft **1.21.1 / NeoForge**。只有广播者需要装这个客户端模组，听众用 Simple Voice Chat 收听。配合服务端附属 **0.3.3-p4d**，广播者可以在游戏内配对，通过 WSS 上传音频。

## 双平台单 JAR 快速开始

1. 把 **0.1.4-p4d 双平台版** JAR 放进游戏实例的 `mods`，换下旧版发送器客户端 JAR。同一个 JAR 用于 Minecraft **1.21.1** 与 NeoForge **21.1.230 起的 21.1.x**，已在 21.1.230 和 21.1.251 验证启动。
2. 请服主授予广播资格。进服并播放音乐，在游戏内输入 `/svcsender apps`，等首次解包提示结束并列出播放器。按实际编号输入 `/svcsender select <编号>`。
3. 输入 `/svcsender start`。有匹配的配对档会复用，首次开播会在游戏内完成配对。用 `/svcsender stop` 停播。

JAR 内置 Windows 11 x64 和 Apple Silicon macOS 14.2+ 两套发送器。客户端按操作系统和 Java 架构选择，在首次调用发送器时只解包匹配的一套，之后复用缓存。Mac 游戏实例使用原生 ARM64 Java。首次实际采集时，如 macOS 请求系统音频权限，由玩家在系统界面处理。构建脚本使用 ad hoc 签名供本机验证。

| 平台 | 运行时缓存 | 现有配对档 |
|---|---|---|
| Windows | `%LOCALAPPDATA%\svc-broadcast\runtime` | `%LOCALAPPDATA%\svc-broadcast\profiles` |
| macOS | `~/Library/Caches/svc-broadcast/runtime` | `~/Library/Application Support/svc-broadcast/profiles` |

已有的 `config/svc-broadcast-sender.json` 会优先作为自定义启动器。要验证内置版，先把旧 JSON 改名为 `svc-broadcast-sender.json.bak`。

### 内置版试用检查

先在没有自定义 JSON 的实例用 `/svcsender apps` 验证首次解包。重开游戏后再次运行该命令，应直接列出当前应用。用 `/svcsender profiles` 核对旧配对档，再开播、停播各一次。如果没有已有配对档，先用 `/svcsender pair` 验证游戏内配对。退出或开播仍沿用既有服务端策略。

## 自定义发送器

Mac 使用独立发送器时，可按[发送器文档](../sender/README.md)准备 Python 依赖，并在仓库根目录运行 `sh sender/native/build.sh macos`。在游戏实例的 `config/svc-broadcast-sender.json` 指定实际的绝对路径。

```json
{
  "command": ["/Users/you/projects/svc-desktop-broadcast/sender/.venv/bin/python", "-m", "svc_broadcast_sender"]
}
```

macOS 需要允许原生助手访问系统音频。原型会使用所选进程的 Core Audio Tap；首次权限提示由玩家在系统界面处理。

Windows 使用独立发送器或自定义配对档位置时，可以用下例覆盖内置启动器。路径改成独立包的实际路径。

```json
{
  "command": [
    "C:/Games/svc-broadcast-p4d-win11-x64-mc-test2/runtime/python.exe",
    "-I", "-B", "-X", "utf8",
    "C:/Games/svc-broadcast-p4d-win11-x64-mc-test2/app/windows_test.py", "--cli"
  ]
}
```

可先执行 `send.cmd profiles list` 核对 Windows 配对档是否存在。JAR 直接运行上面指定的程序，`send.cmd` 留给手动终端操作。

## 游戏内操作

先登录要广播的服务器，让播放器发声。在 Minecraft 聊天栏输入命令并按回车。配对、选源、开播和停播需要玩家这样提交。

| 游戏内命令 | 用途 |
|---|---|
| `/svcsender apps` | 刷新本机音源，显示编号。Mac 只列出当前正在输出音频的进程。Windows 优先选标为「活动音频」的播放器条目 |
| `/svcsender apps 2` | 音源超过一页时看第 2 页 |
| `/svcsender select 1 3` | 按实际编号选择 1–4 个应用，当前 Minecraft 进程会被排除 |
| `/svcsender pair` | 配对当前服务器，或找到同一玩家已有的配对档。无需复制或粘贴，也不会打开采集 |
| `/svcsender profiles` | 显示当前用户的本机配对档名 |
| `/svcsender start` | 使用当前服务器；首次开播会先自动配对。已有匹配档时直接复用 |
| `/svcsender start my-server` | 用指定配对档显式开播。聊天内会显示连接和采集状态 |
| `/svcsender status` | 查看状态、所选配对档和音源，或最近一次故障 |
| `/svcsender stop` | 停止上传并释放所选应用的采集资源 |

广播时可以暂停播放器，恢复后仍使用同一会话。重新启动播放器后需停止广播、刷新音源并重新选择。游戏进程关闭时，该广播会停止；退出服务器后能否续播由原有服务器策略决定。要关掉游戏仍继续播放，可在服务器允许时使用独立发送器。游戏命令不会自动采集、开播或重试。

游戏内配对默认要求正版认证，0.3.3-p4d 起离线服主也可启用。玩家当前登录的 UUID 必须有有效广播资格，新设备受原有设备数上限约束。切换登录模式导致 UUID 变化时，联系服主授权当前登录身份，再运行 `/svcsender pair`。配对串经游戏连接和本机管道交接，凭证由发送器保存在原有私有目录。若服务器证书身份变化，请先向服主确认，再明确移除对应旧档并重新配对。[服务端配对策略](../server/DEPLOYMENT.md#游戏内配对策略)

旧版服务器仍可用 `/svcbroadcast pair` 复制信息，在独立发送器的终端隐藏输入中完成配对，再用 `/svcsender start <配对档>` 开播。请勿在 Minecraft 聊天栏粘贴配对串。移除客户端 JAR 后，也可继续用这些配对档和独立发送器。

## 开发构建

从源码生成双平台 JAR 需要 Java 21、Python 3.12、uv，以及能运行 Xcode 构建工具和 Zig 0.16 的 Apple Silicon Mac。在项目根目录执行以下命令。首次构建会下载固定依赖。

```sh
(cd sender && uv sync --locked)
sh sender/native/build.sh windows
sender/.venv/bin/python sender/windows/build_bundle.py \
  --work backups/bundle-work \
  --wasapi-exe dist/svc-wasapi-check.exe \
  --minecraft-prototype
sh sender/native/build.sh macos
```

Windows 构建步骤会把从 `uv.lock` 验证过的 SoXR 源码归档下载到新建的 `backups/bundle-work/`，供 Mac 打包共用。Mac 打包使用 Python Build Standalone 的 CPython 3.12.13 `20260718` 构建。如果 uv 提供了其他构建，请从该版本的发布页下载并校验 Apple Silicon 安装包，运行打包脚本时将 `--python` 指向解压得到的 `bin/python3.12`。[固定运行时发布页](https://github.com/astral-sh/python-build-standalone/releases/tag/20260718)

```sh
uv python install cpython-3.12.13-macos-aarch64-none
sender/.venv/bin/python sender/macos/build_bundle.py \
  --python "$(uv python find --managed-python --no-project 3.12.13)" \
  --soxr-source backups/bundle-work/soxr-1.0.0.tar.gz
./server/gradlew -p client \
  -PembeddedSenderZip=../dist/svc-broadcast-p4d-win11-x64-mc-test2.zip \
  -PembeddedMacSenderZip=../dist/svc-broadcast-p4d-macos-arm64.zip test build
```

`client/build/libs/` 中的 JAR 包含两个 ZIP 和各自的 SHA-256。开发时可用 `-Pneo_version` 切换测试环境。省略 ZIP 参数可构建仅连接外置发送器的开发版。升级使用按归档摘要区分的运行时缓存，退出游戏及发送器后可以清理旧缓存。配对档保存在单独目录。
