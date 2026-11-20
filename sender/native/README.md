# 原生音源检查与开播

两个原型可选择 1–4 个不同的音源进程，在终端显示每秒峰值和采样计数。检查时间为 1–120 秒，可用 Ctrl+C 提前结束。整个检查在本机内存中计算电平，无需 OBS、虚拟声卡、配对或连接服务器。

## macOS 原生采集与开播

Apple Silicon 上用 Xcode 构建。目标系统为 macOS 14.2 或更新版本。程序使用私有 Core Audio Tap 和临时私有聚合设备，本地播放照常走原输出。[Apple Core Audio Tap 示例](https://developer.apple.com/documentation/coreaudio/capturing-system-audio-with-core-audio-taps)

在项目根目录构建助手，再进入 `sender/`。先让播放器播放，运行 `apps` 找到对应名称与 PID。

```sh
sh sender/native/build.sh macos
cd sender
uv sync --locked
uv run svc-broadcast-send apps
```

用当次枚举的 PID 替换下列示例。每个 `--pid` 指定一个进程，可以选 1–4 个。先跑本机电平检查。

```sh
uv run svc-broadcast-send check-apps --pid 12345 --pid 23456 --seconds 10
```

确认电平后，用已配对的配置档开播。服务器接受会话后才启动采集。按 Ctrl+C 停播。Mac 实际直播验收安排在后续测试。

```sh
uv run svc-broadcast-send broadcast-apps --profile my-server --pid 12345 --pid 23456
```

首次使用时 macOS 可能请求系统音频采集权限，由用户决定是否授权。原生路径支持 44.1 和 48 kHz 立体声 Tap。播放器退出后请重新枚举和选择。

可以从项目根目录直接运行 `"dist/SVC Audio Check.app/Contents/MacOS/svc-audio-check" check 12345,23456 30` 对照转换前的 Tap 电平。

## Windows 11 x64

独立 Windows 发送器可直接选应用及已有的游戏配对档。用下列命令从项目根目录生成 ZIP。首次构建需要下载依赖。[直播步骤](LIVE-WINDOWS.md)

```sh
sh sender/native/build.sh windows
(cd sender && uv sync --locked)
sender/.venv/bin/python sender/windows/build_bundle.py --wasapi-exe dist/svc-wasapi-check.exe
```

以下步骤也可单独检查每个进程树的电平。

从 Mac 用 Zig 0.16 构建，Windows 上直接使用 `svc-wasapi-check.exe`。原型使用 WASAPI 进程回环，选中的根进程及其子进程均纳入检查。[Microsoft 应用回环示例](https://learn.microsoft.com/en-us/samples/microsoft/windows-classic-samples/applicationloopbackaudio-sample/)

在项目根目录执行 `sh sender/native/build.sh windows-package` 可在 `dist/` 重建测试 ZIP 和 SHA-256。包内含 Windows 专用步骤与结果表。[Windows 测试步骤](README-WINDOWS.md)

1. 打开网易云音乐并播放。在任务管理器的「进程」展开网易云音乐，右键主进程并选择「转到详细信息」，记下 PID。关闭 OBS 或保持其现状均可，本检查独立读取应用进程。
2. 在 EXE 所在目录打开 PowerShell，以普通用户运行下列命令。将 `12345` 替换为实际 PID。开头的 `Target` 显示所选进程的文件名。

   ```powershell
   .\svc-wasapi-check.exe check 12345 30
   ```

同时检查两个应用时，填入两个独立根进程的 PID。一个根进程已经包含它的子进程，重复选择会被拒绝。Windows 本机原型并行采集，每秒按 PID 报告各自的电平，混音会在接入发送器时完成。

```powershell
.\svc-wasapi-check.exe check "12345,23456" 30
```

## 验收步骤

1. 保持网易云音乐播放约 5 秒，确认连续出现 `SIGNAL` 且电平非零。
2. 暂停网易云音乐约 5 秒。保持同一次检查运行，让另一个应用播放声音约 5 秒，观察所选音源是否仍为 `SILENT` 或 `NO_SAMPLES`。
3. 恢复网易云音乐，确认同一次检查重新出现 `SIGNAL`。最后按 Ctrl+C，确认返回终端。另开一轮并退出网易云，检查目标退出时程序能结束。

`SILENT` 表示收到零电平的样本，`NO_SAMPLES` 表示该秒没有样本。连续播放期间始终为零时，记录所选进程、系统版本和输出状态。没有样本的区间不能单独作为通过依据，恢复播放后应重新收到信号。

多音源检查时，让两个选中应用依次单独播放，再同时播放。Mac 的混合电平应在三个阶段均有信号。Windows 应分别显示两路电平，同时播放时两路均有信号。最后暂停两路，让未选中的第三个应用保持播放，确认电平仍为零。当前原型中，任一选中进程退出会结束这次检查。
