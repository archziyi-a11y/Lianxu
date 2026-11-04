# 真机独立 bridge 候选（待审阅，尚未部署）

此目录是供根代理审阅的独立服务方案，不包含真实令牌或训练数据；本次准备未操作手机、生成密钥、修改服务器配置或发布路由。用户手动配置手机前不会上传真机资料。减脂计划、skill 选择和页面内容由用户授权的 Grok 完成，不在部署脚本中预设。

只读基线（2026-11-04）：现有 bridge 为 `/opt/lianxu-bridge/releases/7ed7b90`，样本实例与 Caddy 均运行；`18767`、`18768`、`lianxu-phone` 账号及其配置/数据库/unit 路径不存在，Docker 网桥 `br-65d9fe5ab4ea` 的网关为 `172.19.0.1`。Caddyfile SHA-256 为 `7271114f29fcf9606920d6e7ab09cee1f33ae26dfa667f065f80d3ec34983`。GET `/`、`/sales/materials`、`/sales/materials/images`、`/lianxu/mcp/` 的状态依次 404/401/405/401，GET `/lianxu-phone/mcp/` 和 `/lianxu-phone/phone/control` 均 404。未来执行前必须重新验证；若实际环境改变，停止并重新审阅，不更新常量后盲目执行。

只读校验过远端 `7ed7b90` 的 `server.py`、`store.py` 和 `requirements.txt` 与 Git 版本按 CRLF→LF 规范化后的哈希相同，远端文件使用 CRLF。真实手机实例复用该版本只读代码及其指向已安装依赖的 `.venv`，固定版本路径而不跟随 `current`；代码与依赖仍是样本实例共享资源，后续升级须分别审计，不能在共享 venv 中就地修改依赖。

## 实例与路径

- systemd `lianxu-phone.service` 使用专用无登录账号 `lianxu-phone`，独立 `/etc/lianxu-phone/config.env` 和 `/etc/lianxu-phone/agent.token`，目录 0700、文件 0600；独立 SQLite `/var/lib/lianxu-phone/phone.sqlite3`，只监听 `127.0.0.1:18767`。
- `lianxu-phone-gateway.socket` 只绑定 `172.19.0.1%br-65d9fe5ab4ea:18768`，socket-proxyd 以该专用账号转发到 loopback；不监听 0.0.0.0、不新增公网端口或防火墙规则。网桥设备和 Docker 重启的恢复行为尚未验证，不能承诺整机重启后自动恢复。
- 现有 Caddy 只插入 `handle_path /lianxu-phone/*`，去前缀后转发到网关 18768，并将 Host 设置为 `localhost:18767`；其他销售和 `/lianxu/` 路由保持原配置。计划在现有 TLS 域名提供手机 base `https://xiaoshou.yilihuashi.com/lianxu-phone`，Grok MCP 为该地址加 `/mcp/`。
- 手机记录经授权同步后将保存在服务器独立 SQLite 中，**该数据库没有应用层加密**。数据库、日志、备份、服务器管理员访问权限及云服务器持续可用性都应按真实个人资料处理；图片仍按需传输。不要把模拟器的 epoch 7、快照、令牌或数据库复制进来。

## 根代理审核后的执行顺序

`deploy.sh` 默认不执行操作，需在服务器专用暂存目录中显式执行 `preflight`、`install`、`publish` 三个阶段。复制此候选目录到服务器前应复核文件与版本；不传手机数据库、SSH 私钥或其他项目配置。以下命令在已核准服务器上由管理员执行，当前仅作为计划：

1. `bash deploy.sh preflight`：验证用户/路径/端口未占用、基线状态、网桥、已部署 7ed7b90 代码的规范化哈希、Caddyfile 精确哈希和待装 systemd 单元。若任一项不符则停止，无服务或路由修改。
2. `bash deploy.sh install`：建立专用账号及目录，调用现有 `init_config.py` 创建新的随机 owner，独立随机 agent 写入 0600 文件；绝不输出密钥。安装两个 service 和一个 socket，启动后检查 loopback 401/200 和监听地址。失败时可能已创建本项目部分账号、文件或 unit；脚本不会删除任何数据，需按实际状态审查并处理后再重试。此阶段 Caddy 未变、手机无法从公网访问新实例。
3. `bash deploy.sh publish`：再次验证基线、版本与原始 Caddyfile 哈希。生成唯一候选，运行实际容器 `caddy validate`，重验候选与原文件哈希，将原文件连同权限和时间备份为 `Caddyfile.pre-lianxu-phone-20261104`，以保留 bind mount inode 的方式写入候选，再向现有 Caddy 容器发 SIGUSR1。随后核对旧路由和隔离样本路由基线，验证新 MCP 未认证 401 与新 phone 的未认证 401/owner 200。失败时仅在文件哈希仍等于本候选时恢复备份并重载；发现并发改动则拒绝自动覆盖并报告。脚本的哈希检查无法阻止不遵守锁约定的管理员恰在检查与写入间同时更改文件，执行期间需要避免其他配置操作。

现有 Caddy 的管理接口关闭，已用实际版本的隔离 fixture 验证 SIGUSR1 重载；不要停止或重建现有容器。脚本仅生成并验证配置候选，不修改、重载、备份或重启旧样本数据库和旧服务。`systemd-analyze verify`、Caddy validate、真实公网及真实 Android 行为都必须在实际部署时检查；当前没有在服务器执行本候选。

## 凭据交付与关闭

服务创建后，在本机先建立专用受限目录（仅当前 Windows 用户、SYSTEM、Administrators），再通过既有 SSH 将 `/etc/lianxu-phone/config.env` 和 `/etc/lianxu-phone/agent.token` 分别复制到该目录；不要打印文件内容、传入命令参数、聊天或截图。将 `copy-owner.ps1` 和 `copy-agent.ps1` 复制到同一受限目录，在用户的桌面 Windows PowerShell 5.1 运行，以各自脚本把密钥放进剪贴板，由用户在真机或 Grok 界面手动粘贴。两份脚本是 ASCII 且仅操作剪贴板；本工具环境此前无法访问系统剪贴板，实际桌面复制/真机粘贴需用户验证。不要自动注入手机。

停用前先检查当前 Caddyfile 是否仍与已发布候选哈希一致，避免覆盖后续站点更改；使用 `prepare_caddy.py restore` 指定当前候选哈希恢复 `.pre-lianxu-phone-20261104` 内容并发 SIGUSR1，复查旧路由。然后仅停止 `lianxu-phone-gateway.socket`、`lianxu-phone-gateway.service` 和 `lianxu-phone.service`；不要删除数据库、配置、旧服务或证书。针对真实数据的备份应使用现有 `deploy/backup.py` SQLite 在线备份而不是只复制 WAL 模式主文件；恢复须先停新实例、保留原库及 WAL、复制到新的路径并独立校验。长期备份加密、保留策略及运行费用仍需用户决定。

本地校验：`python -m unittest discover -s bridge/deploy/phone -p "test_*.py"`；`bash -n bridge/deploy/phone/deploy.sh`；两份 ASCII 脚本已由 Windows PowerShell 5.1 解析。候选未在服务器运行；尚无真机连接、Grok 接手或计划执行结果。
