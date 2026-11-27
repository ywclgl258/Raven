# QQ Bot -> omp 桥接

这个目录提供一个最小桥接服务，把 QQ Bot 消息转成 `omp -p` 请求，用来远程继续优化 Raven 项目。

## 1. 准备

1. 在 QQ 开放平台创建机器人，个人主体即可先做内测。
2. 记录 `AppID` 和 `AppSecret`。
3. 记下允许使用机器人的 openid：私聊的 `user_openid`、群的 `group_openid`。
4. 设置服务范围、隐私协议，添加内部体验号码。
5. 个人场景建议先用 WebSocket；公开服务再切 Webhook，并配置 IP 白名单。

## 2. 配置

```powershell
cd deploy\qqbot-bridge
copy .env.example .env
# 编辑 .env，填入 QQ_BOT_APP_SECRET
```

`.env` 示例：

```env
QQ_BOT_APP_ID=1905347541
QQ_BOT_APP_SECRET=replace-with-app-secret
QQ_BOT_ALLOWED_USERS=openid-of-trusted-user
QQ_BOT_ALLOWED_GROUPS=openid-of-trusted-group
OMP_REPO_ROOT=D:/Raven
OMP_COMMAND=omp
OMP_TIMEOUT_SECONDS=300
OMP_EXTRA_ARGS=--no-session
```

## 3. 白名单（必填，缺失即拒绝启动）

`QQ_BOT_ALLOWED_USERS` 与 `QQ_BOT_ALLOWED_GROUPS` 都是逗号分隔的 openid 列表，至少配置一个，
否则 `bridge.py` 会打印原因并以退出码 2 结束，不会连接 QQ：

- `QQ_BOT_ALLOWED_USERS`：允许私聊机器人的用户 openid。
- `QQ_BOT_ALLOWED_GROUPS`：允许 @ 机器人的群 openid。若同时配置了用户白名单，群内发言者
  还必须出现在 `QQ_BOT_ALLOWED_USERS` 里。

不在白名单内的消息只得到一条拒绝回复，**不会**启动 `omp` 子进程。子进程使用显式构造的最小环境：
只继承 `PATH`、`PATHEXT`、`SYSTEMROOT`、`SYSTEMDRIVE`、`WINDIR`、`COMSPEC`、`TEMP`/`TMP`/`TMPDIR`、
`HOME`/`USERPROFILE`/`APPDATA`/`LOCALAPPDATA` 与 `LANG`/`LC_ALL` 等运行必需变量，
`QQ_BOT_APP_SECRET` 及其他变量都不会传给 `omp`。

## 4. 安装依赖

```powershell
python -m pip install -U qq-botpy python-dotenv
```

## 5. 运行

```powershell
python bridge.py
```

运行后在 QQ 中 @ 机器人或私聊发送请求，例如：

```text
把连载漫画历史记录改成按作品追踪
```

桥接服务会调用：

```powershell
omp -p "在 D:/Raven 仓库中处理以下请求：把连载漫画历史记录改成按作品追踪" --no-session
```

## 6. 安全边界

- `QQ_BOT_APP_SECRET` 只能放在本地 `.env` 或系统环境变量，不得提交到 Git。
- `.env.example` 只能保留占位符。
- 白名单是唯一的授权边界：`omp` 会在仓库目录里执行模型给出的命令，没有白名单等于对所有人开放远程代码执行。
- 不要把 `signing.properties`、`.jks`、Cookie 或 Token 发给机器人或写入日志。
