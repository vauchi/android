#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
# SPDX-License-Identifier: GPL-3.0-or-later

set -euo pipefail

gradle_wrapper="${VAUCHI_PUBLICATION_WRAPPER:-./gradlew}"
task_prefix="${VAUCHI_PUBLICATION_TASK_PREFIX:-}"

guard_logs=$(mktemp -d "${TMPDIR:-/tmp}/vauchi-maven-guards.XXXXXX")
trap 'rm -rf "$guard_logs"' EXIT

refuse() {
    local label=$1 expected=$2 tag=$3 project=$4 project_id=$5 token=$6
    local task=${7:-verifyMavenRelease}
    local output="$guard_logs/$label.log"
    if CI_COMMIT_TAG="$tag" CI_PROJECT_PATH="$project" CI_PROJECT_ID="$project_id" CI_JOB_TOKEN="$token" \
        "$gradle_wrapper" --no-daemon --no-configuration-cache -q "${task_prefix}${task}" >"$output" 2>&1; then
        printf 'FAIL: %s was permitted\n' "$label" >&2
        exit 1
    fi
    if ! grep -F "$expected" "$output" >/dev/null; then
        cat "$output" >&2
        printf 'FAIL: %s failed for an unexpected reason\n' "$label" >&2
        exit 1
    fi
    printf 'PASS: %s refused\n' "$label"
}

test_token="publication-test-not-a-secret"
refuse missing-tag 'Release tag must match v0.1.0' '' vauchi/android-display 123 "$test_token"
refuse malformed-tag 'Release tag must match v0.1.0' v0.1 vauchi/android-display 123 "$test_token"
refuse mismatched-tag 'Release tag must match v0.1.0' v0.1.1 vauchi/android-display 123 "$test_token"
refuse wrong-project 'Publish only from vauchi/android-display' v0.1.0 vauchi/android 123 "$test_token"
refuse invalid-id 'A GitLab project ID is required' v0.1.0 vauchi/android-display '../123' "$test_token"
refuse missing-token 'A CI job token is required' v0.1.0 vauchi/android-display 123 ''
refuse publisher-guard 'Release tag must match v0.1.0' '' vauchi/android-display 123 "$test_token" \
    publishReleasePublicationToGitLabRepository

CI_COMMIT_TAG=v0.1.0 CI_PROJECT_PATH=vauchi/android-display CI_PROJECT_ID=123 CI_JOB_TOKEN="$test_token" \
    "$gradle_wrapper" --no-daemon --no-configuration-cache -q "${task_prefix}verifyMavenRelease"
printf 'PASS: matching release metadata accepted without publishing\n'
