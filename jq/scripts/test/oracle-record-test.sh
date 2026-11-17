#!/usr/bin/env bash
# Script test for scripts/oracle-record.sh (#27, ADR-0020). Runs the reference jq at /usr/local/bin/jq-ref; every
# case file lives in a throwaway directory. One case waits for the 10 s timeout.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
tool=$here/../oracle-record.sh
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fails=0
check() { if [ "$2" != "$3" ]; then echo "FAIL $1: expected '$3' got '$2'"; fails=1; else echo "ok   $1"; fi; }
has() { if grep -qF -- "$2" "$3"; then echo "ok   $1"; else echo "FAIL $1: no '$2' in $3:"; sed 's/^/     /' "$3"; fails=1; fi; }
run() { bash "$tool" "$@" > "$tmp/out" 2>&1; echo $?; }

ref_sha=$(sha256sum /usr/local/bin/jq-ref | cut -d' ' -f1)

# --- record: header, outputs, exit codes, files and the exact environment ---
cat > "$tmp/t.cases" <<'CASES'
# a comment line
case t/compact
arg -c
filter .
stdin {"a":1}\n
end

case t/type-error
filter .a
stdin [1]
end

case t/file
arg -c
filter .
arg in.json
file in.json {"b":\t2}\n
end

case t/env
arg -nc
filter [$ENV.JQJ_PROBE, ($ENV|keys)]
env JQJ_PROBE=x\\y
end

case t/utf8-argument
arg -n
filter "é"
end
CASES
check "record exits 0" "$(run "$tmp/t.cases")" 0
rec=$tmp/t.rec
has "header names jq-ref and its sha" "# jq-1.8.2 sha256 $ref_sha" "$rec"
has "stdout recorded with escapes" 'stdout {"a":1}\n' "$rec"
has "exit code recorded" 'rc 5' "$rec"
has "stderr recorded" 'stderr jq: error (at <stdin>:0): Cannot index array with string ("a")\n' "$rec"
has "the case's file is in the working directory" 'stdout {"b":2}\n' "$rec"
has "environment is exactly HOME TZ LC_ALL and the case's env" 'stdout ["x\\\\y",["HOME","JQJ_PROBE","LC_ALL","TZ"]]\n' "$rec"
has "a non-ASCII argument reaches jq-ref as UTF-8" 'stdout "é"\n' "$rec"
check "verify of a fresh recording exits 0" "$(run --verify "$rec")" 0

# --- a hand-edited recording is flagged by --verify ---
cp "$rec" "$tmp/keep.rec"
sed -i 's/^stdout {"a":1}\\n$/stdout {"a":2}\\n/' "$rec"
check "verify flags a hand-edited stdout" "$(run --verify "$rec")" 1
has "the flagged line is shown" 'stdout {"a":2}\n' "$tmp/out"
cp "$tmp/keep.rec" "$rec"
sed -i "s/$ref_sha/0000/" "$rec"
check "verify flags a different jq-ref sha" "$(run --verify "$rec")" 1
cp "$tmp/keep.rec" "$rec"
sed -i 's/^filter \.a$/filter .b/' "$tmp/t.cases"
check "verify flags a case changed after its recording" "$(run --verify "$rec")" 1
has "the changed def is shown" 'def ' "$tmp/out"

# --- refused case files: hand-written expected output, bad escape, wrong family ---
printf 'case u/x\nfilter .\nstdout {}\\n\nend\n' > "$tmp/u.cases"
check "a hand-written expected output is refused" "$(run "$tmp/u.cases")" 1
has "the refusal names the key" "stdout" "$tmp/out"
check "a refused file writes no recording" "$(test -e "$tmp/u.rec"; echo $?)" 1
printf 'case v/x\nfilter .\nstdin \\q\nend\n' > "$tmp/v.cases"
check "an unknown escape is refused" "$(run "$tmp/v.cases")" 1
printf 'case other/x\nfilter .\nend\n' > "$tmp/w.cases"
check "a case outside its file's family is refused" "$(run "$tmp/w.cases")" 1

# --- the twice-run rule and the timeout ---
printf 'case n/now\narg -n\nfilter now\nend\n' > "$tmp/n.cases"
check "a case whose two runs differ is rejected" "$(run "$tmp/n.cases")" 1
has "the rejection says why" "differ" "$tmp/out"
printf 'case s/slow\narg -n\nfilter last(range(1e15))\nend\n' > "$tmp/s.cases"
check "a case over 10 s is rejected" "$(run "$tmp/s.cases")" 1
has "the rejection names the timeout" "10 s" "$tmp/out"

# --- usage ---
check "no argument is a usage error" "$(run)" 2
check "--verify of a non-.rec file is a usage error" "$(run --verify "$tmp/t.cases")" 2

exit "$fails"
