#!/usr/bin/env python3
"""Minimal QQ Bot -> omp bridge.

Install:
    pip install -U qq-botpy python-dotenv

Configure:
    copy .env.example to .env and fill QQ_BOT_APP_SECRET.

Run:
    python bridge.py

The bridge never stores the AppSecret in the repository. It is read from the
environment or from a local .env file that must stay outside version control.
"""

from __future__ import annotations

import os
import subprocess
from pathlib import Path

import botpy
from botpy.message import C2CMessage, GroupMessage
from dotenv import load_dotenv


load_dotenv()

APP_ID = os.environ["QQ_BOT_APP_ID"]
APP_SECRET = os.environ["QQ_BOT_APP_SECRET"]
REPO_ROOT = Path(os.environ.get("OMP_REPO_ROOT", "D:/Raven"))
OMP_COMMAND = os.environ.get("OMP_COMMAND", "omp")
OMP_TIMEOUT = int(os.environ.get("OMP_TIMEOUT_SECONDS", "300"))
OMP_EXTRA_ARGS = os.environ.get("OMP_EXTRA_ARGS", "--no-session").split()


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
            result = subprocess.run(
                [OMP_COMMAND, "-p", prompt, *OMP_EXTRA_ARGS],
                cwd=str(REPO_ROOT),
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=OMP_TIMEOUT,
            )
        except subprocess.TimeoutExpired:
            return "omp 执行超时。"
        if result.returncode != 0:
            detail = (result.stderr or result.stdout or "").strip()
            return f"omp 执行失败：{detail[:1000]}"
        return result.stdout or "omp 未返回内容。"

    async def on_c2c_message_create(self, message: C2CMessage):
        await self._handle_message(message)

    async def on_group_at_message_create(self, message: GroupMessage):
        await self._handle_message(message)

    async def _handle_message(self, message):
        prompt = (message.content or "").strip()
        if not prompt:
            return
        # Prefix with the Raven project context so every QQ request stays scoped.
        full_prompt = f"在 D:/Raven 仓库中处理以下请求：{prompt}"
        answer = await self._run_omp(full_prompt)
        await self._reply(message, answer)


def main():
    intents = botpy.Intents(public_messages=True, direct_message=True, public_guild_messages=True)
    client = OmpBridge(intents=intents)
    client.run(appid=APP_ID, secret=APP_SECRET)


if __name__ == "__main__":
    main()
