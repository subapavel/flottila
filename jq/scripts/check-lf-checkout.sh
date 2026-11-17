#!/usr/bin/env bash
# Every tracked file checks out byte-identical on Windows and on Linux (#49). Goldens, corpus cases, contract files,
# canonical fixtures and generated types are compared byte by byte, so a Windows checkout (Git for Windows sets
# core.autocrlf=true) must not turn their LF into CRLF. .gitattributes decides that. Run from any directory; it
# changes to the repository root of the worktree it lives in.
#
#   scripts/check-lf-checkout.sh
#
# It checks out the index twice into a temporary directory, as Linux does (core.autocrlf=false, core.eol=lf) and as
# Windows does (core.autocrlf=true, core.eol=crlf), with the .gitattributes of the working tree and the index, and
# lists every file whose bytes differ. It also renormalizes a copy of the index (git add --renormalize) and lists
# every file whose committed bytes .gitattributes would change, such as the CRLF of mvnw.cmd. Neither the working
# tree nor the index is touched. Stage a changed .gitattributes first. Exit 1 if any file differs.
set -euo pipefail

cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

git -c core.autocrlf=false -c core.eol=lf checkout-index --all --prefix="$tmp/linux/"
git -c core.autocrlf=true -c core.eol=crlf checkout-index --all --prefix="$tmp/windows/"

status=0
if diff -r -q "$tmp/linux" "$tmp/windows" > "$tmp/differ"; then
  echo "every tracked file checks out byte-identical with core.autocrlf=true"
else
  echo "these files check out with other bytes with core.autocrlf=true (Windows):"
  sed -e "s|^Files $tmp/linux/\(.*\) and .* differ$|  \1|" "$tmp/differ"
  status=1
fi

cp "$(git rev-parse --git-path index)" "$tmp/index"
git ls-files --stage > "$tmp/staged"
GIT_INDEX_FILE="$tmp/index" git add --renormalize .
GIT_INDEX_FILE="$tmp/index" git ls-files --stage > "$tmp/renormalized"
if diff -q "$tmp/staged" "$tmp/renormalized" > /dev/null; then
  echo "git add --renormalize changes no tracked file"
else
  echo "git add --renormalize would change these files:"
  diff "$tmp/staged" "$tmp/renormalized" | sed -n -e 's/^> [0-9]* [0-9a-f]* [0-9]*\t/  /p'
  status=1
fi
exit "$status"
