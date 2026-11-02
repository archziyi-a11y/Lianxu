# 外部接入审计修复验证（2026-11-02）

本轮为本地候选修复，不是 Grok Bot 真实账号验收。所有训练与图片为隔离样本；截图来自 Evidence 独立包及内存数据库，未写入正式用户训练库。

- Android 离线构建 `assembleDebug assembleEvidence assembleDebugAndroidTest testDebugUnitTest` 成功；安装 Debug 的 SHA-256 与本地均为 `222ae5d29c61a030c4af621e096dea0b4ad432cba047b1b335b8852fdf07498c`，复制件 `deliverables/Lianxu-external-audit-20261102-222ae5.apk`。
- `python -m unittest discover -s bridge -p "test_*.py"`：5/5。
- 模拟器以下定向命令 13/13：`adb shell am instrument -w -r -e class com.example.lianxu.ExternalRepositoryTest,com.example.lianxu.ExternalBridgeIntegrationTest com.example.lianxu.test/androidx.test.runner.AndroidJUnitRunner`。主 APK 先安装并校验设备 base.apk 与本地 SHA 一致。测试包含文字事实去重、图片请求、schema 驱动饮食与测量入账、计算与图表、编辑保护、决策反馈、撤权、前台自动轮询。图表测试调用本地档案 refresh，生产刷新由 ArchiveRuntime 监听事实变更；该测试不是运行真实 Grok。
- 旧模拟器快照恢复旧版 APK，第一次运行全部 13 项报 NoClassDefFoundError；重装 Debug、核对 SHA 后 13/13 通过。仪器命令的进程退出码不能单独证明测试成功，应检查 `OK (13 tests)`。
- `plans.png`、`decision-pending.png`、`decision-accepted.png`、`decision-rejected.png` 是同一次隔离真实操作。`record-before.png`、`record-filled.png`、`record-after.png` 及 `record-state.json` 是另一隔离会话，点击记组后合成训练由 6 增至 7 且出现 `recorded_set` 事件。事件至 MCP/回写通过分段测试，并非同一条真实账号链路。
- 桥接终态迁移在 Python 存储层测试外部→内置→完成→新外部；真实用户网络/服务断连时序、自动连接状态视觉反馈、跨模式设备端整链仍需进一步独立验证。
- 用户决定是记录建议的同意/拒绝；目标和约束本身仍须用户在资料中更新。自由文本约束依赖模型遵守，不能由代码完备证明。食物目录搜索尚未暴露；事实操作能力提供 description 和 nutrient_total 路径。真实 Grok MCP Bearer 授权、HTTPS 部署、routine/webhook 和费用均未验证，仍需用户自己完成账号侧设置。

本机测试桥接当前 PID 31584。测试需要新 SQLite 库，每轮请停止该 PID 后使用未存在的 `app/build/` 路径重新启动；采用 `ExternalBridgeIntegrationTest` 所载的隔离测试 owner 值，不要使用或打印真实密钥。主服务脚本 `bridge/server.py`，仅监听 localhost。设备执行 `adb reverse tcp:8765 tcp:8765`。因 bridge 测试强制使用固定的假令牌和代际，旧库不能复用于新的完整测试。
