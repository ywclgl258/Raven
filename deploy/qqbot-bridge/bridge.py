#!/usr/bin/env python3
"""Minimal QQ Bot -> omp bridge.

Install:
    pip install -U qq-botpy python-dotenv

Configure:
    copy .env.example to .env and fill QQ_BOT_APP_SECRET plus the allowlists.

Run:
    python bridge.py

The bridge never stores the AppSecret in the repository. It is read from the
environment or from a local .env file that must stay outside version control.

Every message runs ``omp`` inside the repository, so the bridge is a remote code
execution surface: it refuses to start unless at least one allowlist is set, and
rejects any sender that is not listed *before* a subprocess is spawned.
"""

from __future__ import annotations

import asyncio
import os
import shlex
import subprocess
import sys
from pathlib import Path

import botpy
from botpy.message import C2CMessage, GroupMessage
from dotenv import load_dotenv


load_dotenv()

APP_ID = os.environ.get("QQ_BOT_APP_ID", "").strip()
APP_SECRET = os.environ.get("QQ_BOT_APP_SECRET", "").strip()
REPO_ROOT = Path(os.environ.get("OMP_REPO_ROOT", "D:/Raven"))
OMP_COMMAND = os.environ.get("OMP_COMMAND", "omp")
OMP_TIMEOUT = int(os.environ.get("OMP_TIMEOUT_SECONDS", "300"))
OMP_EXTRA_ARGS = shlex.split(os.environ.get("OMP_EXTRA_ARGS", "--no-session"))

# Variables the child process needs to find tools and honour the console code page.
# Everything else (QQ_BOT_APP_SECRET included) stays in this process only.
CHILD_ENV_PASSTHROUGH = (
    "PATH",
    "PATHEXT",
    "SYSTEMROOT",
    "SYSTEMDRIVE",
    "WINDIR",
    "COMSPEC",
    "TEMP",
    "TMP",
    "TMPDIR",
    "HOME",
    "USERPROFILE",
    "APPDATA",
    "LOCALAPPDATA",
    "LANG",
    "LANGUAGE",
    "LC_ALL",
    "LC_CTYPE",
)


def parse_allowlist(name: str) -> frozenset[str]:
    raw = os.environ.get(name, "")
    return frozenset(item.strip() for item in raw.split(",") if item.strip())


ALLOWED_USERS = parse_allowlist("QQ_BOT_ALLOWED_USERS")
ALLOWED_GROUPS = parse_allowlist("QQ_BOT_ALLOWED_GROUPS")


def child_env() -> dict[str, str]:
    return {name: os.environ[name] for name in CHILD_ENV_PASSTHROUGH if name in os.environ}


def user_allowed(openid: str | None) -> bool:
    return bool(openid) and openid in ALLOWED_USERS


def group_allowed(group_openid: str | None, member_openid: str | None) -> bool:
    if not group_openid or group_openid not in ALLOWED_GROUPS:
        return False
    # When a user allowlist exists, it also constrains who may drive the group.
    if ALLOWED_USERS and not user_allowed(member_openid):
        return False
    return True


class OmpBridge(botpy.Client):
    async def on_ready(self):
        print(f"QQ Bot connected: {self.robot.name}")

    async def _reply(self, message, text: str):
        # Keep replies short enough for QQ and avoid leaking command output.
        text = text.strip()
        if len(text) > 1800:
            text = text[:1700] + "\n... (truncated)"
        await message.reply(content=text)

    async def _run_omp(self, prompt: str) -> str:
        try:
            result = await asyncio.to_thread(
                subprocess.run,
                [OMP_COMMAND, "-p", prompt, *OMP_EXTRA_ARGS],
                cwd=str(REPO_ROOT),
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=OMP_TIMEOUT,
                env=child_env(),
            )
        except subprocess.TimeoutExpired:
            return "omp 执行超时。"
        except OSError as error:
            return f"omp 无法启动：{error}"
        if result.returncode != 0:
            detail = (result.stderr or result.stdout or "").strip()
            return f"omp 执行失败：{detail[:1000]}"
        return result.stdout or "omp 未返回内容。"

    async def on_c2c_message_create(self, message: C2CMessage):
        await self._handle_message(message, allowed=user_allowed(message.author.user_openid))

    async def on_group_at_message_create(self, message: GroupMessage):
        await self._handle_message(
            message,
            allowed=group_allowed(message.group_openid, message.author.member_openid),
        )

    async def _handle_message(self, message, *, allowed: bool):
        prompt = (message.content or "").strip()
        if not prompt:
            return
        if not allowed:
            # No subprocess for unlisted senders; tell them nothing about the repo.
            await self._reply(message, "未授权的会话：请联系维护者把 QQ 号或群号加入 allowlist。")
            return
        # Prefix with the Raven project context so every QQ request stays scoped.
        full_prompt = f"在 {REPO_ROOT} 仓库中处理以下请求：{prompt}"
        answer = await self._run_omp(full_prompt)
        await self._reply(message, answer)


def require_allowlist() -> None:
    if ALLOWED_USERS or ALLOWED_GROUPS:
        return
    print(
        "拒绝启动：未配置 allowlist。请至少设置 QQ_BOT_ALLOWED_USERS 或 QQ_BOT_ALLOWED_GROUPS"
        "（逗号分隔的 openid / 群 openid）。桥接会以仓库目录为工作目录执行 omp，"
        "没有 allowlist 就等于对所有人开放远程代码执行。",
        file=sys.stderr,
    )
    raise SystemExit(2)


def main():
    if not APP_ID or not APP_SECRET:
        print("拒绝启动：缺少 QQ_BOT_APP_ID 或 QQ_BOT_APP_SECRET。", file=sys.stderr)
        raise SystemExit(2)
    require_allowlist()
    intents = botpy.Intents(public_messages=True, direct_message=True, public_guild_messages=True)
    client = OmpBridge(intents=intents)
    client.run(appid=APP_ID, secret=APP_SECRET)


if __name__ == "__main__":
    main()
