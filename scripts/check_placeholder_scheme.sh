#!/usr/bin/env bash
# Placeholder scheme audit (AD-6 rule 6 / AR-5, story 4.3): the
# sway://pending/<sourceId> placeholder scheme is defined in exactly ONE object
# (PendingUri) inside :playback, and that owner must remain `internal`. No
# tracked code file outside the playback module may construct, mutate, or
# string-sniff placeholders. The WHOLE playback/ module directory is exempt
# from the scan (module-internal code and documentation of the law — e.g.
# comments in playback/build.gradle.kts).
#
# Mechanics:
# - Owner self-check: playback/src/main/kotlin/com/sway/playback/PendingUri.kt
#   must contain the literal "internal object PendingUri" — a visibility
#   regression back to public fails this audit.
# - Repo scan: all git-tracked *.kt, *.java, *.xml and *.kts files OUTSIDE
#   playback/, matched case-insensitively on 'sway://' or 'pendinguri'. Every
#   hit is named and fails the audit; no basename allowlist exists, so even a
#   file literally named PendingUri.kt outside :playback is a violation.
#
# Usage: scripts/check_placeholder_scheme.sh   (run from repo root; requires git)

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

OWNER="playback/src/main/kotlin/com/sway/playback/PendingUri.kt"

if ! grep -qF 'internal object PendingUri' "$OWNER"; then
  echo "PLACEHOLDER SCHEME VIOLATION: ${OWNER} no longer declares 'internal object PendingUri'"
  echo "(AD-6 rule 6 visibility guard): the placeholder scheme owner must stay module-internal."
  exit 1
fi

violations=0
while IFS= read -r file; do
  if grep -inE 'sway://|pendinguri' "$file" >/dev/null 2>&1; then
    echo "PLACEHOLDER SCHEME VIOLATION: ${file} references 'sway://' or 'PendingUri' outside :playback (AD-6 rule 6):"
    grep -inE 'sway://|pendinguri' "$file" || true
    violations=$((violations + 1))
  fi
done < <(git ls-files '*.kt' '*.java' '*.xml' '*.kts' | grep -v '^playback/')

if (( violations > 0 )); then
  echo "Placeholder scheme audit FAILED: ${violations} file(s) violate the single-owner law."
  exit 1
fi

echo "Placeholder scheme audit OK: no sway:// or PendingUri references in tracked code files outside :playback; PendingUri remains internal."
