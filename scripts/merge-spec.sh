#!/usr/bin/env bash
# Spec merge commit message (#55, F-28, docs/WORKFLOW.md section 5 step 5). Reads PR <pr>'s head SHA, head branch
# spec/<n>-<slug> and title (read-only gh api), writes the body "Ticket: #<n>", the co-author line and
# "Signed-off-by: $GIT_AUTHOR_NAME <$GIT_AUTHOR_EMAIL>" to a file, checks subject plus body with
# scripts/check-trailers.sh --message, then prints the merge command and the body file path. Only with --execute
# does it run that command; the merge into main stays an owner decision.
#
#   scripts/merge-spec.sh <pr-number> [--execute]
#
# Exit codes: 0 ok (with --execute: the merge command's own code), 1 a missing variable, an unreadable PR, a head
# that is not a spec branch, no temporary directory or a refused body, 2 usage.
set -uo pipefail
usage() { echo "usage: scripts/merge-spec.sh <pr-number> [--execute]" >&2; exit 2; }
pr=
execute=0
for a in "$@"; do
  case "$a" in
    --execute) execute=1 ;;
    '' | *[!0-9]*) usage ;;
    *) [ -z "$pr" ] || usage; pr=$a ;;
  esac
done
[ -n "$pr" ] || usage
for v in GIT_AUTHOR_NAME GIT_AUTHOR_EMAIL; do
  if [ -z "${!v-}" ]; then
    echo "$v is not set; the Signed-off-by line needs it. Nothing was merged." >&2
    exit 1
  fi
done
cd "$(dirname "$0")/.." || exit 1
if ! info=$(gh api "repos/{owner}/{repo}/pulls/$pr" --jq '.head.sha, .head.ref, .title'); then
  echo "cannot read PR #$pr with gh api. Nothing was merged." >&2
  exit 1
fi
{ read -r sha; read -r ref; IFS= read -r title; } <<< "$info"
if ! [[ "$ref" =~ ^spec/([0-9]+)- ]]; then
  echo "PR #$pr's head '$ref' is not a spec branch spec/<n>-<slug>. Nothing was merged." >&2
  exit 1
fi
spec=${BASH_REMATCH[1]}
# one leading "Spec <spec>" plus ": " or a spaced em or en dash is dropped; any other title is kept as it is
prefix="^Spec $spec(:| —| –) +(.*)$"
if [[ "$title" =~ $prefix ]]; then
  title=${BASH_REMATCH[2]}
fi
subject="Merge spec $spec \"$title\" (#$pr)"
dir=$(mktemp -d "${TMPDIR:-/tmp}/merge-spec-$pr.XXXXXX") || exit 1
body=$dir/body.txt
printf 'Ticket: #%s\nCo-authored-by: Claude <noreply@anthropic.com>\nSigned-off-by: %s <%s>\n' \
  "$spec" "$GIT_AUTHOR_NAME" "$GIT_AUTHOR_EMAIL" > "$body"
printf '%s\n\n%s\n' "$subject" "$(< "$body")" > "$dir/message.txt"
if ! scripts/check-trailers.sh --message "$dir/message.txt"; then
  echo "the merge message $dir/message.txt fails scripts/check-trailers.sh. Nothing was merged." >&2
  exit 1
fi
cmd=(gh pr merge "$pr" --merge --match-head-commit "$sha" --subject "$subject" --body-file "$body")
echo "body file: $body"
printf '%q' "${cmd[0]}"
printf ' %q' "${cmd[@]:1}"
echo
if [ "$execute" = 0 ]; then
  echo "not merged: run the command above, or re-run with --execute"
  exit 0
fi
exec "${cmd[@]}"
