#!/usr/bin/env bash
# Theme import lint (story 9.1 AC): no component OUTSIDE :designui references
# raw colors or fonts — every surface consumes MaterialTheme roles only.
# (Tests of other modules may construct colors for fixtures; production code
# may not.)

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || echo .)"
cd "$REPO_ROOT"

violations=0

scan() {
  local label="$1" pattern="$2" root="$3"
  local hits
  hits=$(grep -rnE "$pattern" "$root" --include='*.kt' --exclude='*Test.kt' 2>/dev/null || true)
  if [ -n "$hits" ]; then
    echo "THEME IMPORT VIOLATION ($label):"
    echo "$hits"
    violations=$((violations + 1))
  fi
}

# Raw Compose color construction outside :designui production code.
scan "raw Color(" 'Color\(0x' app/src/main designui/src/test core/data/src/main playback/src/main catalog/src/main core/model/src/main

# Direct font resource references outside :designui.
scan "R.font." 'R\.font\.' app/src/main core/data/src/main playback/src/main catalog/src/main core/model/src/main

if [ "$violations" -gt 0 ]; then
  echo "Theme import audit FAILED with $violations violation(s)."
  exit 1
fi

echo "Theme import audit OK: raw colors/fonts live only in :designui."
