#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
# SPDX-License-Identifier: GPL-3.0-or-later
"""The display boundary rejects seeded coupling and absent source input."""

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "check-display-boundary.py"


class DisplayBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.root = Path(self.scratch.name)
        self.source = self.root / "src/main/kotlin/Display.kt"
        self.source.parent.mkdir(parents=True)
        self.source.write_text("package example\nimport android.view.SurfaceView\n", encoding="utf-8")
        self.build = self.root / "build.gradle.kts"
        self.build.write_text('dependencies { implementation("app.vauchi:contract:0.102.0") }\n', encoding="utf-8")

    def tearDown(self):
        self.scratch.cleanup()

    def check_boundary(self):
        return subprocess.run([sys.executable, str(SCRIPT), str(self.root)], capture_output=True, text=True, timeout=10, check=False)

    def test_a_neutral_view_and_the_contract_dependency_are_allowed(self):
        result = self.check_boundary()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("1 Kotlin source", result.stdout)

    def test_core_and_hardware_imports_are_rejected(self):
        for imported in (
            "uniffi.vauchi_platform.MobileEvent", "app.vauchi.ui.coreui.CoreAppViewModel",
            "android.bluetooth.BluetoothGatt", "android.nfc.NfcAdapter", "android.hardware.SensorManager",
            "androidx.camera.core.Preview", "com.google.mlkit.vision.barcode.BarcodeScanning",
            "app.vauchi.util.LocalizationManager", "app.vauchi.BuildConfig", "app.vauchi.R",
        ):
            with self.subTest(imported=imported):
                self.source.write_text(f"package example\nimport {imported}\n", encoding="utf-8")

                result = self.check_boundary()

                self.assertEqual(1, result.returncode)
                self.assertIn("forbidden display import", result.stderr)

    def test_a_core_or_app_dependency_is_rejected(self):
        for dependency in ('implementation("app.vauchi:vauchi-platform:0.109.0")', 'implementation(project(":app"))'):
            with self.subTest(dependency=dependency):
                self.build.write_text(f"dependencies {{ {dependency} }}\n", encoding="utf-8")

                result = self.check_boundary()

                self.assertEqual(1, result.returncode)
                self.assertIn("forbidden display dependency", result.stderr)

    def test_absent_display_sources_cannot_report_a_clean_boundary(self):
        self.source.unlink()

        result = self.check_boundary()

        self.assertEqual(2, result.returncode)
        self.assertIn("no production Kotlin sources", result.stderr)

    def test_an_unreadable_source_cannot_report_a_clean_boundary(self):
        self.source.write_bytes(b"\xff")

        result = self.check_boundary()

        self.assertEqual(2, result.returncode)
        self.assertIn("could not inspect", result.stderr)


if __name__ == "__main__":
    unittest.main()
