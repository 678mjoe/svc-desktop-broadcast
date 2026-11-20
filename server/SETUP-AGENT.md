# 让 Agent 配置 SVC 桌面音频广播

可交给 Agent 的任务：**“按这份说明检查我的 Minecraft 实例，准备 Desktop Music 的配置差异和检查步骤。实例目录是 <目录>，发送者使用 <IP 或域名>，授权给 <玩家>、<期限>。”**

### 先做只读检查

1. 确认实例目录、启停方式、Minecraft / NeoForge / SVC 版本、现有附属版本和是否启用正版验证。不要猜实例路径、自动切换登录模式或升级其他模组。
2. 确认发送者实际能访问的 IP 或域名。游戏域名可能经过代理，或依赖 Minecraft 的 SRV 记录；不能只根据游戏地址推断广播 TCP 也走同一路径。确认 TCP 24458 的监听占用、转发和入站范围。已有部署先读取其非秘密配置；不要用默认值覆盖。
3. 给出要用的附属包、配置差异、需要放行的 TCP 端口和回退动作。停服、公开入口和防火墙变更以服主已有授权为准。在改现有实例前，备份当前附属与私有配置。备份留在服主控制的私有位置；无需将身份库、维护令牌、配对串或设备凭证交给 Agent。

### 最短路径与判断

当前服务端附属为 **0.3.3-p4d**。使用[服务端构建步骤](DEPLOYMENT.md#构建与配置)生成适配当前 NeoForge 的 JAR。新实例的最小配置是 `config/svc-broadcast/tls.properties` 中的一行 `public_address=<发送者可访问的 IP 或域名>`。地址不带协议、端口、路径和方括号。默认监听 TCP 24458。修改监听端口时设置 `port`，只有内外端口不同时才另外设置 `advertised_port`。

已经正常工作的 `bind_address` / `advertised_host` 配置直接保留；不要与 `public_address` 混写。精确网卡绑定、IPv6-only 域名或独立内外地址，按[高级配置](DEPLOYMENT.md)处理。始终保持维护 TCP 24456 仅回环。

启动后，管理员运行 `/svcbroadcast setup`，控制台省略斜线。状态码在消息开头，命令结果为 1 表示 `READY`，其他状态为 0。它只检查正在运行的服务器，不修改文件、开启广播或访问外网。

| 输出 | 接下来做什么 |
|---|---|
| `READY` | 记录监听端口和发送 URL；从发送端验证该 TCP 端口可达，再由玩家私密配对 |
| `LOCAL_ONLY` | 预期仅本机；如已授权远程使用，核对地址配置并重启 |
| `WAITING_FOR_SVC` / `STARTING` / `STOPPING` | 检查启动阶段和 SVC 是否加载，等实例就绪后再查 |
| `ONLINE_MODE_REQUIRED` | 当前服未启用正版认证，且其配对策略要求正版认证。0.3.3-p4d 起，按服主选择在 `tls.properties` 设置 `allow_offline_pairing=true` 并重启 |
| `CONFIG` / `UPLOAD_BIND` / `MAINTENANCE_BIND` | 依据提示修地址、端口或文件访问；改完配置才重启 |
| `IDENTITY` / `AUTH_STORE` / `MAINTENANCE_TOKEN` | 检查私有文件权限或从一致的备份恢复；不要删除文件来重置身份 |

授权使用 `/svcbroadcast auth grant <玩家> <天数|permanent>`，期限按服主指定值填写。装有[游戏内发送器](../client/README.md)的玩家可运行 `/svcsender pair`，预期看到「配对已就绪」。使用独立发送器的玩家在游戏中执行 `/svcbroadcast pair`，将本人收到的配对信息直接粘贴到发送器隐藏输入，预期看到 `Device paired.`。配对可独立验收。Agent 收集成功结果或不含凭证的错误提示。如果还需确认收听，请让实际听众在测试广播期间反馈。

旧附属没有 `setup` 时，用 `/svcbroadcast status` 与启动日志，按旧配置步骤核对。回退只恢复附属和必要配置，不覆盖玩家最新存档，也不重置私有身份或设备授权。
