#!/usr/bin/env bash
# Serializer ownership audit (story 7.3 / AD-8): the canonical QueueSnapshot
# serializer is owned EXCLUSIVELY by :core:data (QueueStateSerializer). No
# tracked Kotlin/Java file outside core/data may (de)serialize queue state —
# two snapshot shapes would silently break FR-25 restore.
#
# Mechanics: repo scan of *.kt/*.java outside core/data/src for the tell-tale
# markers of queue-state SERIALIZATION CODE (codec calls or JSON building).
# Consuming the VALUE types (QueueStateSerializer.RestoredSession etc.) is
# legitimate — the law forbids a second CODEC, not consumers. Also refuses
# destructive-migration escape hatches anywhere in the codebase (AD-8).
#
# Usage: scripts/check_serializer_ownership.sh   (run from repo root; git)

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

violations=0

while IFS= read -r file; do
  if grep -niE 'QueueStateSerializer\.(toJson|fromJson)|kotlinx\.serialization\.json' "$file" >/dev/null 2>&1; then
    echo "SERIALIZER OWNERSHIP VIOLATION: $file contains queue-state codec code outside :core:data"
    violations=$((violations + 1))
  fi
done < <(git ls-files '*.kt' '*.java' | grep -v '^core/data/')

if git grep -nE 'fallbackToDestructive[A-Za-z]*[[:space:]]*\(' -- '*.kt' '*.java' >/dev/null 2>&1; then
  echo "DESTRUCTIVE FALLBACK VIOLATION (AD-8): destructive-fallback APIs must never be invoked"
  git grep -nE 'fallbackToDestructive[A-Za-z]*[[:space:]]*\(' -- '*.kt' '*.java'
  violations=$((violations + 1))
fi

if [ ! -f "core/database/schemas/com.sway.core.database.SwayDatabase/1.json" ]; then
  echo "SCHEMA EXPORT MISSING: core/database/schemas/com.sway.core.database.SwayDatabase/1.json"
  violations=$((violations + 1))
fi

if [ "$violations" -gt 0 ]; then
  echo "Serializer ownership audit FAILED with $violations violation(s)."
  exit 1
fi

echo "Serializer ownership audit OK: queue-state serialization lives only in :core:data; schema v1 exported; no destructive fallback."
