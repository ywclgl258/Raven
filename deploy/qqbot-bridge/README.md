# QQ Bot -> omp 桥接

这个目录提供一个最小桥接服务，把 QQ Bot 消息转成 `omp -p` 请求，用来远程继续优化 Raven 项目。

## 1. 准备

1. 在 QQ 开放平台创建机器人，个人主体即可先做内测。
2. 记录 `AppID` 和 `AppSecret`。
3. 设置服务范围、隐私协议，添加内部体验号码。
4. 个人场景建议先用 WebSocket；公开服务再切 Webhook，并配置 IP 白名单。

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
OMP_REPO_ROOT=D:/Raven
OMP_COMMAND=omp
OMP_TIMEOUT_SECONDS=300
```

## 3. 安装依赖

```powershell
python -m pip install -U qq-botpy python-dotenv
```

## 4. 运行

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

## 5. 安全边界

- `QQ_BOT_APP_SECRET` 只能放在本地 `.env` 或系统环境变量，不得提交到 Git。
- `.env.example` 只能保留占位符。
- 机器人只应开放给受信任的 QQ 号或内测账号，避免任意远程执行代码。
- 建议后续增加 allowlist、命令白名单、审计日志和会话隔离。
- 不要把 `signing.properties`、`.jks`、Cookie 或 Token 发给机器人或写入日志。
