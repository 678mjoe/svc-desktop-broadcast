# Windows 11 x64 独立发送器

本说明适用于免安装 ZIP。游戏内开播请按仓库的客户端说明安装双平台 JAR。两种方式均可使用保存在同一 Windows 用户下的配对档。

## 解压与自检

1. 核对下载来源与配套 SHA-256。PowerShell 可用 `Get-FileHash .\sender.zip -Algorithm SHA256`，把 `sender.zip` 换成实际文件名。
2. 将整个 ZIP 解压到普通用户可写的目录，再双击 `start.cmd`。选择 **1 自检**。将检查内置 Python、依赖与私有文件权限。
3. 如果启动或依赖检查失败，保留实际错误码以及对应自检日志。

## 配对

1. 请服主确认 `/svcbroadcast setup` 显示 `READY`，并为你的游戏身份授权。游戏内运行 `/svcbroadcast pair`，点击私密复制组件。配对信息五分钟内有效。
2. 在解压目录打开 PowerShell，执行 `.\send.cmd pair --name my-server --label Windows`。向隐藏输入粘贴并回车，预期看到 `Device paired.`。也可使用菜单 **3 游戏配对**。
3. 执行 `.\send.cmd profiles list` 或查看菜单 **7**，确认本地档名。现有可用档可继续使用。

配对档保存在 `%LOCALAPPDATA%\svc-broadcast\profiles`。资格期限和设备数由服主管理。若收到 `pairing_rejected`，检查广播资格、设备名额与配对码有效性。确需删除本地旧档时，可显式执行 `.\send.cmd profiles remove my-server`。删除本地文件不会撤销服务端设备。

## 原生应用采集

有原生 WASAPI 助手的 ZIP 会显示菜单 **8 原生应用音源检查**和 **9 原生应用音源直播**。先让播放器发声。选择 8 并按列表编号选择 1–4 个应用，有「活动音频会话」时优先选对应条目。看到本机电平后，有 SVC 听众在场时再用 9 开播并选配对档。详细步骤见 ZIP 内 `README-P4C.txt`。

需要测试服务器连接时，可以向测试听众播放一秒合成音。

```powershell
.\send.cmd tone --profile my-server --seconds 1
```

上述命令会直接发送测试音。设备配对会话会排除向发送者本人回传音乐，请其他在线 SVC 玩家确认听感。按 Ctrl+C 停播。

## 虚拟输入（可选）

已有 OBS 或虚拟音频设备时，可把 OBS 的**指定应用音频源**监听到虚拟声卡。以 VB-CABLE 为例，OBS 监听选 **CABLE Input**，发送器读取 **CABLE Output**。继续用原来的耳机与物理麦克风进行普通游戏语音。[OBS 应用音频捕获](https://obsproject.com/kb/application-audio-capture-guide) · [VB-CABLE](https://vb-audio.com/Cable/)

用菜单 **2** 获取设备和后端名，再用 **4** 在本机检查信号。使用自定义配对档时，在终端显式选择档案和设备。以下名称是示例，以本机枚举结果为准。

```powershell
.\send.cmd capture --profile my-server --device "CABLE Output (VB-Audio Virtual Cable)" --host-api "Windows WASAPI" --sample-rate 48000 --channels 2
```

开播后暂停播放器会保留会话，恢复播放即可继续。遇到无声或采集故障，请提供操作步骤、版本、错误码和经过检查的 `Capture diagnostics` 计数。配对信息和配置档留在本机。
