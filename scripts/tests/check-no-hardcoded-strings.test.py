#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
# SPDX-License-Identifier: GPL-3.0-or-later

"""The string guard preserves exemptions and reports incomplete scans."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "check-no-hardcoded-strings.sh"
BASH = shutil.which("bash")


class HardcodedStringGuardTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "View sample.kt"
        self.source.write_text('Text("Vauchi")\n')
        self.bin = self.root / "bin"
        self.bin.mkdir()

    def run_guard(self, source=None, path=None):
        env = dict(os.environ)
        if path is not None:
            env["PATH"] = path
        return subprocess.run([BASH, str(SCRIPT), str(source or self.source)],
                              env=env, capture_output=True, text=True)

    def test_brand_allowlist_remains_valid(self):
        result = self.run_guard()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("OK:", result.stdout)

    def test_file_without_matching_strings_is_clean(self):
        self.source.write_text("val value = 1\n")
        result = self.run_guard()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("OK:", result.stdout)

    def test_explicit_pragma_remains_valid(self):
        self.source.write_text('Text("Example") // allow-hardcoded-string: fixture\n')
        result = self.run_guard()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("OK:", result.stdout)

    def test_preceding_suppression_remains_valid(self):
        self.source.write_text('@Suppress("HardcodedString")\nText("Example")\n')
        result = self.run_guard()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("OK:", result.stdout)

    def test_unlocalized_string_fails_with_source_location(self):
        self.source.write_text('Text("Example")\n')
        result = self.run_guard()
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertIn("View sample.kt:1:", result.stderr)
        self.assertNotIn("OK:", result.stdout)

    def test_missing_input_is_an_error(self):
        result = self.run_guard(source=self.root / "missing.kt")
        self.assertEqual(result.returncode, 2)
        self.assertNotIn("OK:", result.stdout)

    def test_backend_failure_with_or_without_partial_matches_is_an_error(self):
        self.source.write_text('Text("Example")\n')
        for partial in [False, True]:
            with self.subTest(partial=partial):
                stub = self.bin / "rg"
                stub.write_text("#!/bin/sh\n" + (
                    "echo 'View.kt:1:Text(\"Example\")'\n" if partial else "") +
                    "echo 'injected search failure' >&2\nexit 2\n")
                stub.chmod(0o755)
                result = self.run_guard(path=str(self.bin) + os.pathsep + os.environ["PATH"])
                self.assertEqual(result.returncode, 2)
                self.assertIn("injected search failure", result.stderr)
                self.assertNotIn("OK:", result.stdout)

    def test_missing_search_tool_is_an_error(self):
        result = self.run_guard(path=str(self.bin))
        self.assertEqual(result.returncode, 127)
        self.assertNotIn("OK:", result.stdout)


if __name__ == "__main__":
    unittest.main()
