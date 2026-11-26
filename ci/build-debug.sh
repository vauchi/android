#!/bin/sh
# SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
# SPDX-License-Identifier: GPL-3.0-or-later

set -eu

build_status=0
./gradlew assembleDebug lint || build_status=$?
report_status=0
python3 ci/android-lint-codequality.py --root . \
    app/build/reports/lint-results-debug.xml \
    display/build/reports/lint-results-debug.xml \
    > gl-code-quality-report.json || report_status=$?

if [ "$build_status" -ne 0 ]; then
    exit "$build_status"
fi
exit "$report_status"
