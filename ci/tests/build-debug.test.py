#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
# SPDX-License-Identifier: GPL-3.0-or-later

"""CI must retain lint findings without hiding either build or report failure."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

CI = Path(__file__).resolve().parents[1]


class BuildDebugTest(unittest.TestCase):
    def run_build(self, build_status, report_status):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "ci").mkdir()
            shutil.copy(CI / "build-debug.sh", root / "ci/build-debug.sh")
            (root / "gradlew").write_text(f"#!/bin/sh\nexit {build_status}\n")
            (root / "gradlew").chmod(0o755)
            (root / "ci/android-lint-codequality.py").write_text(
                f"print('prepared findings')\nraise SystemExit({report_status})\n"
            )
            result = subprocess.run(["sh", "ci/build-debug.sh"], cwd=root, capture_output=True, text=True, check=False)
            return result.returncode, (root / "gl-code-quality-report.json").read_text()

    def test_success_retains_the_report(self):
        self.assertEqual((0, "prepared findings\n"), self.run_build(0, 0))

    def test_failed_build_still_converts_and_preserves_its_status(self):
        self.assertEqual((42, "prepared findings\n"), self.run_build(42, 0))

    def test_report_failure_fails_an_otherwise_successful_build(self):
        self.assertEqual((2, "prepared findings\n"), self.run_build(0, 2))

    def test_build_failure_wins_when_both_commands_fail(self):
        self.assertEqual((42, "prepared findings\n"), self.run_build(42, 2))


if __name__ == "__main__":
    unittest.main()
