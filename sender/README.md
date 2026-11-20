# 独立发送器

日常开播推荐使用[游戏内双平台发送器](../client/README.md)。独立发送器适合终端操作、音源诊断，以及服主允许离线广播时持续运行。

从源码运行时，在 `sender/` 使用 Python 3.12 和 uv，先执行 `uv sync --locked`。原生应用采集使用 Windows 11 x64 的 WASAPI 或 Apple Silicon macOS 的 Core Audio Tap。需要构建原生助手或查电平时，参见[原生采集说明](native/README.md)。Windows 便携 ZIP 的使用说明另见 [Windows 独立发送器](windows/README-WINDOWS.md)。

## 检查虚拟输入（可选）

1. 要使用 OBS 和虚拟声卡，可先让播放器发声，再打开 OBS。
2. 在独立场景中只添加指定播放器的应用音频源，把该音源的监听输出设为已安装的虚拟声卡。下面以 Mac 的 BlackHole 2ch 为例。
3. 在 `sender/` 目录运行以下命令。`devices` 只枚举设备；`check-input` 明确打开所选输入，在本机检查，不上传、不回放、不保存音频，也不需要服务器令牌。

```sh
uv run svc-broadcast-send devices
uv run svc-broadcast-send check-input \
  --device "BlackHole 2ch" --host-api "Core Audio" \
  --sample-rate 48000 --channels 2 --gain-db 0 --seconds 10
```

`--gain-db 0` 表示发送器不额外放大。设备名与后端必须精确匹配枚举结果。检查时长须大于 0、最多 60 秒。按 Ctrl+C 可提前关闭输入。播放时请观察电平是否有信号，静音也可以产生可读取的帧。

## 如何判断无声

| 观察 | 如何继续 |
|---|---|
| 播放器暂停，`peak=-inf`，`callbacks` 和 `frames_read` 持续增长 | 这是有效静音，直接恢复播放器 |
| 播放器在播，OBS 没有电平，检查仍有回调但全静音 | 检查 OBS 是否绑定了正在播放的应用 |
| 播放器在播、OBS 有电平，虚拟输入却静音 | 核对该音源的监听配置及虚拟声卡输出 |
| `capture_timeout`，`callbacks=0` | 本次启动没有收到设备回调；保留诊断，检查选定设备，不自动切换输入 |
| `capture_timeout`，已有回调 | 对照 `last_callback_age_ms`、`max_callback_gap_ms`、`max_backend_age_ms` 和 `stale_blocks`，区分回调停顿与帧过期；仅凭这个错误不能确定 OBS、系统调度或设备根因 |
| 本机检查有音乐，远端听不到 | 再检查发送器、连接/服务端状态、听众 SVC 连接和独立音乐音量；本机检查不验证服务端播放 |

OBS 的应用音频源在播放器重启后仍全静音时，可保持播放器正在播放，在音源属性里选择「重新开始采集」后复查。

## 游戏配对与配置档

首次信任来自你确认连接正确的 Minecraft 服务器。游戏内配对策略由服主控制，0.3.3-p4d 起可按需在离线模式下启用。[服务端配对策略](../server/DEPLOYMENT.md#游戏内配对策略)

1. 管理员用 `/svcbroadcast auth grant 玩家名 30` 授予 30 天资格，或以 `permanent` 长期授权。玩家需要在线才能首次按名字识别；管理员也可指定准确 UUID。
2. 玩家在游戏中执行 `/svcbroadcast pair`，点击仅自己可见的复制组件。配对信息五分钟有效、一次使用；再执行命令会使上一个配对码失效。
3. 在 `sender/` 的交互终端运行：

```sh
uv run svc-broadcast-send pair --name my-server --label "My Mac"
```

按隐藏输入提示粘贴并回车；看到 `Device paired.` 就完成，不用先配置音源。粘贴时不会回显；不要把配对信息放进命令参数、普通聊天、管道、截图或日志。如果旧版 Mac 发送器在粘贴后一直停在提示处，按 Ctrl+C 退出，换用包含长配对串修复的版本，再复制新的配对信息。配对只交换设备凭证，不打开音频设备。每人默认最多三台设备，资格到期时间由管理员按玩家统一管理。兑换成功但响应/本地保存失败时，可能已占用设备名额；请管理员查看 `auth devices` 并撤销该设备，再重新配对。

```sh
uv run svc-broadcast-send profiles list
uv run svc-broadcast-send profiles remove my-server
```

删除本地配置档不会撤销服务器资格/凭证；遗失设备应让管理员执行 `auth revoke-device`。配置档同时保存服务器地址、专用 CA 与设备凭证。只对该配置档信任该 CA，检查目标地址及证书有效期，不使用系统 CA 兜底、不写入系统信任库。不要为“修复”证书错误关闭校验或直接覆盖配置档；先请管理员检查身份文件、服务器地址与系统时间。

默认位置为 macOS `~/Library/Application Support/svc-broadcast/profiles/`、Windows `%LOCALAPPDATA%/svc-broadcast/profiles/`、Linux `~/.config/svc-broadcast/profiles/`。POSIX 目录/文件使用 0700/0600，Windows 使用当前用户专用 DACL。无法设置私有权限时失败。需要其他位置可在子命令前指定 `--profiles-dir`，请选择私有目录。

## 显式开播与停止

原生应用采集先用 `apps` 找到播放器，再按[原生采集说明](native/README.md)运行 `check-apps` 和 `broadcast-apps`。如果采用虚拟输入，先通过本机输入检查，再使用已配对的配置档。

```sh
uv run svc-broadcast-send capture --profile my-server \
  --device "BlackHole 2ch" --host-api "Core Audio" \
  --sample-rate 48000 --channels 2 --gain-db 0
```

使用 `--profile` 时不能混用 `--url`、`--token-file` 或 `--ca-file`。有限测试音可使用 `tone --profile my-server --seconds 1`，它会向服务器广播，须先安排听众与测试窗口。

新安装默认要求广播者的 Minecraft 角色在线；离线开播被拒绝，下线结束会话，重新登录不会自动重播。管理员可改全服默认或按玩家覆盖为允许离线，且与 SVC 麦克风静音、语音键和语音开关无关。允许离线也要求发送器仍运行，资格及设备凭证有效。到期、撤销和在线策略变化会结束不再符合要求的会话；无自动重试。

配对会话自动排除绑定 UUID 本人的广播音乐，首帧即生效，停播/断线后排除随会话解除；原有 `listen off/on` 偏好不变。此前手动关闭收听的玩家仍保持关闭，可自行 `/svcbroadcast listen on` 恢复正常偏好。其他玩家的音乐与正常语音保持独立。全服只允许一个活跃广播，后来的发送器不能抢占。

### 管理员维护旧入口

以下命令连接本机测试服。维护共享令牌仅用于回环入口。远程广播请使用游戏配对和 WSS。

```sh
uv run svc-broadcast-send \
  --token-file ../server/run/config/svc-broadcast/token.txt \
  capture --device "BlackHole 2ch" --host-api "Core Audio" \
  --sample-rate 48000 --channels 2 --gain-db 0
```

令牌仅通过文件提供，不将令牌内容写入命令参数、URL、日志或截图。远程直连必须使用验证证书的 WSS；未部署入口时不可用明文公网 WS 替代。

开播后持续读取所选输入，暂停或恢复播放器时可沿用同一场广播。按 Ctrl+C 停播并释放输入。服务器停止、连接失败或设备采集异常也会结束会话。使用错误前后的电平和最终 `Capture diagnostics` 定位问题，再手动开播。

独立发送器在终端前台运行，关闭终端可能中断广播。

旧共享令牌入口没有可信玩家绑定，测试时仍需自行避免本人双响。自动排除仅适用于新的设备凭证入口。服务端部署与管理员命令见 [部署说明](../server/DEPLOYMENT.md)。
