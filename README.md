# SVC Desktop Broadcast

[简体中文](README.zh-CN.md)

SVC Desktop Broadcast is a **NeoForge add-on for Simple Voice Chat (SVC)** that lets you share music playing on your computer with other Minecraft players.

The sender captures audio from the apps you select and streams it to the server over an encrypted WebSocket (WSS). The server add-on uses SVC's audio API to deliver the music over a separate channel with its own **Desktop Music** volume category. Players can adjust the music volume or opt out while continuing to use SVC voice chat.

The project currently supports **Minecraft 1.21.1 on NeoForge only**. Support for Fabric and other Minecraft versions may be added in the future.

| User role | Installation requirements |
|---|---|
| Server admin | Minecraft 1.21.1, NeoForge 21.1.230 or 21.1.251, Simple Voice Chat 2.6.20, and server add-on 0.3.3-p4d |
| Broadcaster | Simple Voice Chat and a sender. The in-game sender 0.1.4-p4d is recommended. |
| Listener | Simple Voice Chat |

The same sender JAR supports Windows 11 x64 and offers preview support for Apple Silicon Macs running macOS 14.2 or later. The in-game sender targets Minecraft 1.21.1 and NeoForge 21.1.x starting at 21.1.230.

This is a source preview. This project is evolving rapidly, and its contents, dependencies, and implementation may change at any time. Before deploying it on the public internet, review the notes on bundled dependencies, especially the server's Netty HTTP module. [Third-party dependencies and licenses](THIRD_PARTY_NOTICES.md)

## Server setup

1. Put the server add-on JAR that matches your server's NeoForge version into `mods/`. If you're starting from source, follow the [server build instructions](server/DEPLOYMENT.md#构建与配置).
2. Set `public_address` in `config/svc-broadcast/tls.properties` to an IP address or hostname broadcasters can reach. Enter the address without a protocol, port, or path.

   ```properties
   public_address=play.example.org
   ```

3. Allow inbound TCP connections from broadcasters on port 24458 and start the server. Run `/svcbroadcast setup` as an admin and look for `READY`. The add-on manages its own TLS identity. [Advanced configuration, upgrades, and rollback](server/DEPLOYMENT.md)
4. Grant access to the broadcaster. For example, `/svcbroadcast auth grant PlayerName 7` gives the named online player permission to broadcast for seven days.

## Start a broadcast

1. Put the cross-platform sender JAR in your Minecraft instance's `mods/` folder. To build it from source, follow the [sender packaging instructions](client/README.md#开发构建).
2. Join the server and start playback in the app you want to share. In Minecraft chat, run `/svcsender apps`, then run `/svcsender select 1` using the app's actual number from the list.
3. Run `/svcsender start`. The sender reuses a matching pairing profile or pairs with the server on first use. Run `/svcsender status` to check the broadcast and `/svcsender stop` to stop it.

The first command that needs the bundled sender automatically extracts the runtime for your platform. You can also broadcast from the standalone command-line sender. Both follow the server's broadcaster permissions and logout policy. [Standalone sender guide](sender/README.md)

Listeners can adjust **Desktop Music** in Simple Voice Chat's volume settings. Use `/svcbroadcast listen off` to opt out and `/svcbroadcast listen on` to resume listening.

## Build and test

Install Java 21, Python 3.12, and uv. Run these commands from the repository root. The first build resolves the tools and dependencies. The default client build uses an external sender. The packaging instructions above cover the JAR with its sender bundled.

```sh
(cd sender && uv sync --locked && uv run --locked pytest)
./server/gradlew -p server test interopTest build
./server/gradlew -p client test build
```

The detailed guides and roadmap are in Chinese. [In-game commands](client/README.md) · [Deployment](server/DEPLOYMENT.md) · [Deployment guide for agents](server/SETUP-AGENT.md) · [Roadmap](ROADMAP.md)

AI tools were used during development to assist with writing code and documentation.

## License

The project's source code is available under the [MIT License](LICENSE). The Gradle Wrapper, template code, and other third-party components retain their respective licenses. The build scripts generate or download the runtimes and dependency packages.
