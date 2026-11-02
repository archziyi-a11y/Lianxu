# 本地桥接开发验证

该服务仍在开发，当前仅用于本机隔离联调。它监听 127.0.0.1:8765，使用 Streamable HTTP MCP；不要将明文 HTTP 直接开放到公网。

在隔离环境安装 `requirements.txt` 后，为进程设置至少 32 字符的 `LIANXU_OWNER_TOKEN`，可设置 `LIANXU_BRIDGE_DB` 指向专用 SQLite 文件，运行 `python server.py`。不要把令牌或真实用户数据写入仓库。`python -m unittest discover -s bridge -p "test_*.py"` 运行服务测试。手机与模拟器的测试连接、密钥管理和公网授权部署仍须完成，不能把本服务测试等同于真实 Grok 联调。
