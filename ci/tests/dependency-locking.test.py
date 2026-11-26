#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
# SPDX-License-Identifier: GPL-3.0-or-later
"""A changed dependency graph must fail the real Gradle compile boundary."""

import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class DependencyLockingTest(unittest.TestCase):
    def test_a_missing_runtime_lock_cannot_compile(self):
        for module in ("app", "display"):
            with self.subTest(module=module):
                lock = ROOT / module / "gradle.lockfile"
                original = lock.read_bytes() if lock.exists() else None
                try:
                    if lock.exists():
                        lock.unlink()
                    result = subprocess.run(
                        [str(ROOT / "gradlew"), f":{module}:compileDebugKotlin", "--offline", "--quiet"],
                        cwd=ROOT, capture_output=True, text=True, timeout=180, check=False,
                    )
                    self.assertNotEqual(0, result.returncode, f"Gradle compiled without the {module} dependency lock")
                    self.assertIn("does not have lock state", result.stderr)
                finally:
                    if original is not None:
                        lock.write_bytes(original)


if __name__ == "__main__":
    unittest.main()
