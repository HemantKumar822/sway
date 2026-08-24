#!/usr/bin/env bash
cd "$(dirname "$0")/.."
for s in scripts/check_*.sh; do
  if bash "$s" >/dev/null 2>&1; then
    echo "$s: exit 0"
  else
    echo "$s: exit $?"
  fi
done
