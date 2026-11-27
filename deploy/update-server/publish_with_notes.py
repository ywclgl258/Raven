"""Run publish.py with release notes read from a UTF-8 file.

The one-click script has to cross PowerShell -> ssh -> bash before publish.py sees the notes;
inline quoting loses spaces and CJK punctuation on the way. Reading the notes from a file keeps
the remote command free of quoting entirely.
"""

from __future__ import annotations

import pathlib
import runpy
import sys


def main() -> None:
    args = sys.argv[1:]
    notes_path: str | None = None
    forwarded: list[str] = []
    index = 0
    while index < len(args):
        if args[index] == "--notes-file" and index + 1 < len(args):
            notes_path = args[index + 1]
            index += 2
            continue
        forwarded.append(args[index])
        index += 1

    if notes_path:
        notes = pathlib.Path(notes_path).read_text(encoding="utf-8").strip()
        if notes:
            forwarded.extend(["--release-notes", notes])

    sys.argv = ["publish.py", *forwarded]
    runpy.run_path(str(pathlib.Path(__file__).with_name("publish.py")), run_name="__main__")


if __name__ == "__main__":
    main()
