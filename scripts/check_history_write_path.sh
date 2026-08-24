#!/usr/bin/env bash
# History single-write-path audit (story 8.3 / FR-34 / AR-5 rule 7): recording
# into History happens EXCLUSIVELY through the service-side recorder
# (HistoryRecorder in :playback) calling :core:data's HistoryRepository.record.
#
# Mechanics (filesystem scan — also works for untracked new files):
#  - `fun record(` on HistoryRepository is declared exactly once (:core:data).
#  - No file outside :playback main recorder + :core:data repository calls
#    `.record(` on a history repo (tests excluded; UI layers therefore cannot
#    double-record).
#
# Usage: scripts/check_history_write_path.sh   (run from repo root)

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || echo .)"
cd "$REPO_ROOT"

violations=0

DECLS=$(grep -rnE 'suspend fun record\(' core/data/src/main --include='*.kt' || true)
COUNT=$(echo "$DECLS" | grep -c 'HistoryRepository\.kt' || true)
if [ "$COUNT" -ne 1 ]; then
  echo "HISTORY WRITE PATH VIOLATION: HistoryRepository.record must be declared exactly once in core/data (found $COUNT)"
  echo "$DECLS"
  violations=$((violations + 1))
fi

BAD_CALLS=$(grep -rnE '\.record\(' \
  playback/src/main core/data/src/main app/src/main designui/src/main catalog/src/main core/model/src/main \
  --include='*.kt' 2>/dev/null \
  | grep -v 'core/data/src/main/kotlin/com/sway/core/data/HistoryRepository.kt' \
  | grep -v 'playback/src/main/kotlin/com/sway/playback/HistoryRecorder.kt' || true)
if [ -n "$BAD_CALLS" ]; then
  echo "HISTORY WRITE PATH VIOLATION: .record( called outside the service-side recorder:"
  echo "$BAD_CALLS"
  violations=$((violations + 1))
fi

if [ "$violations" -gt 0 ]; then
  echo "History write-path audit FAILED with $violations violation(s)."
  exit 1
fi

echo "History write-path audit OK: recording is declared once and invoked only by the service-side recorder."
