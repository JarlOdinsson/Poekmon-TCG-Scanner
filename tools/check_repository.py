#!/usr/bin/env python3
from __future__ import annotations

import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
GITHUB_LIMIT = 100 * 1024 * 1024
WARNING_LIMIT = 95 * 1024 * 1024


def main() -> int:
    result = subprocess.run(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
        cwd=ROOT,
        check=True,
        capture_output=True,
    )
    oversized: list[tuple[Path, int]] = []
    warnings: list[tuple[Path, int]] = []
    for raw in result.stdout.split(b"\0"):
        if not raw:
            continue
        relative = Path(raw.decode("utf-8"))
        path = ROOT / relative
        if not path.exists():
            continue
        size = path.stat().st_size
        if size >= GITHUB_LIMIT:
            oversized.append((relative, size))
        elif size >= WARNING_LIMIT:
            warnings.append((relative, size))
    for path, size in warnings:
        print(f"warning: {path} is close to GitHub's limit ({size} bytes)")
    for path, size in oversized:
        print(f"error: {path} exceeds GitHub's 100 MiB file limit ({size} bytes)")
    if oversized:
        return 1
    print("Repository file-size check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
