#!/usr/bin/env bash
# Flottila — Claude Code Cloud environment setup script.
# Runs as root on the Ubuntu 24.04 VM before Claude Code starts; the result is snapshotted when
# it finishes in under ~5 minutes. Self-contained (no repository paths), idempotent.
# Paste this file's contents into the environment's "Setup script" field.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

# 1. Temurin 25 next to the pre-installed JDK 21. GitHub release assets are on the default allowlist.
if [ ! -x /opt/temurin-25/bin/java ]; then
  api="https://api.github.com/repos/adoptium/temurin25-binaries/releases/latest"
  url="$(curl -fsSL "$api" \
        | grep -o '"browser_download_url": *"[^"]*OpenJDK25U-jdk_x64_linux_hotspot_[^"]*\.tar\.gz"' \
        | grep -v '\.sig' | head -1 | sed 's/.*"\(https:[^"]*\)"/\1/')"
  [ -n "$url" ] || { echo "Temurin 25 asset not found"; exit 1; }
  mkdir -p /opt/temurin-25
  curl -fsSL "$url" | tar -xz --strip-components=1 -C /opt/temurin-25
fi
for tool in java javac jar jshell jcmd jfr; do
  update-alternatives --install "/usr/bin/$tool" "$tool" "/opt/temurin-25/bin/$tool" 2500
  update-alternatives --set "$tool" "/opt/temurin-25/bin/$tool"
done
cat > /etc/profile.d/temurin25.sh <<'EOF'
export JAVA_HOME=/opt/temurin-25
export PATH="$JAVA_HOME/bin:$PATH"
EOF
/opt/temurin-25/bin/java -version

# 2. Small tools the workflow uses.
apt-get update -qq
apt-get install -y -qq --no-install-recommends jq postgresql-client >/dev/null

# 3. Pre-pull the stack images so the snapshot has them (tags pinned by the agent in P2-05;
#    a missing tag must not fail the setup).
if docker info >/dev/null 2>&1; then
  for img in postgres:17 apache/kafka:latest clickhouse/clickhouse-server:latest \
             valkey/valkey:8 chrislusf/seaweedfs:latest atmoz/sftp:alpine; do
    docker pull -q "$img" || echo "pull failed (ignored): $img"
  done
fi

echo "flottila setup done"
