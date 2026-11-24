# Minecraft 游戏内广播

Minecraft **1.21.1 / NeoForge**。只有广播者需要装这个客户端模组，听众用 Simple Voice Chat 收听。配合服务端附属 **0.3.3-p4d**，广播者可以在游戏内配对，通过 WSS 上传音频。

## P4E 双平台试用

1. 把 **0.2.0-p4e 测试版** JAR 放进游戏实例的 `mods`，换下旧版发送器客户端 JAR。同一个 JAR 用于 Minecraft **1.21.1** 与 NeoForge **21.1.230 起的 21.1.x**。
2. 请服主授予广播资格。进服并播放音乐，在游戏内输入 `/svcsender apps`，等首次解包提示结束并列出播放器。按实际编号输入 `/svcsender select <编号>`。
3. 输入 `/svcsender start`。有匹配的配对档会复用，首次开播会在游戏内完成配对。用 `/svcsender stop` 停播。

P4E 的配对、音频处理和上传在 Minecraft 的 Java 21 JVM 中运行。JAR 只封装 Windows 11 x64 和 Apple Silicon macOS 14.2+ 的原生采集助手，首次需要时只解包当前平台的助手。Mac 游戏实例使用原生 ARM64 Java。首次实际采集时，如 macOS 请求系统音频权限，由玩家在系统界面处理。构建脚本使用 ad hoc 签名供试用。实际音乐广播与 SVC 语音共存仍需两端真机确认。

| 平台 | 原生助手缓存 | 现有配对档 |
|---|---|---|
| Windows | `%LOCALAPPDATA%\svc-broadcast\native` | `%LOCALAPPDATA%\svc-broadcast\profiles` |
| macOS | `~/Library/Caches/svc-broadcast/native` | `~/Library/Application Support/svc-broadcast/profiles` |

已有的 `config/svc-broadcast-sender.json` 会优先作为自定义启动器。要试用 P4E 的 Java 路径，先把旧 JSON 改名为 `svc-broadcast-sender.json.bak`。需要回退时可恢复该配置，或单独使用原有 0.1.4-p4d JAR。

### 内置版试用检查

1. 在没有自定义 JSON 的实例执行 `/svcsender apps`。首次会解包本机助手。重开游戏后再次执行，应直接列出应用。
2. 用 `/svcsender profiles` 核对旧档。对未配对且有资格的测试账号执行 `/svcsender pair`，验证游戏内首次配对。Windows 可用 `icacls` 检查新档仅当前用户有访问权限。Mac 在首次采集时检查系统音频授权。
3. 选择播放器后开播，请另一位玩家同时确认音乐与 SVC 语音。暂停音乐几秒再恢复，应沿用同一广播会话。多选时可分别暂停各应用，确认其余所选应用继续播放。
4. 检查进程列表中由游戏启动的采集助手，执行 `/svcsender stop` 后应退出。再开播并关闭游戏，也应结束广播。只退出服务器时沿用服主的下线策略。

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

游戏内配对默认要求正版认证，0.3.3-p4d 起离线服主也可启用。玩家当前登录的 UUID 必须有有效广播资格，新设备受原有设备数上限约束。切换登录模式导致 UUID 变化时，联系服主授权当前登录身份，再运行 `/svcsender pair`。Java 路径通过游戏连接接收配对信息，在 JVM 内完成 HTTPS 兑换，凭证保存在原有私有目录。若服务器证书身份变化，请先向服主确认，再明确移除对应旧档并重新配对。[服务端配对策略](../server/DEPLOYMENT.md#游戏内配对策略)

旧版服务器仍可用 `/svcbroadcast pair` 复制信息，在独立发送器的终端隐藏输入中完成配对，再用 `/svcsender start <配对档>` 开播。请勿在 Minecraft 聊天栏粘贴配对串。移除客户端 JAR 后，也可继续用这些配对档和独立发送器。

## 开发构建

从源码生成 P4E 双平台测试 JAR 需要 Java 21，以及可运行 Xcode 构建工具和 Zig 0.16 的 Apple Silicon Mac。玩家使用成品 JAR 只需要正常的 Minecraft Java 21 环境。

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
sh sender/native/build.sh windows
sh sender/native/build.sh macos
./server/gradlew -p client \
  -PembeddedWindowsHelper=../dist/svc-wasapi-check.exe \
  '-PembeddedMacHelper=../dist/SVC Audio Check.app' p4eRelease
```

成品位于 `client/build/libs/`。`p4eRelease` 要求提供两个助手，普通开发可运行 `./server/gradlew -p client test build`。开发时可用 `-Pneo_version` 切换测试环境。旧 Python 运行时缓存和配对档留在原位置，Java 路径使用独立的原生助手缓存。
