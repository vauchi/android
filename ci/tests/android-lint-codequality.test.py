#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
# SPDX-License-Identifier: GPL-3.0-or-later

"""Retain lint findings from every assembled module in GitLab's report."""

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

SCRIPT = Path(__file__).resolve().parents[1] / "android-lint-codequality.py"


class AndroidLintCodequality(unittest.TestCase):
    def report(self, root, module, severity="Warning", filename=None, issue=True):
        path = root / module / "build/reports/lint-results-debug.xml"
        path.parent.mkdir(parents=True, exist_ok=True)
        xml = ET.Element("issues", format="6")
        if issue:
            finding = ET.SubElement(xml, "issue", id="PreparedRule", severity=severity, message="Prepared diagnostic")
            ET.SubElement(finding, "location", file=filename or str(root / module / "src/main/View.kt"), line="7")
        ET.ElementTree(xml).write(path, encoding="utf-8")
        return path

    def run_report(self, root, reports):
        return subprocess.run([sys.executable, str(SCRIPT), "--root", str(root), *map(str, reports)], capture_output=True, text=True, check=False)

    def test_both_module_findings_have_repository_relative_locations(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            reports = [self.report(root, module) for module in ("app", "display")]
            result = self.run_report(root, reports)
            self.assertEqual(0, result.returncode, result.stderr)
            findings = json.loads(result.stdout)
            self.assertEqual({"app/src/main/View.kt", "display/src/main/View.kt"}, {f["location"]["path"] for f in findings})
            for finding in findings:
                self.assertEqual("Prepared diagnostic", finding["description"])
                self.assertEqual("PreparedRule", finding["check_name"])
                self.assertEqual("minor", finding["severity"])
                self.assertEqual(7, finding["location"]["lines"]["begin"])

    def test_missing_a_required_module_cannot_emit_a_partial_clean_report(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            report = self.report(root, "app", issue=False)
            result = self.run_report(root, [report, root / "missing.xml"])
            self.assertNotEqual(0, result.returncode)
            self.assertEqual("", result.stdout)

    def test_empty_reports_produce_the_required_empty_json_array(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            result = self.run_report(root, [self.report(root, m, issue=False) for m in ("app", "display")])
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual([], json.loads(result.stdout))

    def test_fingerprints_do_not_depend_on_checkout_root_or_input_order(self):
        results = []
        for _ in range(2):
            with tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                reports = [self.report(root, m) for m in ("app", "display")]
                result = self.run_report(root, list(reversed(reports)) if results else reports)
                self.assertEqual(0, result.returncode, result.stderr)
                results.append(json.loads(result.stdout))
        self.assertEqual(results[0], results[1])

    def test_report_relative_paths_are_normalized_without_leading_dot(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            result = self.run_report(root, [self.report(root, "display", filename="../../src/main/View.kt")])
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("display/src/main/View.kt", json.loads(result.stdout)[0]["location"]["path"])

    def test_invalid_severity_or_outside_paths_cannot_be_silently_dropped(self):
        for severity, filename in (("Unexpected", None), ("Warning", "/outside/repository/View.kt")):
            with self.subTest(severity=severity, filename=filename), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                result = self.run_report(root, [self.report(root, "display", severity, filename)])
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("", result.stdout)

    def test_relocated_cached_source_paths_rebase_to_existing_module_files(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for name in ("app/src/main/View.kt", "gradle/libs.versions.toml"):
                with self.subTest(name=name):
                    source = root / name
                    source.parent.mkdir(parents=True, exist_ok=True)
                    source.write_text("prepared content\n")
                    report = self.report(root, "app", filename="/old/checkout/" + name)
                    result = self.run_report(root, [report])
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertEqual(name, json.loads(result.stdout)[0]["location"]["path"])

    def test_external_gradle_dependency_findings_attach_to_the_owning_build_file(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            build = root / "app/build.gradle.kts"
            build.parent.mkdir(parents=True)
            build.write_text("dependencies {}\n")
            for cache in (Path("/cache"), root):
                with self.subTest(cache=str(cache)):
                    report = self.report(root, "app", filename=str(cache / ".gradle/caches/transforms/library/jni/arm64/libnative.so"))
                    result = self.run_report(root, [report])
                    self.assertEqual(0, result.returncode, result.stderr)
                    finding = json.loads(result.stdout)[0]
                    self.assertEqual({"path": "app/build.gradle.kts", "lines": {"begin": 1}}, finding["location"])
                    self.assertEqual("Prepared diagnostic", finding["description"])

    def test_severities_and_duplicate_findings_are_preserved(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            reports = [self.report(root, severity, severity) for severity in ("Fatal", "Error", "Warning", "Informational", "Hint")]
            result = self.run_report(root, reports + reports)
            self.assertEqual(0, result.returncode, result.stderr)
            findings = json.loads(result.stdout)
            self.assertEqual(5, len(findings))
            self.assertEqual({"blocker", "critical", "minor", "info"}, {f["severity"] for f in findings})


if __name__ == "__main__":
    unittest.main()
