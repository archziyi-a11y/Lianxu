# 真机专用服务部署（2026-11-04）

根代理审阅候选2706242并运行2项本地测试通过；远端preflight通过，随后install/publish成功。独立账号lianxu-phone、库/var/lib/lianxu-phone/phone.sqlite3，loopback18767，经Docker网关18768提供/lianxu-phone/。固定复用7ed7b90代码与既有依赖。启动初期连接拒绝，脚本重试后health无认证401/owner200；公网同样通过，原路由基线检查通过。Caddy备份为Caddyfile.pre-lianxu-phone-20261104，发布后SHA256 f5b3efc5a501b6d4f06bf1b17e112bb62af1e27a80fe56d8f9bfbbb28ad8286a。

新配置和agent令牌已通过SSH复制到本地受限目录E:/Dev/Android/lianxu-tunnel-tools/aliyun-phone-20261104，配套ASCII剪贴板脚本，无秘密写入仓库或输出。手机尚待用户手动填写；未注入凭据，未验证真机同步/Grok接手。

真机此前已备份并以install -r升级，Android14，APK SHA256 c04a7be53f367469298c2cd0471d1138469bc0bf4c53dcbe3a715f7a1567ee49。启动与两页横滑已操作验证。用户要求由Grok自主选择skill、制定减脂安排并在练序呈现，Codex不代选或代做计划。完整链路仍待真实测试。
