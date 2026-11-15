#!/usr/bin/env bash
# Script test for the default PIT module list of scripts/gate-spec.sh (#123), in a scratch root with stubbed
# mvnw, java and scripts/pit-summary.sh: the real gate is never run. The stub records the modules it is given.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fails=0
check() { if [ "$2" != "$3" ]; then echo "FAIL $1: expected '$3' got '$2'"; fails=1; else echo "ok   $1"; fi; }

mkdir -p "$tmp/scripts" "$tmp/ratchet" "$tmp/bin"
cp "$here/../gate-spec.sh" "$tmp/scripts/gate-spec.sh"
printf '#!/usr/bin/env bash\nexit 0\n' > "$tmp/mvnw"
printf '#!/usr/bin/env bash\nexit 0\n' > "$tmp/bin/java"
# the stub records its arguments, one line per call
printf '#!/usr/bin/env bash\necho "$*" >> "$(dirname "$0")/../pit-calls"\n' > "$tmp/scripts/pit-summary.sh"
chmod +x "$tmp/mvnw" "$tmp/bin/java" "$tmp/scripts/pit-summary.sh"
printf 'engine/api\t1\t93\nengine/ir\t0\t61\nengine/xdm\t0\t483\n' > "$tmp/ratchet/mutation.tsv"

gate() { rm -f "$tmp/pit-calls"; (cd "$tmp" && PATH="$tmp/bin:$PATH" scripts/gate-spec.sh 1 "$@" > /dev/null 2>&1); }

gate
check "default: run call" "$(sed -n 1p "$tmp/pit-calls")" "--run .logs/1-pit.log engine/api engine/ir engine/xdm"
check "default: summary call" "$(sed -n 2p "$tmp/pit-calls")" "ratchet/mutation.tsv engine/api engine/ir engine/xdm"
gate engine/ir,engine/udf
check "explicit: run call" "$(sed -n 1p "$tmp/pit-calls")" "--run .logs/1-pit.log engine/ir engine/udf"

exit "$fails"
