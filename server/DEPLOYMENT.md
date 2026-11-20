# 内置 TLS 与广播者接入

新装 **0.3.3-p4d** 时填写 `public_address` 并运行 `/svcbroadcast setup`。升级时可以沿用已有的 TLS 配置、身份和设备授权。[快速配置](../README.md#服主快速配置) · [Agent 部署指引](SETUP-AGENT.md)

## 构建与配置

当前支持的服务端构建目标是 Minecraft 1.21.1、NeoForge 21.1.230 或 21.1.251、Simple Voice Chat 2.6.20。安装 Java 21、Python 3.12 和 uv 后，在仓库根目录运行下列命令。

```sh
(cd sender && uv sync --locked)
./server/gradlew -p server test interopTest build
./server/gradlew -p server -Pneo_version=21.1.251 -Pmod_version=0.3.3-p4d-nf251 test interopTest build
```

两个产物分别为 `server/build/libs/svc_broadcast-0.3.3-p4d.jar` 和同目录中的 `svc_broadcast-0.3.3-p4d-nf251.jar`。按服务器的 NeoForge 版本安装其中一个。`interopTest` 用 `sender/.venv` 的 Python 发送器进行回环验证。

配置文件是服务器实例中的 `config/svc-broadcast/tls.properties`。可在首次启动前写入；没有文件时程序生成注释模板并保持回环：

```properties
public_address=play.example.org
```

填写上面一行会为 IPv4 或域名监听 `0.0.0.0`，为裸 IPv6 地址监听 `::`，并使用同一个地址生成证书和游戏配对端点。默认 TCP 24458；只改 `port` 时监听端口和对外端口一起变。`advertised_port` 仅用于不同的外部 TCP 转发端口。地址不带协议、路径、端口或方括号；填写域名时先确认发送者确实能用它访问广播端口。修改后重启，不自动改防火墙。

以下为高级配置或 0.3.0-p4a 的旧配置，可按需精确绑定网卡。IPv6-only 域名使用 `bind_address=::`，另设实际的 `advertised_host`。旧配置保持原义，与 `public_address` 的主机设置二选一：

```properties
bind_address=127.0.0.1
port=24458
advertised_host=127.0.0.1
advertised_port=24458
max_devices=3
```

使用高级配置远程直连时，把示例中的回环地址改为所需绑定地址及发送者可达地址。两种模式都需要服主确认 TCP 入站可达；不要公开旧 TCP 24456。

程序用 Bouncy Castle 1.83 生成 EC P-256 X.509 身份，连接由 JDK TLS 1.2/1.3 完成。无需 Nginx、域名、公网 CA、Certbot 或系统信任库修改。内置默认模式通过游戏配对分发服务器专用 CA；它借鉴 SVC 通过已有游戏连接建立信任的方式，并未复用 SVC 语音密钥或自创加密算法。

私有状态必须随实例备份：

| 路径（相对 `config/svc-broadcast/`） | 用途 |
|---|---|
| `tls/identity.p12` | 专用 CA 及服务证书/私钥，只给服务器账号访问 |
| `tls/identity-created` | 已初始化标记，防止丢失密钥后静默重建身份 |
| `authorization.json` | 全服下线默认、玩家期限/覆盖和设备凭证摘要，不含原始设备凭证 |
| `tls.properties` | 监听地址、对外地址、设备数上限和游戏内配对策略 |
| `token.txt` | 仅回环维护入口的共享令牌，与设备凭证独立 |

新建目录/文件使用 0700/0600 或私有 ACL；状态写入在后台完成，通过同目录原子替换保存。勿共享备份、提交私钥或授权库。CA 有效期十年，服务证书九十天；每小时检查，在不足三十天或地址改变时更新服务证书，信任根保持不变。根过期/损坏不自动重置，先恢复一致的私有备份。确需更换信任根时须由管理员明确安排重新配对；旧发送器拒绝新根。改变服务器地址后也需明确重新配对配置档，不自动跟随重定向。

## 管理员与玩家命令

以下管理员命令要求权限等级 2；控制台省略 `/`。在线玩家可用名字，离线已授权玩家可用已存名字；未知离线玩家需准确 UUID 或先登录。资格和期限按玩家统一，所有设备共享；设备默认最多三台（配置上限可为 1–16）。

```text
/svcbroadcast auth grant <玩家名或UUID> <天数1–36500|permanent>
/svcbroadcast auth expiry <玩家名或UUID> <天数1–36500|permanent>
/svcbroadcast auth devices <玩家名或UUID>
/svcbroadcast auth revoke-device <玩家名或UUID> <设备UUID>
/svcbroadcast auth revoke <玩家名或UUID>
/svcbroadcast auth default-logout <stop|allow>
/svcbroadcast auth policy <玩家名或UUID> <inherit|stop|allow>
```

`grant`/`expiry` 的天数从执行时起算，更新期限保留现有设备和覆盖策略；已到期的资格可延长。`revoke` 撤销整名玩家及全部设备；再次授权不会复活被撤销的旧凭证。`revoke-device` 只撤销一台。配对码仅在内存中保存摘要，五分钟、一次使用、每人一个；重启使未兑换的配对码失效。

新安装全服默认为 `stop`，玩家默认为 `inherit`。`stop` 要求 Minecraft 角色在线，离线开播被拒绝；退出结束会话，重登不自动恢复。`allow` 允许离线开播和继续，发送器及凭证仍须有效。在线状态独立于 SVC/麦克风开关。在线快照每 5 tick 刷新，授权检查每 100 ms；正常调度下撤销、到期及下线/策略变更在一秒内终止不再合规的会话，服务器停顿无法作墙钟保证。

### 游戏内配对策略

默认要求 Minecraft 正版认证。从 0.3.3-p4d 起，服主可在 `config/svc-broadcast/tls.properties` 加入下列设置并重启，让 `online-mode=false` 的服务器也能游戏内配对。

```properties
allow_offline_pairing=true
```

开启后，配对使用游戏服务器给当前连接分配的 UUID。若服务器只凭用户名允许登录，冒用已获授权的用户名可以获得该身份的配对码。请在你信任游戏登录链路和玩家身份的环境中启用。HTTPS 兑换和 WSS 广播继续使用原有 TLS。

玩家可运行 `/svcsender pair`，也可用 `/svcsender start` 在首次开播时自动配对。已有匹配档会复用。旧客户端可运行 `/svcbroadcast pair` 后，把复制的信息粘贴到发送器隐藏输入中。[客户端操作说明](../client/README.md)

如果切换登录模式后 UUID 变了，等玩家用当前身份登录，再运行 `/svcbroadcast auth grant <玩家名> <天数|permanent>` 授权，然后重新配对。开始广播时选用新身份的配对档，服务端才能在本场广播排除本人回传。把设置改回 `false` 会恢复原配对策略，若要停用已发放的凭证，可用 `auth revoke-device` 撤销。

设备入口绑定服务端确认的 UUID，会话期间自动排除本人音乐回传，保持原有收听偏好；旧共享令牌会话不具备这种身份绑定。音乐暂停仍持续发送有效静音；恢复播放无需重新开播。单活跃会话，不抢占。

## 协议与日常恢复

- `POST /v1/pair`：仅内置 TLS，严格 JSON、小请求、连接/速率限制，持久化设备摘要后返回独立凭证。不支持浏览器 Origin 或重定向。
- `wss://<地址>:<端口>/v1/device/audio`：只接受设备 Bearer 凭证，继续使用 v1 PCM 帧格式。
- `ws://127.0.0.1:24456/v1/audio`：维护共享令牌，不在 TLS 公网端提供。轮换旧令牌不影响设备凭证。

`/svcbroadcast setup` 检查本机配对入口，给出配置、绑定、身份或授权库的下一步；`/svcbroadcast status` 检查运行与音频计数。身份损坏不删库“试一下”，先从私有备份恢复。连接失败先验证证书和资格/在线策略，再检查音源；OBS 无电平不靠关闭 TLS 或调整系统音量处理。发送器故障明确退出，不默认循环重试、自启或追播积压。

## 升级与回退

1. 确认实际软件版本、在线玩家、启停方式和广播端口。停播并正常保存停服，私下备份最新实例、原附属与 `config/svc-broadcast/`。校验备份可读。
2. 在 `mods/` 替换本附属，按部署方式启动。服务端入站沿用游戏与 SVC 配置，广播 TCP 端口按需放行，维护端口继续只监听回环。
3. 用 `/svcbroadcast setup` 确认 `READY`。由测试玩家在游戏中配对并短测音乐，确认另一位 SVC 玩家可以收听并正常语音交流。
4. 回退时正常保存停服，把上一版附属及所需的原配置放回原位，再核对游戏、SVC 和广播入口。保留最新存档、TLS 身份与授权。
