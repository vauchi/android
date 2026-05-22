#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Scan Kotlin source for hardcoded user-visible strings that should
# route through `localizationManager.t(<key>)` instead (ADR-038).
#
# Flags four Compose / Android-API patterns when the first argument is
# a string literal starting with an uppercase ASCII letter:
#
#   Text("...")
#   .setTitle("...")
#   .setSubtitle("...")
#   contentDescription = "..."
#
# Three exemptions:
#
# 1. Brand-name allowlist (see `ALLOWLIST` below) — e.g. "Vauchi".
# 2. Per-line pragma `// allow-hardcoded-string` (any comment trailing
#    the offending line). Use sparingly; the comment SHOULD explain
#    why (e.g. `// allow-hardcoded-string: brand name`).
# 3. `@Suppress("HardcodedString")` on the immediately-preceding line
#    (mirrors Detekt convention; cheaper than a custom rule).
#
# Usage:
#   check-no-hardcoded-strings.sh <kotlin-file-or-dir> [more...]
#
# Exit code 1 if any violation is found.
#
# History: audit 18 C1 (`_private!2026-05-21-android-mainactivity-hardcoded-strings`)
# G2 + G3 — shell-based gate instead of a custom Detekt plugin,
# matching the existing `lint:locale-key-coverage` precedent
# (android/.gitlab-ci.yml:352).

set -euo pipefail

if [[ $# -eq 0 ]]; then
  echo "Usage: $0 <kotlin-file-or-dir> [more...]" >&2
  exit 2
fi

ALLOWLIST=(
  'Vauchi'
)

allow_re=""
for s in "${ALLOWLIST[@]}"; do
  [[ -n "$allow_re" ]] && allow_re+="|"
  allow_re+="\"${s}\""
done

# rg with -g is portable across alpine (CI) and GNU/BSD hosts.
matches=$(rg --no-heading --line-number --with-filename \
  -g '*.kt' \
  -e 'Text\("[A-Z][^"]*"\)' \
  -e '\.setTitle\("[A-Z][^"]*"\)' \
  -e '\.setSubtitle\("[A-Z][^"]*"\)' \
  -e 'contentDescription = "[A-Z][^"]*"' \
  "$@" 2>/dev/null || true)

if [[ -z "$matches" ]]; then
  echo "OK: no hardcoded user-visible strings under: $*"
  exit 0
fi

violations=""
while IFS= read -r line; do
  # line is `path:lineno:content`
  if [[ -n "$allow_re" ]] && [[ "$line" =~ $allow_re ]]; then
    # check whether the entire flagged literal is allowlisted
    # (covers `Text("Vauchi")`, `.setTitle("Vauchi")`, etc.)
    if echo "$line" | grep -qE "($allow_re)\\)|($allow_re),|($allow_re)$"; then
      continue
    fi
  fi
  if echo "$line" | grep -qE "// allow-hardcoded-string"; then
    continue
  fi
  # @Suppress check: peek at the previous line in the source file.
  path="${line%%:*}"
  rest="${line#*:}"
  lineno="${rest%%:*}"
  if [[ "$lineno" -gt 1 ]]; then
    prev=$(sed -n "$((lineno - 1))p" "$path" 2>/dev/null)
    if echo "$prev" | grep -qE '@Suppress\([^)]*"HardcodedString"'; then
      continue
    fi
  fi
  violations+="$line"$'\n'
done <<< "$matches"

if [[ -z "$violations" ]]; then
  echo "OK: no hardcoded user-visible strings under: $* (after allowlist + pragmas)"
  exit 0
fi

cat >&2 <<'EOF'
ERROR: hardcoded user-visible strings detected (route through localizationManager.t() per ADR-038):

EOF
printf '%s' "$violations" >&2
cat >&2 <<'EOF'

Fix one of three ways:
  1. Use localizationManager.t("locale.key") and add the key to vauchi/locales.
  2. Add `// allow-hardcoded-string: <reason>` on the same line if it
     is genuinely OK (technical identifier, debug-only string, etc.).
  3. Add `@Suppress("HardcodedString")` on the preceding line for
     larger blocks.

EOF
printf 'Brand names (currently: %s) are auto-allowed and need no pragma.\n' "${ALLOWLIST[*]}" >&2
exit 1
