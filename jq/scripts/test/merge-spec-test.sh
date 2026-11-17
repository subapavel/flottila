#!/usr/bin/env bash
# Script test for scripts/merge-spec.sh (#55). A stub gh first on PATH answers the PR reads and records every
# call, so the real gh is never run; a throwaway repo with a bare origin shows that nothing is pushed.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fails=0
check() { if [ "$2" != "$3" ]; then echo "FAIL $1: expected '$3' got '$2'"; fails=1; else echo "ok   $1"; fi; }

sha=0123456789abcdef0123456789abcdef01234567
bin=$tmp/bin
mkdir "$bin"
ghlog=$tmp/gh.log
# the stub answers "gh api repos/{owner}/{repo}/pulls/<n>" from $tmp/pr-<n> (head ref, then title)
cat > "$bin/gh" <<STUB
#!/usr/bin/env bash
printf '%s\n' "\$*" >> "$ghlog"
case "\$1 \$2" in
  "api repos/{owner}/{repo}/pulls/"*)
    f=$tmp/pr-\${2##*/}
    [ -f "\$f" ] || { echo "stub gh: no such PR" >&2; exit 1; }
    echo $sha
    cat "\$f"
    ;;
  "pr merge") exit 0 ;;
  *) echo "stub gh: unexpected call" >&2; exit 1 ;;
esac
STUB
chmod +x "$bin/gh"
pr() { printf '%s\n%s\n' "$2" "$3" > "$tmp/pr-$1"; }
# PR #48's real title
pr 48 spec/22-walking-skeleton 'Spec 22: Walking skeleton (`.` and `.a` through every layer, tooling, oracles, ratchet)'
s48='Merge spec 22 "Walking skeleton (`.` and `.a` through every layer, tooling, oracles, ratchet)" (#48)'
pr 49 t/49-some-ticket 'Some ticket'
export PATH="$bin:$PATH"
export TMPDIR=$tmp
export GIT_AUTHOR_NAME="O Wner" GIT_AUTHOR_EMAIL=o@x.y

git init -q --bare -b main "$tmp/origin.git"
repo=$tmp/repo
git init -q -b main "$repo"
git -C "$repo" config user.email a@b.c
git -C "$repo" config user.name t
git -C "$repo" remote add origin "$tmp/origin.git"
git -C "$repo" commit -q --allow-empty -m base
git -C "$repo" -c push.negotiate=false push -q origin main
# the script works in the repository it lives in, so the throwaway repo holds its own copies
mkdir "$repo/scripts"
cp "$here/../merge-spec.sh" "$here/../check-trailers.sh" "$repo/scripts/"
head0=$(git -C "$repo" rev-parse HEAD)
origin0=$(git -C "$repo" ls-remote origin)

run() { : > "$ghlog"; out=$(cd "$repo" && "$@" 2> "$tmp/err"); rc=$?; err=$(< "$tmp/err"); }
merges() { grep -c '^pr merge' "$ghlog"; }
untouched() {
  check "$1: local HEAD unchanged" "$(git -C "$repo" rev-parse HEAD)" "$head0"
  check "$1: origin unchanged" "$(git -C "$repo" ls-remote origin)" "$origin0"
}

# default: the body is written and checked, the command is printed, nothing is merged or pushed
run scripts/merge-spec.sh 48
check "dry run rc" "$rc" 0
body=$(printf '%s\n' "$out" | sed -n 's/^body file: //p')
check "dry run names the body file" "$([ -f "$body" ] && echo yes)" yes
check "body is the trailer block" "$(cat "$body")" "$(printf 'Ticket: #22\nCo-authored-by: Claude <noreply@anthropic.com>\nSigned-off-by: O Wner <o@x.y>')"
printf '%s\n\n' "$s48" > "$tmp/msg"
cat "$body" >> "$tmp/msg"
"$repo/scripts/check-trailers.sh" --message "$tmp/msg" > "$tmp/check" 2>&1
check "subject plus body passes check-trailers.sh" "$?" 0
cmd=$(printf '%s\n' "$out" | grep '^gh pr merge ')
check "dry run prints one merge command" "$(printf '%s\n' "$cmd" | grep -c .)" 1
check "dry run calls no merge" "$(merges)" 0
untouched "dry run"

# the printed command is exact: run as printed, the stub sees the subject and the body file
: > "$ghlog"
(cd "$repo" && eval "$cmd")
check "printed command" "$(cat "$ghlog")" "pr merge 48 --merge --match-head-commit $sha --subject $s48 --body-file $body"

# --execute runs that same command
run scripts/merge-spec.sh 48 --execute
check "execute rc" "$rc" 0
body2=$(printf '%s\n' "$out" | sed -n 's/^body file: //p')
check "execute merges once with the checked body" "$(grep '^pr merge' "$ghlog")" "pr merge 48 --merge --match-head-commit $sha --subject $s48 --body-file $body2"

# the title loses one leading "Spec <n>" plus ':' or a dash only when <n> is the head branch's spec number
subject_of() { # <pr> -> the subject the printed command passes to gh
  run scripts/merge-spec.sh "$1"
  : > "$ghlog"
  (cd "$repo" && eval "$(printf '%s\n' "$out" | grep '^gh pr merge ')")
  sed -n 's/^pr merge .* --subject \(.*\) --body-file .*$/\1/p' "$ghlog"
}
pr 51 spec/22-x 'Walking skeleton'
check "unprefixed title unchanged" "$(subject_of 51)" 'Merge spec 22 "Walking skeleton" (#51)'
pr 52 spec/22-x 'Spec 23: Other spec'
check "other spec number unchanged" "$(subject_of 52)" 'Merge spec 22 "Spec 23: Other spec" (#52)'
pr 53 spec/22-x 'Spec 220: Longer number'
check "longer number unchanged" "$(subject_of 53)" 'Merge spec 22 "Spec 220: Longer number" (#53)'
pr 57 spec/22-x 'Draft Spec 22: not leading'
check "prefix not leading unchanged" "$(subject_of 57)" 'Merge spec 22 "Draft Spec 22: not leading" (#57)'
pr 58 spec/22-x 'Spec 22:glued'
check "prefix without a space unchanged" "$(subject_of 58)" 'Merge spec 22 "Spec 22:glued" (#58)'
pr 54 spec/22-x 'Spec 22 — Em dash'
check "em dash prefix stripped" "$(subject_of 54)" 'Merge spec 22 "Em dash" (#54)'
pr 55 spec/22-x 'Spec 22 – En dash'
check "en dash prefix stripped" "$(subject_of 55)" 'Merge spec 22 "En dash" (#55)'
pr 56 spec/22-x 'Spec 22: Spec 22: twice'
check "only one prefix stripped" "$(subject_of 56)" 'Merge spec 22 "Spec 22: twice" (#56)'

# a missing author variable fails before any gh call, naming the variable
for v in GIT_AUTHOR_NAME GIT_AUTHOR_EMAIL; do
  run env -u "$v" scripts/merge-spec.sh 48 --execute
  check "no $v rc" "$rc" 1
  case "$err" in *"$v"*) echo "ok   no $v named";; *) echo "FAIL no $v not named: $err"; fails=1;; esac
  check "no $v calls no gh" "$(grep -c . "$ghlog")" 0
  untouched "no $v"
done
run env GIT_AUTHOR_NAME= scripts/merge-spec.sh 48 --execute
check "empty GIT_AUTHOR_NAME rc" "$rc" 1

# a body that check-trailers.sh refuses (an author value carrying a second Signed-off-by) merges nothing
run env GIT_AUTHOR_EMAIL="$(printf 'o@x.y>\nSigned-off-by: E <e@x.y')" scripts/merge-spec.sh 48 --execute
check "refused body rc" "$rc" 1
case "$out$err" in *"Signed-off-by lines"*) echo "ok   refused body shows the check";; *) echo "FAIL refused body: $out $err"; fails=1;; esac
check "refused body calls no merge" "$(merges)" 0

# a PR whose head is not a spec branch, or a PR gh cannot read, merges nothing
run scripts/merge-spec.sh 49 --execute
check "not a spec branch rc" "$rc" 1
case "$err" in *"t/49-some-ticket"*) echo "ok   not a spec branch named";; *) echo "FAIL not a spec branch: $err"; fails=1;; esac
check "not a spec branch calls no merge" "$(merges)" 0
run scripts/merge-spec.sh 50 --execute
check "unreadable PR rc" "$rc" 1
check "unreadable PR calls no merge" "$(merges)" 0

# no temporary directory for the body: nothing is merged
run env TMPDIR="$tmp/missing" scripts/merge-spec.sh 48 --execute
check "no temp dir rc" "$rc" 1
check "no temp dir calls no merge" "$(merges)" 0

# run from another directory, the script still finds check-trailers.sh in its own repository
: > "$ghlog"
out=$(cd "$tmp" && "$repo/scripts/merge-spec.sh" 48 2>&1)
check "other cwd rc" "$?" 0
check "other cwd prints the command" "$(printf '%s\n' "$out" | grep -c '^gh pr merge ')" 1

# usage
for args in "" "abc" "48 49" "48 --force"; do
  # shellcheck disable=SC2086
  run scripts/merge-spec.sh $args
  check "usage '$args' rc" "$rc" 2
  check "usage '$args' calls no gh" "$(grep -c . "$ghlog")" 0
done
run scripts/merge-spec.sh "" 48
check "usage empty argument rc" "$rc" 2

[ "$fails" = 0 ] && echo "ALL PASS"
exit "$fails"
