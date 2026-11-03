# 阿里云隔离样本接入记录（2026-11-03）

本记录仅涉及用户授权的独立样本 bridge。新服务器尚未收到手机快照或训练记录，SQLite 控制状态为 epoch 0、builtin，快照数为 0；没有上传正式训练库或私钥。Grok 在旧临时 bridge 的 box 直连成功由用户报告、根代理只读核对，但这不能作为新服务器或托管 connector 的联调结果。

服务器 Ubuntu 22.04，沿用已有 Docker Caddy `sasalele-sales-edge-1` 和销售 API。新增专用非 root 账号 `lianxu-bridge`，版本目录 `/opt/lianxu-bridge/releases/c00e46e`，`current` 指向该版本；独立服务 `lianxu-bridge.service` 监听 `127.0.0.1:18765`，数据库 `/var/lib/lianxu-bridge/sample.sqlite3`。配置和新生成的 agent 测试令牌保存在 `/etc/lianxu-bridge/`，目录 0700、文件 0600；内容不写入仓库或日志。

因原 Caddy 容器位于 Docker bridge 网络而非 host 网络，新增 socket `lianxu-gateway.socket` 只绑定 `172.19.0.1%br-65d9fe5ab4ea:18766`，由以专用账号运行的 `systemd-socket-proxyd` 转发到宿主 loopback；未创建公网监听或防火墙规则。socket 依赖 Docker 服务及该网桥设备；尚未做整机重启或 Docker 网桥重建验证，网桥重建后的自动恢复不能保证。

既有 TLS 站点新增 `handle_path /lianxu/*`，请求体限制 36MB，代理至 Docker 网关并设上游 Host 为 `localhost:18765`。公网手机服务根地址是 `https://xiaoshou.yilihuashi.com/lianxu`，MCP 地址为 `https://xiaoshou.yilihuashi.com/lianxu/mcp/`。Caddyfile 备份为 `/home/admin/sasalele-release-20261014/project/modules/qm-sales-assistant/deploy/Caddyfile.pre-lianxu-20261103`；候选在实际容器内 `caddy validate` 成功。原容器 Caddy 2.11.7 以 `caddy run --config` 启动且 admin off；先用相同精确镜像的隔离容器验证 SIGUSR1 可重载，再对原容器发送 SIGUSR1，未重建或停止该容器。

核查时直接请求 HTTPS：POST `/lianxu/phone/control` 未认证为 401、owner 认证为 200；GET 此路径为 405（只支持 POST）；`/lianxu/mcp/` 未认证为 401。原 `/`、GET `/sales/materials` 和 GET `/sales/materials/images` 的前后状态均为 404、401 和 405。根代理亦独立复核。部署后容器状态：edge `StartedAt=2026-11-03T07:14:38.920963378Z`、API `StartedAt=2026-11-03T07:14:38.899999396Z`，两者 `RestartCount=0`、running，API healthy。操作前没有保存 StartedAt/RestartCount 数值，故不宣称该字段有前后精确对照。

在线 SQLite 备份 `/var/lib/lianxu-bridge/backups/pre-evidence.sqlite3` 使用 SQLite backup API 创建；服务重启后，源库和备份 `PRAGMA integrity_check` 均为 ok，控制状态均为 epoch 0、builtin、未接手，快照均为 0。这只验证空样本库，不能宣称已验证实际训练数据的恢复。重启桥接后立即检查曾出现短暂 502：systemd active 早于 Python 监听；数秒后 loopback 和 HTTPS 均恢复 401/200。生产请求方应重试，正式使用前仍应验证含数据的恢复和启动就绪。

初次上传执行脚本时 PowerShell 管道引入 CRLF，pip 试图打开带回车的 requirements 路径而失败；核查确认未生成异常路径。随后服务器默认 pip 源是明文阿里云镜像，第一次安装的 `.venv` 被隔离保留为 `.venv-untrusted`，未用于运行；最终使用全新 `.venv` 从官方 HTTPS PyPI 安装直接固定版本，传递依赖仍未完全锁定。隔离 Caddy 试验第一次意外拉取新 `caddy:2` 镜像并改变本地标签，已恢复标签指向原容器的精确镜像；现有容器本身配置固定 SHA，未停止。新镜像缓存未删除。

人工完成隔离 Evidence 接入：在现有 EvidenceActivity 中打开“连接与显示 → 连接外部智能体”，填上述服务根地址及智能体名称，手机连接密钥使用受限目录 `E:/Dev/Android/lianxu-tunnel-tools/aliyun-sample-20261103/copy-owner.ps1` 复制，智能体连接密钥使用同目录 `copy-agent.ps1` 复制。脚本为 ASCII，Windows PowerShell 5.1 语法检查通过；本执行环境无法访问剪贴板，尚未验证用户桌面实际复制及模拟器粘贴。脚本仅复制，不自动注入手机，令牌不得发到聊天或截图。旧临时桥接仍保持运行，切换 Evidence 的新地址并不会自动撤销旧服务的远端令牌；待独立验证新服务后由根代理关闭旧会话。

自动策略拒绝了“读取新服务器令牌并通过 adb 写入 Evidence 私有文件、随后启动 Activity”的组合命令；该命令未执行，未换壳或拆分绕过。故当前 Evidence 仍处旧会话，新服务器的手机收发、Grok MCP 接手和回写尚待用户人工连接及独立验收。

回滚前检查配置及数据：先验证备份与当前 Caddyfile 没有其他人后续修改；在不替换 bind mount inode 的前提下将备份内容恢复到原文件，向现有 Caddy 容器发送 SIGUSR1，再复查销售路由。仅停用 `lianxu-gateway.socket`、对应 service 及 `lianxu-bridge.service`；不删除新数据库、凭据、旧业务或现有容器。恢复真实业务路由/后续变更必须以实际现场状态为准。

## 手机连接复核（2026-11-03，恢复后）

用户在 Evidence 正常界面点击“连接并同步”后，根代理经 SSH 以 SQLite 只读连接独立核查：control 为 epoch 2、external、Grok Bot、accepted=0；snapshot revision=1，包含 1 个看板、6 条训练、11 条测量、2 个训练计划及 14 个注册方法。pending inputs 与 proposals 均为空。由此确认隔离手机样本已经同步到阿里云；尚未验证 Grok 对新服务的读取、接手或回写，托管 connector 是否能连接仍待实测。没有读取或输出令牌。旧临时会话待新服务 Grok 回写验证完成后关闭。
