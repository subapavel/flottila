#!/usr/bin/env bash
# Counts the cells a calibration run wrote. Maven's exit code says that no test failed, not that the cells ran (a run
# whose tag filter leaves them out passes too), so the gates and nightly count the rows of the run's tsv. This script
# alone holds the counts; its callers pass the run's name.
#
#   scripts/calibration-cells.sh <tsv> column|matrix
#   scripts/calibration-cells.sh --expected column|matrix
#
#   column  the ticket column, jq-core/target/calibration-column.tsv: check rows, 2 modes × 14 shapes = 28
#   matrix  the matrix, jq-core/target/calibration.tsv: cell rows, -Xint and C1 on 4 threads (platform 256 KiB,
#           512 KiB, 1 MiB and virtual) and warm on the last 3, × 2 modes × 14 shapes = 224 + 84 = 308
#
# Prints "calibration: <n> of <expected> <row> rows in <tsv>, <time-ms> ms", the time the test wrote in its time-ms
# row, or "no time-ms row" in its place; with --expected, the bare count. Exit codes: 0 every cell ran (or the count
# printed), 1 a missing tsv or another count, 2 usage.
set -uo pipefail
usage() {
  echo "usage: scripts/calibration-cells.sh <tsv> column|matrix | --expected column|matrix" >&2
  exit 2
}
[ $# -eq 2 ] || usage
case $2 in
  column) row=check expected=28 ;;
  matrix) row=cell expected=308 ;;
  *) usage ;;
esac
if [ "$1" = --expected ]; then
  echo "$expected"
  exit 0
fi
tsv=$1
if [ ! -f "$tsv" ]; then
  echo "calibration: no $tsv: the cells did not run (F-91)"
  exit 1
fi
n=$(awk -F'\t' -v row="$row" '$1 == row { n++ } END { print n + 0 }' "$tsv")
ms=$(awk -F'\t' '$1 == "time-ms" { t = $2 } END { print t }' "$tsv")
echo "calibration: $n of $expected $row rows in $tsv, ${ms:-no time-ms row}${ms:+ ms}"
[ "$n" -eq "$expected" ]
