#!/usr/bin/env bash
# Counts the cells a calibration run wrote. Maven's exit code says that no test failed, not that the cells ran (a run
# whose tag filter leaves them out passes too), so the gates and nightly count the rows of the run's tsv.
#
#   scripts/calibration-cells.sh <tsv> <row> <expected>
#
#   <tsv>       jq-core/target/calibration-column.tsv (the ticket column) or jq-core/target/calibration.tsv (the matrix)
#   <row>       the first field of a cell's row: check (the column) or cell (the matrix)
#   <expected>  the cells of a full run
#
# Prints "calibration: <n> of <expected> <row> rows in <tsv>, <time-ms> ms", the time the test wrote in its time-ms
# row, or "no time-ms row" in its place. Exit codes: 0 every cell ran, 1 a missing tsv or another count, 2 usage.
set -uo pipefail
if [ $# -ne 3 ]; then
  echo "usage: scripts/calibration-cells.sh <tsv> <row> <expected>" >&2
  exit 2
fi
tsv=$1 row=$2 expected=$3
if [ ! -f "$tsv" ]; then
  echo "calibration: no $tsv: the cells did not run (F-91)"
  exit 1
fi
n=$(awk -F'\t' -v row="$row" '$1 == row { n++ } END { print n + 0 }' "$tsv")
ms=$(awk -F'\t' '$1 == "time-ms" { t = $2 } END { print t }' "$tsv")
echo "calibration: $n of $expected $row rows in $tsv, ${ms:-no time-ms row}${ms:+ ms}"
[ "$n" -eq "$expected" ]
