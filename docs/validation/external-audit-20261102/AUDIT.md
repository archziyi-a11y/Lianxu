# 外部接入审计修复验证（2026-11-02）

本轮为本地候选修复，不是 Grok Bot 真实账号验收。所有训练与图片为隔离样本；截图来自 Evidence 独立包及内存数据库，未写入正式用户训练库。

- Android 离线构建 `assembleDebug assembleEvidence assembleDebugAndroidTest testDebugUnitTest` 成功；安装 Debug 的 SHA-256 与本地均为 `222ae5d29c61a030c4af621e096dea0b4ad432cba047b1b335b8852fdf07498c`，复制件 `deliverables/Lianxu-external-audit-20261102-222ae5.apk`。
- `python -m unittest discover -s bridge -p "test_*.py"`：5/5。
- 模拟器以下定向命令 13/13：`adb shell am instrument -w -r -e class com.example.lianxu.ExternalRepositoryTest,com.example.lianxu.ExternalBridgeIntegrationTest com.example.lianxu.test/androidx.test.runner.AndroidJUnitRunner`。主 APK 先安装并校验设备 base.apk 与本地 SHA 一致。测试包含文字事实去重、图片请求、schema 驱动饮食与测量入账、计算与图表、编辑保护、决策反馈、撤权、前台自动轮询。图表测试调用本地档案 refresh，生产刷新由 ArchiveRuntime 监听事实变更；该测试不是运行真实 Grok。
- 旧模拟器快照恢复旧版 APK，第一次运行全部 13 项报 NoClassDefFoundError；重装 Debug、核对 SHA 后 13/13 通过。仪器命令的进程退出码不能单独证明测试成功，应检查 `OK (13 tests)`。
- `plans.png`、`decision-pending.png`、`decision-accepted.png`、`decision-rejected.png` 是同一次隔离真实操作。`record-before.png`、`record-filled.png`、`record-after.png` 及 `record-state.json` 是另一隔离会话，点击记组后合成训练由 6 增至 7 且出现 `recorded_set` 事件。事件至 MCP/回写通过分段测试，并非同一条真实账号链路。
- 桥接终态迁移在 Python 存储层测试外部→内置→完成→新外部；真实用户网络/服务断连时序、自动连接状态视觉反馈、跨模式设备端整链仍需进一步独立验证。
- 用户决定是记录建议的同意/拒绝；目标和约束本身仍须用户在资料中更新。自由文本约束依赖模型遵守，不能由代码完备证明。食物目录搜索尚未暴露；事实操作能力提供 description 和 nutrient_total 路径。真实 Grok MCP Bearer 授权、HTTPS 部署、routine/webhook 和费用均未验证，仍需用户自己完成账号侧设置。

Terra 交接时的测试桥接 PID 为 31584，根代理已将其停止并使用新库复跑；根代理测试服务现也已停止。再次测试须使用未存在的 `app/build/` SQLite 路径；采用 `ExternalBridgeIntegrationTest` 所载的隔离测试 owner 值，不要使用或打印真实密钥。主服务脚本 `bridge/server.py`，仅监听 localhost。设备执行 `adb reverse tcp:8765 tcp:8765`。因 bridge 测试强制使用固定的假令牌和代际，旧库不能复用于新的完整测试。

## 根代理独立审计结果

实现由 GPT-6.1 Terra 完成，根代理复验生产提交 `abf6367`，以及仅修正测试的 `d5d8683`。交付 APK 哈希保持上述 `222ae5…`。结论：本地候选通过本轮代码审阅和隔离回归，可进入真实 Grok Bot 联调；规格 09 的真实平台闭环尚未验收。

首轮审计发现并退回修复：外部文字/图片事实缺少入账工具；内置处理完成的输入在桥接交接时仍可能待处理；交接快照缺少训练与计划关联；已有卡片无法接受智能体折叠更新；用户决定和本机记组缺少可唤醒智能体的反馈事件；前台自动收发与连接状态反馈不足。修复后复用了现有事实字段校验，增加手机终态对账、能力发现、计划关联及生命周期内的同步。界面截图已独立检查当前/历史计划与决定状态。

根代理核对主 APK 与设备哈希后，启动新的 localhost 隔离桥接库，独立执行：

- Python 桥接测试：5/5 通过。
- Android 广回归：发现 92 项，89 通过、2 项按前置条件跳过、1 项失败。跳过项是 `JourneyTest.liveSparseTwoRounds`（真实模型联网）和 `StageFiveUiTest.manualEvidenceSession`（手动取证入口），不能计作通过。
- 唯一失败为 `NutritionBoardTest.descriptionOnlyIntakeIsNeverDisplayedAsZeroNutrients` 的过期文案断言，仍要求此前删掉的“缺项保留”。未知营养值行为断言已通过。Terra 将其改为非空未知值、差额和小计语义检查，生产代码及 APK 未改；该测试类随后 7/7 通过，根代理另行独立复跑原失败用例，`OK (1 test)`。
- 因此实际执行的 90 个不同用例最终均通过，但不是一次运行的“92/92”。原失败记录保留。

广回归命令为 `adb shell am instrument -w -r -e notClass com.example.lianxu.LiveAgentTest,com.example.lianxu.LiveArchiveTest,com.example.lianxu.StageFiveLiveTest com.example.lianxu.test/androidx.test.runner.AndroidJUnitRunner`。完整原始日志见本目录 `root-instrumentation.log`、`root-python.log`、`root-retest.log`。

仍待真实验收：Grok Bot 账号侧的 MCP 认证、用户发送后的 webhook、定时接续，以及实际网络下的撤权与交接整链。图片测试使用固定样本和脚本提议，不代表 Grok 已完成图片理解。联网服务尚未选定授权；本轮未向公网部署，也未使用真实训练库进行平台试验。

## 临时公网联调准备（后续授权）

用户随后明确授权使用 Cloudflare 临时 HTTPS 隧道连接带令牌认证的隔离样本服务，并要求验证后关闭。Evidence 专用真实 HTTP 模式已提交 `b26677c`，生产 APK 未改变。测试服务位于 localhost:18765，使用新建隔离 SQLite、合成资料及随机凭据；凭据保存在仓库外受限目录，未输出到聊天或验证文档。

本地验证完成 MCP 初始化、12 项工具发现、样本交接读取及页面回写 `applied`。根代理独立复核未认证请求返回 401、有效认证返回 200，交接含 1 个样本看板和 2 个样本计划。本轮无效令牌测试不等同于真实旧代际撤销，后者由既有回归覆盖。

执行环境自动审批拒绝了启动 Cloudflare 隧道及生成启动脚本，返回 `blocked by policy`，没有提供更具体原因。未尝试换壳、改名或其他绕过路径。目前未生成公网 URL，Grok Bot 仍未连接。待用户在本机完成正常的隧道启动后，再进行公网和账号侧验证；此次准备不能记作平台联调成功。
