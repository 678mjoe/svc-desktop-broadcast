# 第三方许可与发行说明

本项目的原创代码和文档采用 [MIT 许可证](LICENSE)。下列第三方组件按各自上游许可分发。

## 源码仓库

- 服务端的 NeoForge 构建模板改编自 [NeoForge MDK](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle/tree/e93d7f09d4eec12c76d1d7be9bf4eaad0246e9e0)。仓库保留模板的 [MIT 许可和版权声明](server/TEMPLATE_LICENSE.txt)。
- Gradle Wrapper 随源码提供，适用 [Gradle 许可与组件声明](server/gradle/GRADLE_LICENSE.txt)。
- 服务端在构建与测试时获取 Simple Voice Chat 和 NeoForge。服主和玩家通过各自的上游渠道安装这些依赖。[Simple Voice Chat](https://modrepo.de/minecraft/voicechat/overview)、[NeoForge](https://neoforged.net/)

## 二进制包

服务端构建产物嵌入 Bouncy Castle 1.83 和 Netty HTTP 编解码模块。仓库保留 [Bouncy Castle 许可](server/src/main/resources/META-INF/licenses/bouncycastle-1.83.txt)。现用的 `netty-codec-http:4.1.97.Final` JAR 未包含独立的 `LICENSE` 或 `NOTICE` 条目。公开发行服务端 JAR 前，需为实际嵌入版本随包提供对应的上游声明，并评估更新 Netty 与 Minecraft 运行时的兼容性。Netty 公告列出的受影响版本包含当前依赖，服务端也使用了公告所述的 `HttpServerCodec`。[Netty 4.1.97.Final 许可证](https://github.com/netty/netty/blob/netty-4.1.97.Final/LICENSE.txt)、[Netty 4.1.97.Final NOTICE](https://github.com/netty/netty/blob/netty-4.1.97.Final/NOTICE.txt)、[Netty 公告](https://github.com/netty/netty/security/advisories/GHSA-pvjx-v7vp-62vq)

便携发送器包含 [Astral 的 CPython 发行版](https://github.com/astral-sh/python-build-standalone)及 [`sender/uv.lock`](sender/uv.lock) 固定的 Python wheel。试用包自带上游许可证和 `soxr` 1.0.0 的对应源码归档，其中含 `libsoxr` 源码。`soxr` 与 `libsoxr` 采用 LGPL-2.1-or-later，发布相应二进制时需保留许可证和对应源码。[Python-SoXR 1.0.0](https://pypi.org/project/soxr/1.0.0/)

Windows 试用包还包含来自 CPython 与 NumPy 的 Microsoft Visual C++ 运行时文件。公开发行包含 Windows 运行时的 ZIP 或客户端 JAR 前，需核实这些文件及包内 `msvcp140.dll` 别名的再分发条款，并附上项目自身的 MIT 声明。[Windows 第三方声明](sender/windows/THIRD-PARTY-NOTICES.txt)、[Microsoft 再分发条款说明](https://learn.microsoft.com/cpp/windows/redistributing-visual-cpp-files)

首轮 GitHub 发布准备范围为源码和构建文件，便携测试包与现成模组 JAR 另行审查。
