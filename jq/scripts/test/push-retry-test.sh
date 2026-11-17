#!/usr/bin/env bash
# Script test for scripts/push-retry.sh (#126): a bare repo whose pre-receive hook rejects the first N pushes.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
tool=$here/../push-retry.sh
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fails=0
check() { if [ "$2" != "$3" ]; then echo "FAIL $1: expected '$3' got '$2'"; fails=1; else echo "ok   $1"; fi; }

export PUSH_RETRY_WAITS="0 0 0 0 0"
git init -q --bare -b main "$tmp/remote.git"
count=$tmp/count
limit=$tmp/limit
cat > "$tmp/remote.git/hooks/pre-receive" <<HOOK
#!/usr/bin/env bash
n=\$(cat "$count" 2>/dev/null || echo 0)
echo \$((n + 1)) > "$count"
if [ "\$n" -lt "\$(cat "$limit")" ]; then echo "fatal error in commit_refs_rules" >&2; exit 1; fi
HOOK
chmod +x "$tmp/remote.git/hooks/pre-receive"

git init -q -b main "$tmp/w"
git -C "$tmp/w" config user.email a@b.c
git -C "$tmp/w" config user.name t
git -C "$tmp/w" remote add origin "$tmp/remote.git"
git -C "$tmp/w" commit -q --allow-empty -m one

run() { # <rejections> -> sets rc, out, attempts
  echo 0 > "$count"; echo "$1" > "$limit"
  out=$("$tool" "$tmp/w" "main:refs/heads/main" 2>&1); rc=$?
  attempts=$(cat "$count")
}

run 0
check "first try rc" "$rc" 0
check "first try attempts" "$attempts" 1

git -C "$tmp/w" commit -q --allow-empty -m two
run 3
check "transient then ok rc" "$rc" 0
check "transient then ok attempts" "$attempts" 4

git -C "$tmp/w" commit -q --allow-empty -m three
run 99
check "always rejected rc" "$rc" 1
check "always rejected attempts" "$attempts" 6

# non-fast-forward: a different commit on the remote, no retry, no force
git clone -q "$tmp/remote.git" "$tmp/other" 2>/dev/null
git -C "$tmp/other" config user.email a@b.c
git -C "$tmp/other" config user.name t
git -C "$tmp/other" commit -q --allow-empty -m other
echo 0 > "$limit"
git -C "$tmp/other" push -q origin HEAD:refs/heads/main 2>/dev/null
git -C "$tmp/w" commit -q --allow-empty -m four
run 0
check "non-fast-forward rc" "$rc" 1
case "$out" in *waiting*) echo "FAIL non-fast-forward was retried"; fails=1;; *) echo "ok   non-fast-forward not retried";; esac
case "$out" in *rejected*) echo "ok   non-fast-forward printed";; *) echo "FAIL non-fast-forward not printed: $out"; fails=1;; esac

[ "$fails" = 0 ] && echo "ALL PASS"
exit "$fails"
