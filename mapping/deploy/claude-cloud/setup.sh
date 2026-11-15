#!/usr/bin/env bash
# Flottila Mapping — Claude Code Cloud environment setup script (v3, 2026-10-30).
# Runs as root on the Ubuntu 24.04 VM before Claude Code starts. Must finish in ~5 minutes to
# be snapshotted, so it does three things: install JDK 25 beside the pre-installed JDK 21, point
# Maven and the Maven Wrapper at the Google mirror of Central (Central answers HTTP 429 to the VM,
# ADR-0017), and pre-approve the project's Context7 MCP server in the user settings.
# Docker images, apt extras and Maven dependencies are fetched by the agent on first use.
# Self-contained (no repository paths), idempotent, reports every step.
# Paste this file's contents into the environment's "Setup script" field.
set -uo pipefail
log() { echo "[flottila-mapping-setup] $*"; }
JDK=/opt/temurin-25   # path is referenced by the JAVA_HOME environment variable; keep it even if the fallback vendor lands here

if [ -x "$JDK/bin/java" ]; then
  log "JDK already present at $JDK"
else
  mkdir -p "$JDK"
  tgz=/tmp/jdk25.tar.gz
  rm -f "$tgz"

  # Source 1: Eclipse Temurin 25 from the Adoptium GitHub release (GitHub release assets are on the default allowlist).
  api="https://api.github.com/repos/adoptium/temurin25-binaries/releases/latest"
  url="$(curl -fsSL -H 'Accept: application/vnd.github+json' "$api" 2>/dev/null \
        | grep -o '"browser_download_url": *"[^"]*OpenJDK25U-jdk_x64_linux_hotspot_[^"]*\.tar\.gz"' \
        | head -1 | sed 's/.*"\(https:[^"]*\)"/\1/')" || url=""
  if [ -n "$url" ]; then
    log "source 1: $url"
    curl -fsSL "$url" -o "$tgz" || { log "source 1 download failed"; rm -f "$tgz"; }
  else
    log "source 1: Temurin asset not found via api.github.com"
  fi

  # Source 2: Oracle JDK 25 (download.oracle.com is on the default allowlist; NFTC licence, dev use only).
  if [ ! -s "$tgz" ]; then
    url="https://download.oracle.com/java/25/latest/jdk-25_linux-x64_bin.tar.gz"
    log "source 2: $url"
    curl -fsSL "$url" -o "$tgz" || { log "source 2 download failed"; rm -f "$tgz"; }
  fi

  if [ -s "$tgz" ]; then
    tar -xzf "$tgz" --strip-components=1 -C "$JDK" || log "extract failed"
    rm -f "$tgz"
  fi

  # Source 3: Ubuntu package, if the archive carries it.
  if [ ! -x "$JDK/bin/java" ]; then
    log "source 3: apt openjdk-25-jdk"
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq >/dev/null 2>&1 || log "apt-get update reported errors (continuing)"
    if apt-get install -y -qq --no-install-recommends openjdk-25-jdk-headless >/dev/null 2>&1; then
      rmdir "$JDK" 2>/dev/null || true
      ln -sfn /usr/lib/jvm/java-25-openjdk-amd64 "$JDK"
    else
      log "source 3: package not available"
    fi
  fi
fi

if [ ! -x "$JDK/bin/java" ]; then
  log "FAILED: no JDK 25 could be installed from any source"
  exit 1
fi

for tool in java javac jar jshell jcmd jfr; do
  [ -x "$JDK/bin/$tool" ] || continue
  update-alternatives --install "/usr/bin/$tool" "$tool" "$JDK/bin/$tool" 2500 >/dev/null 2>&1 || log "alternatives install failed for $tool (continuing)"
  update-alternatives --set "$tool" "$JDK/bin/$tool" >/dev/null 2>&1 || log "alternatives set failed for $tool (continuing)"
done
cat > /etc/profile.d/jdk25.sh <<'EOF'
export JAVA_HOME=/opt/temurin-25
export PATH="$JAVA_HOME/bin:$PATH"
EOF

log "installed: $("$JDK/bin/java" -version 2>&1 | head -1)"

# Maven: Central rate-limits the VM (HTTP 429); the Google mirror of Central serves the same
# artefacts. The repository itself points at Central, so people outside the VM need nothing.
MIRROR=https://maven-central.storage-download.googleapis.com/maven2
m2="$HOME/.m2/settings.xml"
mkdir -p "$(dirname "$m2")"
if [ -f "$m2" ] && ! grep -q storage-download.googleapis.com "$m2"; then
  log "keeping existing $m2 (no Google mirror in it; add one by hand)"
else
  cat > "$m2" <<EOF
<settings>
  <mirrors>
    <mirror>
      <id>google-maven-central</id>
      <mirrorOf>central</mirrorOf>
      <url>$MIRROR</url>
    </mirror>
  </mirrors>
</settings>
EOF
  log "maven mirror written to $m2"
fi
echo "export MVNW_REPOURL=$MIRROR" > /etc/profile.d/mvnw-mirror.sh
log "MVNW_REPOURL exported in /etc/profile.d/mvnw-mirror.sh"

# Context7: `.mcp.json` servers stay "Pending approval" in a cloud session (nobody can approve
# them interactively), and `enabledMcpjsonServers` is honoured only in the user settings.
settings="$HOME/.claude/settings.json"
mkdir -p "$(dirname "$settings")"
if python3 -I - "$settings" <<'PY'
import json, os, sys
p = sys.argv[1]
d = json.load(open(p)) if os.path.exists(p) and os.path.getsize(p) else {}
servers = d.setdefault("enabledMcpjsonServers", [])
if "context7" not in servers:
    servers.append("context7")
json.dump(d, open(p, "w"), indent=2)
PY
then
  log "context7 approved in $settings"
else
  log "could not write $settings (continuing; context7 stays pending approval)"
fi
log "node: $(node --version 2>/dev/null || echo missing) · npm: $(npm --version 2>/dev/null || echo missing)"
log "done"
