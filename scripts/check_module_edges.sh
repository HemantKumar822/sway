#!/usr/bin/env bash
# Edge audit (AR-1 / AD-5): verifies that every inter-module project dependency in the
# build files matches the architecture's allowed-edge set exactly. Any undeclared edge
# fails with the offending path named.
#
# Usage: scripts/check_module_edges.sh   (run from repo root)

set -euo pipefail

declare -A ALLOWED=(
  [":app"]=":core:model :core:database :core:data :catalog :playback :designui"
  [":designui"]=":core:model"
  [":playback"]=":core:model :core:data"
  [":core:data"]=":core:model :core:database"
  [":core:database"]=":core:model"
  [":catalog"]=":core:model"
  [":core:model"]=""
)

modules=(
  ":app" ":designui" ":playback" ":core:data" ":core:database" ":catalog" ":core:model"
)

path_for() {
  local module="$1"
  local suffix="${module#:}"          # strip leading ':'
  suffix="${suffix//:/\/}"            # ':' -> '/'
  if [[ "$module" == ":app" ]]; then
    echo "app/build.gradle.kts"
  else
    echo "${suffix}/build.gradle.kts"
  fi
}

violations=0

for module in "${modules[@]}"; do
  file="$(path_for "$module")"
  [[ -f "$file" ]] || continue

  declared=$(grep -oE 'project\(":[a-zA-Z0-9:.]+"\)' "$file" \
    | sed -E 's/^project\("([^"]+)"\)$/\1/' | sort -u || true)

  allowed="${ALLOWED[$module]}"

  while IFS= read -r edge; do
    [[ -z "$edge" ]] && continue
    ok=0
    for candidate in $allowed; do
      [[ "$edge" == "$candidate" ]] && ok=1 && break
    done
    if [[ "$ok" -ne 1 ]]; then
      echo "EDGE VIOLATION: ${file} declares '${edge}' which is not in AD-5's allowed set for ${module}"
      violations=$((violations + 1))
    fi
  done <<< "$declared"
done

if (( violations > 0 )); then
  echo "Edge audit FAILED: ${violations} violation(s)."
  exit 1
fi

echo "Edge audit OK: all module dependencies match the AD-5 allowed set."
