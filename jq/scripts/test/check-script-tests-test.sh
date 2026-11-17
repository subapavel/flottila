#!/usr/bin/env bash
# Script test for scripts/check-script-tests.sh (#42). Throwaway roots with a ci.yml and script tests.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
tool=$here/../check-script-tests.sh
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fails=0
check() { if [ "$2" != "$3" ]; then echo "FAIL $1: expected '$3' got '$2'"; fails=1; else echo "ok   $1"; fi; }
run() { bash "$tool" "$@" > "$tmp/out" 2>&1; echo $?; }

mk() { # mk <name> <ci.yml text> <test names...>
  local r=$tmp/$1 ci=$2
  shift 2
  mkdir -p "$r/.github/workflows" "$r/scripts/test"
  printf '%s\n' "$ci" > "$r/.github/workflows/ci.yml"
  for n in "$@"; do : > "$r/scripts/test/$n"; done
  echo "$r"
}

r=$(mk glob 'run: for t in scripts/test/*-test.sh; do bash "$t"; done' a-test.sh b-test.sh)
check "glob covers every file" "$(run "$r")" 0

r=$(mk list 'run: bash scripts/test/a-test.sh && bash scripts/test/b-test.sh' a-test.sh b-test.sh)
check "a list naming every file passes" "$(run "$r")" 0

r=$(mk missing 'run: bash scripts/test/a-test.sh' a-test.sh b-test.sh)
check "a file missing from ci.yml fails" "$(run "$r")" 1
check "the missing file is named" "$(grep -c 'scripts/test/b-test.sh' "$tmp/out")" 1
check "a present file is not named" "$(grep -c 'scripts/test/a-test.sh' "$tmp/out")" 0

r=$(mk none 'run: echo hi')
check "no script tests passes" "$(run "$r")" 0

mkdir -p "$tmp/noci"
check "no ci.yml is rc 2" "$(run "$tmp/noci")" 2
check "two arguments is rc 2" "$(run a b)" 2
check "the real repository passes" "$(run)" 0

[ $fails -eq 0 ] && echo "ALL PASS"
exit $fails
