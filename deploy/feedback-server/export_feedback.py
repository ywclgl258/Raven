"""Export open Raven feedback into one agent-ready Markdown document.

Usage on the VPS:
  python3 export_feedback.py --data-dir /var/lib/raven-feedback --output /tmp/raven-feedback.md

Usage from the developer machine (via the repo script):
  .\\deploy\\pull-feedback.ps1
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from store import FeedbackStore

OPEN_STATUSES = ("new", "triaged", "in_progress")
CATEGORY_LABELS = {
    "bug": "缺陷",
    "suggestion": "建议",
    "playback": "播放",
    "download": "下载",
    "account": "账号",
    "other": "其他",
}


def build_document(store: FeedbackStore, log_dir: Path, include_resolved: bool = False) -> tuple[str, list[str]]:
    listing = store.list_tickets(limit=200)
    items = listing["items"]
    if not include_resolved:
        items = [item for item in items if item["status"] in OPEN_STATUSES]
    lines = [
        "# Raven 反馈整理",
        "",
        "本文件由 `deploy/feedback-server/export_feedback.py` 从官网后台导出，供本地 Agent 直接优化。",
        "不要把日志中的账号、Cookie、口令写进提交说明或聊天记录。",
        "",
        f"- 未关闭：{sum(1 for item in items if item['status'] in OPEN_STATUSES)}",
        f"- 本文件条目：{len(items)}",
        "",
        "## 给 Agent 的处理约定",
        "",
        "1. 先按优先级处理 `high`，再处理 `normal`。",
        "2. 每条反馈只改与复现路径相关的代码，不顺手重构无关模块。",
        "3. 日志只作为证据；如果日志不足，在回复里说明缺什么，不要猜测凭据。",
        "4. 改完后用对应模块的单元测试验证，并在回复里按反馈编号列出结果。",
        "",
    ]
    exported: list[str] = []
    if not items:
        lines.append("当前没有待处理反馈。")
        return "\n".join(lines) + "\n", exported
    for index, item in enumerate(items, start=1):
        exported.append(item["id"])
        lines.extend(_section(index, item, log_dir))
    return "\n".join(lines).rstrip() + "\n", exported


def _section(index: int, item: dict, log_dir: Path) -> list[str]:
    category = CATEGORY_LABELS.get(item["category"], item["category"])
    device = " · ".join(
        part for part in (item.get("device_model"), item.get("android_release"), f"SDK {item.get('sdk_int') or '-'}") if part
    )
    lines = [
        f"## {index}. {item['id']} · {item['title']}",
        "",
        f"- 状态：`{item['status']}` · 优先级：`{item['priority']}` · 来源：`{item['source']}` · 分类：{category}",
        f"- 版本：{item.get('app_version') or '未知'} ({item.get('version_code') or 0}) · 包名：{item.get('package_name') or '未知'}",
        f"- 设备：{device or '未提供'} · 语言：{item.get('locale') or '未知'}",
        f"- 联系方式：{item.get('contact') or '未留'}",
        "",
        "### 用户描述",
        "",
        item.get("body") or "（空）",
        "",
    ]
    if item.get("admin_note"):
        lines.extend(["### 维护备注", "", item["admin_note"], ""])
    log_path = log_dir / f"{item['id']}.log"
    if log_path.is_file():
        text = log_path.read_text(encoding="utf-8", errors="replace")
        tail = "\n".join(text.splitlines()[-180:]) or "（日志为空）"
        lines.extend([
            "### 日志摘录（末尾 180 行）",
            "",
            "```text",
            tail,
            "```",
            "",
        ])
    else:
        lines.extend(["### 日志", "", "未附带日志。", ""])
    lines.extend([
        "### 建议动作",
        "",
        "- [ ] 复现或确认无法复现",
        "- [ ] 修复并补测试",
        "- [ ] 在后台把状态改为 resolved，备注写明版本号",
        "",
    ])
    return lines


def main() -> None:
    parser = argparse.ArgumentParser(description="Export Raven feedback for a local agent")
    parser.add_argument("--data-dir", default="/var/lib/raven-feedback")
    parser.add_argument("--output", required=True)
    parser.add_argument("--include-resolved", action="store_true")
    parser.add_argument("--mark-exported", action="store_true")
    args = parser.parse_args()
    data_dir = Path(args.data_dir)
    store = FeedbackStore(data_dir / "feedback.sqlite3")
    try:
        document, ids = build_document(store, data_dir / "logs", include_resolved=args.include_resolved)
    finally:
        store.close()
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(document, encoding="utf-8")
    if args.mark_exported:
        store = FeedbackStore(data_dir / "feedback.sqlite3")
        try:
            store.mark_exported(ids)
        finally:
            store.close()
    print(json.dumps({"output": str(output), "count": len(ids), "ids": ids}, ensure_ascii=False))


if __name__ == "__main__":
    main()
