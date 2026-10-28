#!/bin/sh
# Deploys a package directory to a Flottila node (docs/QUICKSTART.md): zips the directory with package.yaml at the archive
# root and PUTs it to ${FLOTTILA_ADMIN:-http://localhost:8081}/admin/packages/<name>. Prints the node's answer; exits 0 on a
# 2xx answer, 1 on any other, 2 on a usage error (curl's own code when the node cannot be reached).
# <name> defaults to the directory's name and must equal the name in package.yaml. FLOTTILA_ADMIN_TOKEN, when set, is sent as
# the bearer token (from a file in a private temp directory, never on curl's command line). Needs curl, and zip (else the JDK's jar).
#
#   deploy/quickstart/deploy.sh packages/demo
set -eu

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
    echo "usage: $0 <package-dir> [name]" >&2
    exit 2
fi
dir=$1
if [ ! -f "$dir/package.yaml" ]; then
    echo "deploy.sh: $dir/package.yaml not found" >&2
    exit 2
fi
dir=$(cd "$dir" && pwd)
name=${2:-$(basename "$dir")}
admin=${FLOTTILA_ADMIN:-http://localhost:8081}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
if command -v zip > /dev/null 2>&1; then
    (cd "$dir" && zip -q -r -D -X "$work/package.zip" .)
else
    jar --create --no-manifest --file "$work/package.zip" -C "$dir" .
fi

if [ -n "${FLOTTILA_ADMIN_TOKEN:-}" ]; then
    printf 'Authorization: Bearer %s\n' "$FLOTTILA_ADMIN_TOKEN" > "$work/headers"
else
    : > "$work/headers"
fi
status=$(curl -sS -o "$work/answer" -w '%{http_code}' -X PUT -H 'Content-Type: application/zip' -H "@$work/headers" \
    --data-binary "@$work/package.zip" "$admin/admin/packages/$name")
cat "$work/answer"
echo
case $status in
    2??) exit 0 ;;
    *) echo "deploy.sh: the node answered $status" >&2; exit 1 ;;
esac
