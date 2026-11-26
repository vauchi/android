#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
# SPDX-License-Identifier: GPL-3.0-or-later

"""Merge required Android lint XML reports into GitLab Code Quality JSON."""

import argparse
import hashlib
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

SEVERITIES = {"Fatal": "blocker", "Error": "critical", "Warning": "minor", "Informational": "info", "Hint": "info"}
BUILD_FILES = {"build.gradle.kts", "build.gradle", "gradle.lockfile", "gradle.properties", "settings.gradle.kts"}


def source_location(root, report, location):
    source = Path(location.attrib["file"])
    source = (source if source.is_absolute() else report.parent / source).resolve()
    line = max(1, int(location.get("line", "1")))
    if "/.gradle/caches/" in source.as_posix():
        # Dependency binaries have no repository line; their consumer's build file owns the pin.
        module = root / report.relative_to(root).parts[0]
        build = next((module / name for name in ("build.gradle.kts", "build.gradle") if (module / name).is_file()), None)
        if build is not None:
            return build.relative_to(root).as_posix(), 1
    if source.is_relative_to(root):
        return source.relative_to(root).as_posix(), line
    # Relocatable Gradle reports can retain the previous checkout's absolute source paths.
    for module in root.iterdir():
        if not module.is_dir():
            continue
        _, marker, suffix = source.as_posix().rpartition(f"/{module.name}/")
        if marker:
            candidate = (module / suffix).resolve()
            if candidate.is_relative_to(root) and candidate.is_file():
                return candidate.relative_to(root).as_posix(), line
    if source.name in BUILD_FILES and (root / source.name).is_file():
        return source.name, line
    raise ValueError("lint source is outside the repository and cannot be mapped")


def findings(root, reports):
    merged = {}
    for report in reports:
        report = (report if report.is_absolute() else root / report).resolve()
        xml = ET.parse(report).getroot()
        if xml.tag != "issues":
            raise ValueError("unexpected lint report root")
        for issue in xml.findall("issue"):
            if issue.get("severity") == "Ignore":
                continue
            severity = SEVERITIES[issue.attrib["severity"]]
            location = issue.find("location")
            if location is None or not location.get("file"):
                raise ValueError("lint finding has no source location")
            path, line = source_location(root, report, location)
            name = issue.attrib["id"]
            description = issue.attrib["message"]
            identity = json.dumps([name, path, line, description], ensure_ascii=False).encode("utf-8")
            fingerprint = hashlib.sha256(identity).hexdigest()
            merged[fingerprint] = {
                "description": description,
                "check_name": name,
                "fingerprint": fingerprint,
                "severity": severity,
                "location": {"path": path, "lines": {"begin": line}},
            }
    return [merged[key] for key in sorted(merged)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("reports", nargs="+", type=Path)
    args = parser.parse_args()
    try:
        report = findings(args.root.resolve(), args.reports)
    except (OSError, ET.ParseError, ValueError, KeyError) as error:
        print(f"Cannot create lint Code Quality report: {error}", file=sys.stderr)
        return 2
    print(json.dumps(report, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
