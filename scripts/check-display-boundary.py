#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
# SPDX-License-Identifier: GPL-3.0-or-later
"""Reject Core/app and hardware coupling in an Android display checkout."""

import argparse
import re
import sys
from pathlib import Path

IMPORT = re.compile(r"^\s*import\s+([\w.*]+)", re.MULTILINE)
HARDWARE = (
    "uniffi", "android.bluetooth", "android.nfc", "android.hardware", "android.location", "android.media",
    "androidx.camera", "com.google.mlkit.vision",
)
DISPLAY = ("app.vauchi.contract", "app.vauchi.display", "app.vauchi.ui.presentation", "app.vauchi.ui.theme")
DEPENDENCY = re.compile(r"vauchi-platform|project\s*\(\s*[\"']:app[\"']")


def under(name, prefix):
    return name == prefix or name.startswith(prefix + ".")


def inspect(root):
    sources = sorted((root / "src/main").rglob("*.kt"))
    if not sources:
        print("no production Kotlin sources in display input", file=sys.stderr)
        return 2
    violations = []
    for source in sources:
        text = source.read_text(encoding="utf-8")
        for imported in IMPORT.findall(text):
            hardware = any(under(imported, prefix) for prefix in HARDWARE)
            app = under(imported, "app.vauchi") and not any(under(imported, prefix) for prefix in DISPLAY)
            if hardware or app:
                violations.append(f"{source.relative_to(root)}: forbidden display import {imported}")
    build = root / "build.gradle.kts"
    if DEPENDENCY.search(build.read_text(encoding="utf-8")):
        violations.append("build.gradle.kts: forbidden display dependency")
    if violations:
        print("\n".join(violations), file=sys.stderr)
        return 1
    print(f"Display boundary checked: {len(sources)} Kotlin source(s)")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    try:
        return inspect(args.root)
    except (OSError, UnicodeError):
        print("could not inspect complete display input", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
