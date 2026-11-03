# 练序外部智能体桥接（开发与联调）

这是单用户桥接服务，使用本机 SQLite 和 MCP Streamable HTTP，默认只监听 `127.0.0.1:8765`。训练记录由手机主动同步到所配置的服务；原图只在智能体请求该待处理输入的图片后传送，缓存在服务进程内存中，并在切换处理方时清除。SQLite 内的文字、历史和页面快照没有应用层加密，部署位置和备份策略需由使用者选择。

## 本地运行

安装 Python 依赖：`python -m pip install -r bridge/requirements.txt`。设置不少于 32 字符且随机生成的 `LIANXU_OWNER_TOKEN`，可选设置 `LIANXU_BRIDGE_DB`，在 `bridge` 目录运行 `python server.py`。手机设置中的“手机连接密钥”须与服务的 owner token 相同；“智能体连接密钥”是另一条随机密钥，由手机在切换时登记。每次更换智能体必须使用从未使用过的新智能体密钥。不要将令牌写入仓库、聊天或截图。

本机隔离测试：`python -m unittest discover -s bridge -p "test_*.py"`。Android 模拟器可通过 `adb reverse tcp:8765 tcp:8765` 和 debug 包的 `http://127.0.0.1:8765` 联通。普通发布包仅允许 HTTPS；切勿把明文 HTTP 直接暴露到公网。实际手机与远端智能体需要用户选定可长期运行的 HTTPS 服务和可信访问方式，代理必须将上游 Host 设置为服务认可的 localhost 地址。服务不提供 OAuth 授权服务器，Grok Bot 的自定义 MCP 界面能否使用当前 Bearer 方式尚未通过真实账号验证。

## Grok Bot 接续

用户在 Grok Bot 中为 Bot 配置自定义 MCP，服务路径为 `/mcp/`，并以智能体密钥完成其支持的认证；账户权限、界面及 Bearer 可用性需实际检查。Bot 先调用 `read_handoff` 阅读目标、计划、近期实绩、约束和待处理输入，再用该快照版本调用 `accept_handoff`。之后可读取待处理项并提交今日、档案、计划或能力需求提议；返回 `pending` 只说明服务已收件，`read_proposal_status` 才能看到手机是否应用、拒绝或转交用户决定。仅向已授权服务同步；不要将真实库用于无授权的演示环境。

可在 Grok Bot 自己的界面建立定时 routine，使 Bot 到时读取最新资料。若用户建立 webhook routine，可将其 HTTPS 地址和 Bearer key 分别设置为 `LIANXU_GROK_WEBHOOK_URL`、`LIANXU_GROK_WEBHOOK_TOKEN` 后重启服务。桥接仅发送输入标识与事件类型，不发送训练原文；同一处理代际中的重复发送有收据去重，异常重试在极端崩溃时仍可能重复触发，提议和记录须按原 submission 幂等。HTTP 200 仅表示平台启动运行，不表示任务完成。未配置 webhook 时需依赖 Bot 的定时 routine 或手动接续。

手机外部模式在联网时后台重试；普通页面更新不通知，声明需要用户决定的目标或约束变更会在系统通知获准时提醒。切回内置模式立即停止手机侧外部写入；若手机离线，远端撤权要等连接恢复，平台原有 routine 也需用户在平台管理。目标 ID 与修订和数据来源在手机校验；自由文字约束依赖智能体阅读并遵守，不能视为已由程序完全证明。

官方参考：[Grok Bot 接入应用](https://docs.x.ai/grok-bot/computer-and-apps)、[Grok Bot 例程与 webhook](https://docs.x.ai/grok-bot/skills-routines-and-automations)。真实账号、实际付费和长期部署尚未配置，未完成平台联调。

`describe_capabilities` 和 `read_handoff` 提供手机已注册的分析方法、档案主题及计算结果。手机档案运行时在事实变化后自动执行适用方法；数据不足时不会虚构结果。图表应引用快照中实际出现的 calculation ID 和结果路径。`submit_facts` 经手机校验并完成对应输入后，另行提交页面时请使用空 `inputIds`，避免重复完成同一输入。实际记组和用户决定会生成独立待处理事件，供接续和触发使用。

待审阅的阿里云单用户部署模板和隔离样本操作说明见 [deploy/README.md](deploy/README.md)；尚未连接或部署至用户服务器。

手机显式重试同一活跃连接时，owner 认证的 `/phone/switch` 可携带 `resumeActive: true`：仅当前服务仍为同名智能体、同一活跃令牌且 epoch 前移时保留接手状态和该代待处理项。默认不续代；真实交接仍拒绝使用过的旧令牌。请求携带续接标志但对应的是从未登记的新令牌时，仍按严格新交接处理并要求重新接手。已完成和被撤销的历史不改代际。
