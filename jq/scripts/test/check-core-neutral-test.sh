#!/usr/bin/env bash
# Script test for scripts/check-core-neutral.sh: a scratch repository with the script and a query-core/ of its own.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fails=0
check() { if [ "$2" != "$3" ]; then echo "FAIL $1: expected '$3' got '$2'"; fails=1; else echo "ok   $1"; fi; }

git init -q -b main "$tmp/r"
mkdir -p "$tmp/r/scripts" "$tmp/r/query-core/src/dev/rail8/jq/query/ir"
cp "$here/../check-core-neutral.sh" "$tmp/r/scripts/"
run() { (cd "$tmp/r" && bash scripts/check-core-neutral.sh >/dev/null 2>&1); echo $?; }

# The project's names (the package root dev.rail8.jq, the coordinates rail8-jq-*) are names, not the word jq.
cat > "$tmp/r/query-core/src/dev/rail8/jq/query/ir/Node.java" <<'JAVA'
package dev.rail8.jq.query.ir;
// see dev/rail8/jq/query and the artifact rail8-jq-query-core
public sealed interface Node permits Leaf {}
JAVA
check "project names pass" "$(run)" 0

echo '// reads JSON' >> "$tmp/r/query-core/src/dev/rail8/jq/query/ir/Node.java"
check "the word json fails" "$(run)" 1

printf 'package dev.rail8.jq.query.ir;\npublic sealed interface Node permits Leaf {}\n' \
  > "$tmp/r/query-core/src/dev/rail8/jq/query/ir/Node.java"
echo '// a jq filter' >> "$tmp/r/query-core/src/dev/rail8/jq/query/ir/Node.java"
check "the word jq fails" "$(run)" 1

printf 'package dev.rail8.jq.query.ir;\n' > "$tmp/r/query-core/src/dev/rail8/jq/query/ir/Node.java"
mkdir -p "$tmp/r/query-core/src/jq"
echo 'x' > "$tmp/r/query-core/src/jq/A.txt"
check "jq as a path segment outside the names fails" "$(run)" 1

exit "$fails"
