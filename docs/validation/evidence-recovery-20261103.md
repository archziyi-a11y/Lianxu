# Evidence 联调界面恢复（2026-11-03）

模拟器退出的原因尚未确定。冷启动使用 E 盘 AVD `Medium_Phone_API_37.0`，参数 `-no-snapshot-load -no-snapshot-save -memory 1536 -cores 2`；构建前正常关闭模拟器，Gradle 离线单 worker、1GB JVM 构建 Evidence，成功后停止 Gradle 并重启设备。该恢复不证明卡顿已经根除。

联调模式现在使用 Evidence 独立包自身的 `AppDatabase.getDatabase()`，与 `ExternalSyncWorker` 使用同一个持久数据库；正式 App 数据库与离线证据的内存 Room 不受影响。只有所有应用表均无数据时才在事务中填入合成样本；已有数据不清空、不覆盖。前台仅在 STARTED 状态使用真实 HTTP 同步此库；已有连接设置继续保留，用户可通过界面手动修改。联调模式不再读取 `bridge-session.json`，未读取、注入或传输新令牌。

安装前 Evidence 包默认数据库目录为空，未删除任何文件；首次合成样本入库为资料 1、训练 6、计划 2、external/epoch 1。正常 force-stop、覆盖安装并重启后，这些数量和 epoch 保持不变。桥接模式重复使用同一 runId 的输出目录并正常 ready 3 次；离线模式仍要求唯一 runId。当前前台是 EvidenceActivity，后续服务器连接仍须用户操作，不能把界面恢复当作新服务器联调通过。

最终 Evidence APK SHA-256 为 `e759342da1e5d3b7f1e28791988c1e4d6fcf38caca8746e4f8c1a9d9fc490d3f`，与模拟器安装包一致。构建命令为 `./gradlew.bat assembleEvidence --offline --no-configuration-cache --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process'`。

根代理独立触控验证：今日页可显示，点击右上角设置后打开“连接与显示”对话框，截图 `E:/Dev/Android/avd/recovery-settings.png`；当前显示“连接中断，正在重试”。阿里云尚未接入，不将此界面状态记为同步通过。
