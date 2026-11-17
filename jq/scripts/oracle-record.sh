#!/usr/bin/env bash
# Records differential cases from the reference jq at /usr/local/bin/jq-ref (ADR-0020). Expected outputs are
# observed here, never written by hand.
#
#   scripts/oracle-record.sh <family.cases>...          writes <family>.rec beside each case file
#   scripts/oracle-record.sh --verify <family.rec>...   re-records in memory; exit 1 on any byte difference
#   scripts/oracle-record.sh --generate values-v1|core-v1 <from> <to>
#                                                       writes oracle/src/test/resources/diff/gen/<generator>.cases
#                                                       and .rec for the seeds [from, to) (ADR-0025, #58)
#
# Each case runs twice in a fresh directory with its files and an environment of exactly HOME, TZ=UTC, LC_ALL=C
# and its env, under a 10 s timeout; two runs that differ reject the case. A generated seed with rc 3 or a timeout
# is dropped: a "# dropped <seed> rc3|timeout" line in the case file's header. The case format, the generators and
# the recorder are oracle/src/main/java/.../oracle/{diff,gen,record}; this script compiles them with javac and needs
# no Maven build. Exit codes: 0 ok, 1 a rejected case, a refused file or a difference, 2 usage.
set -uo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
src=$root/oracle/src/main/java/io/github/subapavel/jqj/oracle
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
javac -Xlint:all -Werror -d "$classes" \
  "$src"/diff/CaseFormat.java "$src"/diff/CaseDirectory.java "$src"/diff/DiffCase.java \
  "$src"/diff/Outcome.java "$src"/diff/Recording.java "$src"/gen/SplitMix64.java "$src"/gen/ValuesV1.java \
  "$src"/gen/CoreV1.java "$src"/record/Recorder.java 2> "$classes/javac.log" || {
  cat "$classes/javac.log" >&2
  exit 2
}
# --generate writes below the repository's differential corpus, whatever the working directory.
if [ "${1:-}" = --generate ]; then set -- "$@" "$root/oracle/src/test/resources/diff"; fi
# The recorder's own locale decides how it encodes the arguments it passes to jq-ref: UTF-8 here. jq-ref itself
# gets LC_ALL=C from the recorder.
LC_ALL=C.UTF-8 java -cp "$classes" io.github.subapavel.jqj.oracle.record.Recorder "$@"
