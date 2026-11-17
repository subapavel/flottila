#!/usr/bin/env bash
# Fails when a scripts/test/*-test.sh is not run by the nightly workflow, .github/workflows/jq-nightly.yml at the
# repository's root, the folder above the engine's root.
#
#   scripts/check-script-tests.sh [root]    root, the engine's root, defaults to jq/
#
# The workflow runs the tests either through the glob scripts/test/*-test.sh (every file is covered) or by naming each
# file. Exit codes: 0 ok, 1 a test the workflow does not run, 2 usage or a missing workflow.
set -uo pipefail
[ $# -le 1 ] || { echo "usage: $0 [root]" >&2; exit 2; }
root=${1:-$(cd "$(dirname "$0")/.." && pwd)}
ci=$root/../.github/workflows/jq-nightly.yml
[ -f "$ci" ] || { echo "no $ci" >&2; exit 2; }
if grep -qF 'scripts/test/*-test.sh' "$ci"; then
  exit 0
fi
rc=0
for t in "$root"/scripts/test/*-test.sh; do
  [ -e "$t" ] || continue
  name=$(basename "$t")
  if ! grep -qF "scripts/test/$name" "$ci"; then
    echo "jq-nightly.yml does not run scripts/test/$name" >&2
    rc=1
  fi
done
exit $rc
