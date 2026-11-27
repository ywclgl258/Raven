# Raven 反馈后台

官网与 Android 客户端共用这一套后端。服务只监听 `127.0.0.1:8765`，由现有官网 nginx 反代。

## 部署

在 VPS 上第一次安装时，先设置至少 12 位管理口令，再运行安装脚本。口令写入 `/etc/raven-feedback.env`，不进入仓库。

```bash
export RAVEN_FEEDBACK_ADMIN_PASSWORD='至少十二位的口令'
sudo -E bash deploy/feedback-server/install.sh
```

安装后：

- 公开提交：`https://ywclgl258.us.ci/api/feedback`
- 后台：`https://ywclgl258.us.ci/admin`
- 数据：`/var/lib/raven-feedback/feedback.sqlite3`
- 日志：`/var/lib/raven-feedback/logs/`

本地一键整理：

```powershell
.\deploy\pull-feedback.ps1
```

脚本会把未关闭反馈和日志摘录下载到 `docs/agent/FEEDBACK_INBOX.md`。把这个文件交给本地 Agent 即可按编号处理。

## 安全边界

- 公开接口只接受标题、描述、设备信息和可选日志，不接收账号、Cookie 或备份。
- 客户端先在设备上打码，服务端再对日志里的 `password`、`cookie`、`authorization`、`token` 行打码一次。
- 后台使用 HttpOnly + Strict Cookie，口令用 scrypt 保存。
- 单条日志上限 1.5 MB。

## 客户端日志采集

应用侧日志实现在 `:core:common` 的 `com.raven.app.core.common.log`，`:app` 的 `RavenDiagnostics` 负责 Android 装配：

- 采集分层：滚动时间线（`filesDir/diagnostics/raven.log`，384 KB 轮转一次，保留上一份）、崩溃记录（`crash.log`，只存 ERROR + 堆栈）、logcat 镜像（`adb logcat` 仍可用）、进程内环形缓冲（1200 条，用于瞬间取快照）。
- 记录级别：INFO 及以上始终记录；DEBUG/VERBOSE 只在 debug 包或用户在反馈页临时开启「记录详细日志」时记录（不持久化，避免长期磁盘与隐私开销）。
- 脱敏：写入磁盘前与生成报告时各做一次。覆盖 `password`/`cookie`/`authorization`/`token`/`secret`/`api_key` 等键值、`Bearer`/`Basic` 凭据、URL 上 `token`/`sig`/`session` 等查询参数、私钥整段。
- 上传内容只有一份文本报告：环境头（版本、包名、设备、ABI、语言、时区、内存）、错误与警告、最近一次错误、事件时间线、上次崩溃、logcat 尾部、滚动文件尾部；不含账号、Cookie 值、浏览内容或备份。
- 接入点：应用启动、网络请求失败与 429、更新检查与安装包校验、批量下载失败、反馈提交本身都会写一条带标签的事件，便于按标签定位子系统。
