# SVC 桌面音频广播

在 Minecraft 中与其他玩家一起听你电脑上正在播放的音乐。发送器采集明确选中的应用，并通过加密连接把音频传给服务器。玩家用 Simple Voice Chat 收听，可单独调节 **Desktop Music** 音量或退出广播，同时照常语音聊天。

| 谁 | 需要什么 |
|---|---|
| 服主 | Minecraft 1.21.1、NeoForge 21.1.230 或 21.1.251、Simple Voice Chat 2.6.20、本项目服务端附属 0.3.3-p4d |
| 广播者 | Simple Voice Chat 与发送器；推荐安装游戏内双平台发送器 0.1.4-p4d |
| 听众 | Simple Voice Chat |

同一个双平台发送器 JAR 支持 Windows 11 x64 和 Apple Silicon macOS 14.2+。游戏内发送器面向 Minecraft 1.21.1 和 NeoForge 21.1.230 起的 21.1.x，已在 21.1.230 和 21.1.251 验证启动。Windows 真机已有可用反馈。Mac 已验证包内发送器启动、配对档查询和应用枚举，游戏内真实原生音乐广播待验。

此版本以源码预览为主。对公网部署前请查阅依赖发布说明，尤其是服务端内嵌的 Netty HTTP 模块。[第三方依赖与许可](THIRD_PARTY_NOTICES.md)

## 服主快速配置

1. 将适配服务器 NeoForge 版本的附属 JAR 放进 `mods/`。如果手头只有源码，先按[服务端构建步骤](server/DEPLOYMENT.md#构建与配置)生成 JAR。
2. 在 `config/svc-broadcast/tls.properties` 中设置发送者可访问的 IP 或域名。将示例值替换为实际地址，地址不带协议、端口或路径。

   ```properties
   public_address=play.example.org
   ```

3. 放行发送者所需的 TCP 24458 入站连接并启动服务器。管理员执行 `/svcbroadcast setup`，期待显示 `READY`。程序会管理自己的 TLS 身份。详见[高级配置、升级与回退](server/DEPLOYMENT.md)。
4. 授予广播资格。例如，对在线玩家使用 `/svcbroadcast auth grant PlayerName 7`，表示自执行时起授权七天。

## 广播者快速开始

1. 将双平台客户端 JAR 放进 Minecraft 实例的 `mods/`。源码仓库可按[双平台 JAR 构建步骤](client/README.md#开发构建)生成该文件。
2. 进服后让播放器发声。在聊天栏输入 `/svcsender apps`，再输入 `/svcsender select 1`，把 `1` 换成应用列表的实际编号。
3. 输入 `/svcsender start`。客户端会复用对应的配对档或完成首次配对。用 `/svcsender status` 查看状态，用 `/svcsender stop` 停播。

首次需要发送器的命令会自动解包本机运行时。独立终端发送器也可以开播，详见[发送器说明](sender/README.md)。两种方式都沿用服主配置的授权和下线策略。

听众可在 SVC 的音量设置中调整 **Desktop Music**，也可用 `/svcbroadcast listen off` 或 `/svcbroadcast listen on` 退出或恢复收听。

## 开发与验证

安装 Java 21、Python 3.12 和 uv。在仓库根目录执行下列命令，首次构建会解析构建工具与依赖。

```sh
(cd sender && uv sync --locked && uv run --locked pytest)
./server/gradlew -p server test interopTest build
./server/gradlew -p client test build
```

[广播命令](client/README.md) · [部署指引](server/DEPLOYMENT.md) · [Agent 部署指引](server/SETUP-AGENT.md) · [路线图](ROADMAP.md)

## 许可

项目源码采用 [MIT 许可](LICENSE)。Gradle Wrapper、模板代码和其他第三方组件继续适用各自的许可证。源码仓库包含构建脚本，运行时与依赖包由这些脚本生成或下载。
