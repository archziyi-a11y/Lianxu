# 单用户 bridge 部署候选：先审阅，再按实际服务器调整

此目录只提供部署模板，不会自行连接服务器或修改既有服务。先用**全新的隔离样本库**；不传正式训练库、图片或旧令牌。用户已报告 Grok 的 box 直连临时 bridge 可读取和提交提议，但托管 connector 报 Network error；这并不证明该包部署后 connector 可用。

## 部署前确认

由操作人只读确认服务器系统与架构、systemd 和 Python 版本（本服务要求 Python 3.10+）、可用磁盘、现有 HTTPS 反代及证书、可用域名或子域、已有监听端口、现有站点和备份方式。`18765`、`/opt/lianxu-bridge`、`/var/lib/lianxu-bridge`、`/etc/lianxu-bridge` 和 `lianxu-bridge` 均为模板值，确认不冲突后才能使用。已有站点、证书、反代与防火墙规则须原样保留；不假设机器为空，不申请付费服务。

服务架构：TLS 由现有反代终止；只把选定域名的 `/mcp/` 和 `/phone/` 转发到 `127.0.0.1:18765`，不会把 bridge 明文端口暴露公网。模板 `nginx-locations.conf` 仅是现有 TLS `server` 内的两个 location 片段，不是完整站点配置：审阅既有 location、重定向、认证、请求体限制和日志规则后按实际方案合并。不要覆盖整份配置；先备份配置、`nginx -t`，再由有权限的操作人 reload，确认原站点仍工作。如果不是 Nginx，按等价要求接入已有反代，不另装服务。保留原始路径和 Authorization，上游 Host 必须是 `localhost:18765`（或匹配实际端口），避免 MCP 的 Host 校验拒绝；公网外部地址仍是正常 HTTPS 域名。手机连接地址是域名根路径，Grok MCP 地址是 `https://域名/mcp/`。

`lianxu-bridge.service` 是待调整的 systemd 模板：专用非 root 账号运行，代码只读，持久数据仅写入 `/var/lib/lianxu-bridge`，设置 `UMask=0077`、`ProtectSystem=strict`。使用前检查目标 systemd 版本与运行依赖（含 DNS、TLS、文件权限），确认模板路径、账号、端口和其他服务无冲突。创建服务账号和目录需要服务器管理员权限；不要复用现有业务账号。建议发布目录如 `/opt/lianxu-bridge/releases/<revision>/`，由 `current` 指向实际版本；将仓库 `bridge/` 内文件复制到版本目录（使 `server.py`、`requirements.txt`、`deploy/` 位于 `current/` 下）；虚拟环境放在版本目录的 `.venv` 中，Python 依赖安装 `requirements.txt`。当前只固定直接依赖，传递依赖尚未生成完整锁文件；部署前需在匹配的服务器环境复核依赖及来源。不得替换已有应用或其虚拟环境。

## 私有配置与令牌

复制 `config.env.example` 为机器上的 `/etc/lianxu-bridge/config.env`，只允许专用账号及管理员读取（例如文件所有者为专用账号、权限 `0600`，父目录权限 `0700`），不要存入仓库或展示在聊天、进程参数、截图和 shell 历史中。字段是每行一个 `KEY=value`，不进行 shell 变量展开；填入新样本 SQLite 的绝对路径及空闲端口。`launch.py` 从该私有文件读取并通过进程环境传给现有 server；systemd 单元不把密钥写进 `Environment=`。进程运行身份及管理员仍能读取该进程的环境，应避免让其他人使用专用账号。可由专用账号执行 `python deploy/init_config.py /etc/lianxu-bridge/config.env /var/lib/lianxu-bridge/sample.sqlite3 18765`，对不存在的配置文件生成随机 owner token，使用 0600 创建且不打印令牌；目标数据库必须尚不存在。此命令不会创建账号、目录或数据库，且不会覆盖已有配置。智能体令牌由手机在连接交接时登记，必须独立随机生成，且每次更换智能体使用新令牌。不要用真实训练库的密钥。

默认不设置 webhook；用户明确建立 Grok routine 后，才在受限文件同时配置对应 URL 与 token。没有 webhook 时，需由 Grok routine 或用户手动触发，bridge 不会自动要求平台运行。配置被更改后应先在隔离环境审阅，再按具体主机情况重启本服务；其他服务不重启。

## 启动与检查

1. 经审阅创建隔离目录、专用账号、虚拟环境及私有配置；SQLite 从空的 `sample.sqlite3` 开始。配置文件中只存测试专用令牌。安装单元前检查 `systemd-analyze verify`，然后只启动本服务。
2. 从服务器检查监听地址应仅为 `127.0.0.1:<port>`，并运行 `python deploy/health.py /etc/lianxu-bridge/config.env http://127.0.0.1:18765`。正常为未认证 401、owner 认证 200；命令不输出密钥或响应正文。之后将第二参数换成选定的 `https://域名`，验证真实反代和 TLS。这个健康检查只证明认证和控制接口，不证明 Grok 连接或完整业务。
3. 在用户授权的隔离 Evidence 包中配置该测试服务，确认手机上传的是合成资料，再由用户自行在 Grok 配置该 HTTPS MCP 地址和智能体令牌，完成读取、接手、提议、手机应用及撤权测试。观察服务状态和必要日志时不得记录 Authorization、令牌或个人内容。公开公网地址和反代配置经核查后才进行真实测试。

## 备份、恢复与停止

SQLite 使用 WAL。在线状态下不要只复制 `.sqlite3` 文件：以专用账号执行 `python deploy/backup.py /var/lib/lianxu-bridge/sample.sqlite3 /var/lib/lianxu-bridge/backups/<new-name>.sqlite3`；先建受限备份目录，目标必须是新文件。脚本使用 SQLite 在线备份 API 并做 `integrity_check`。备份包含完整快照与记录，应施加同等访问权限；本次只备份合成资料。正式数据的加密、位置、保留期限及费用须另行由用户决定。

升级前记录旧版本及单元/反代配置，做一致性备份。回滚代码前停止**本服务**，切换 `current` 回原版本并核对配置及数据库兼容性，再启动、复检健康与原站点；不要假设未来 schema 向后兼容。若需恢复数据库，先停止本服务并保存当前数据库及 WAL，使用已检验备份**另建**新数据库路径、调整配置并验证后再切换；不要覆盖可能仍含未同步提交的原库。若只停止，停本服务并按事先保存的反代配置移除其两个 location、验证后 reload；不删除数据库、密钥或其他应用。SQLite 备份不能恢复进程内按需图片缓存，重启后需手机重新响应请求。

本地验证：`python -m unittest discover -s bridge/deploy -p 'test_*.py'`。模板未在用户的阿里云机器或其现有反代上运行过；部署条件和最终命令须根据只读检查结果重新核实。

参考：[NGINX proxy 模块](https://nginx.org/en/docs/http/ngx_http_proxy_module.html)、[SQLite 在线备份](https://www.sqlite.org/backup.html)、[systemd 执行环境和凭据](https://www.freedesktop.org/software/systemd/man/latest/systemd.exec.html)。
