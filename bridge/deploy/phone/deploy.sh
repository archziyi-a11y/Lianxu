#!/usr/bin/env bash
# Review before use. No operation is performed without an explicit phase.
set -euo pipefail
DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
CADDY=/home/admin/sasalele-release-20261014/project/modules/qm-sales-assistant/deploy/Caddyfile
BACKUP=${CADDY}.pre-lianxu-phone-20261104
CANDIDATE=/tmp/lianxu-phone-Caddyfile-candidate
EXPECTED=7271114f29fcf9606920d6e7ab09cee1f33ae26dfa667f065f65f80d3ec34983
RELEASE=/opt/lianxu-bridge/releases/7ed7b90
CFG=/etc/lianxu-phone/config.env
DB=/var/lib/lianxu-phone/phone.sqlite3
DOMAIN=https://xiaoshou.yilihuashi.com
sha() { sha256sum "$1" | awk '{print $1}'; }
require_root() { [ "$(id -u)" = 0 ] || { echo 'Run as root after review' >&2; exit 1; }; }
check_original() { [ "$(sha "$CADDY")" = "$EXPECTED" ] || { echo 'Caddyfile changed; stop and re-audit' >&2; exit 1; }; }
check_ports() { [ -z "$(ss -H -ltn '( sport = :18767 or sport = :18768 )')" ] || { echo 'Port conflict' >&2; exit 1; }; }
check_absent() {
  ! getent passwd lianxu-phone >/dev/null || { echo 'Account already exists' >&2; exit 1; }
  for p in /etc/lianxu-phone /var/lib/lianxu-phone /etc/systemd/system/lianxu-phone.service /etc/systemd/system/lianxu-phone-gateway.socket /etc/systemd/system/lianxu-phone-gateway.service "$BACKUP" "$CANDIDATE"; do
    [ ! -e "$p" ] || { echo "Target already exists: $p" >&2; exit 1; }
  done
}
verify_release() {
  python3 - "$RELEASE" <<'PY'
import hashlib,pathlib,sys
root=pathlib.Path(sys.argv[1])
expected={'server.py':'efc3769c8d9dd2e72110dbb5759f908babe8be06441a82019a4927ea57d82ee9',
          'store.py':'e9c1c4eecf62b86b650718b25df0dbf5008f37cb181b66a332dd578afac2ce27',
          'requirements.txt':'8ef6d7af0bfde08abc4b994d85d69e7332a659be515d6e3ac75f82c482647d98'}
for name,sha in expected.items():
    if hashlib.sha256((root/name).read_bytes().replace(b"\r\n",b"\n")).hexdigest()!=sha:
        raise SystemExit('Release changed; stop and re-audit')
PY
}
status() { curl -sS -o /dev/null -w '%{http_code}' --max-time 10 "$@"; }
new_unclaimed() {
  [ "$(status "$DOMAIN/lianxu-phone/mcp/")" = 404 ]
  [ "$(status "$DOMAIN/lianxu-phone/phone/control")" = 404 ]
}
baseline() {
  [ "$(status "$DOMAIN/")" = 404 ]
  [ "$(status "$DOMAIN/sales/materials")" = 401 ]
  [ "$(status "$DOMAIN/sales/materials/images")" = 405 ]
  [ "$(status "$DOMAIN/lianxu/mcp/")" = 401 ]
}
case "${1:-}" in
  preflight)
    require_root; check_original; check_ports; check_absent; baseline; new_unclaimed; verify_release
    [ -x "$RELEASE/.venv/bin/python" ] && [ -r "$RELEASE/server.py" ]
    ip -o -4 addr show dev br-65d9fe5ab4ea | grep -F '172.19.0.1/16' >/dev/null
    systemd-analyze verify "$DIR/lianxu-phone.service" "$DIR/lianxu-phone-gateway.service" "$DIR/lianxu-phone-gateway.socket"
    echo 'Preflight passed; no service or routing changes made.'
    ;;
  install)
    require_root; check_original; check_ports; check_absent; baseline; new_unclaimed; verify_release
    [ -x "$RELEASE/.venv/bin/python" ] && [ -r "$RELEASE/server.py" ]
    useradd --system --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin lianxu-phone
    install -d -m 0700 -o lianxu-phone -g lianxu-phone /etc/lianxu-phone /var/lib/lianxu-phone
    runuser -u lianxu-phone -- "$RELEASE/.venv/bin/python" "$RELEASE/deploy/init_config.py" "$CFG" "$DB" 18767
    runuser -u lianxu-phone -- "$RELEASE/.venv/bin/python" -c 'import os,secrets; f=os.open("/etc/lianxu-phone/agent.token",os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600); os.write(f,secrets.token_urlsafe(48).encode()); os.close(f)'
    install -m 0644 "$DIR/lianxu-phone.service" /etc/systemd/system/lianxu-phone.service
    install -m 0644 "$DIR/lianxu-phone-gateway.service" /etc/systemd/system/lianxu-phone-gateway.service
    install -m 0644 "$DIR/lianxu-phone-gateway.socket" /etc/systemd/system/lianxu-phone-gateway.socket
    systemd-analyze verify /etc/systemd/system/lianxu-phone.service /etc/systemd/system/lianxu-phone-gateway.service /etc/systemd/system/lianxu-phone-gateway.socket
    systemctl daemon-reload
    systemctl enable --now lianxu-phone.service lianxu-phone-gateway.socket
    for i in $(seq 1 20); do
      if runuser -u lianxu-phone -- "$RELEASE/.venv/bin/python" "$RELEASE/deploy/health.py" "$CFG" http://127.0.0.1:18767; then break; fi
      [ "$i" -lt 20 ] || { echo 'Bridge did not become healthy' >&2; exit 1; }
      sleep 1
    done
    ss -ltn '( sport = :18767 or sport = :18768 )'
    echo 'Independent bridge installed; Caddy unchanged. Initial database contains no phone data.'
    ;;
  publish)
    require_root; check_original; baseline; new_unclaimed; verify_release
    [ -f "$CFG" ] && [ -f "$DB" ] && [ ! -e "$BACKUP" ] && [ ! -e "$CANDIDATE" ]
    systemctl is-active --quiet lianxu-phone.service
    systemctl is-active --quiet lianxu-phone-gateway.socket
    python3 "$DIR/prepare_caddy.py" prepare "$CADDY" "$DIR/caddy-snippet.conf" "$CANDIDATE" "$EXPECTED"
    CANDIDATE_SHA=$(sha "$CANDIDATE")
    docker exec -i sasalele-sales-edge-1 caddy validate --config /dev/stdin --adapter caddyfile < "$CANDIDATE"
    [ "$(sha "$CANDIDATE")" = "$CANDIDATE_SHA" ] || { echo 'Candidate changed after validation' >&2; exit 1; }
    check_original
    cp -p -- "$CADDY" "$BACKUP"
    [ "$(sha "$BACKUP")" = "$EXPECTED" ] || { echo 'Backup mismatch' >&2; exit 1; }
    rollback() {
      if [ "${ok:-0}" = 0 ]; then
        if [ "$(sha "$CADDY")" = "$CANDIDATE_SHA" ]; then
          python3 "$DIR/prepare_caddy.py" restore "$CADDY" "$BACKUP" "$CANDIDATE_SHA"
          docker kill -s USR1 sasalele-sales-edge-1 >/dev/null || true
          echo 'Restored original Caddyfile after failed publish.' >&2
        else
          echo 'Caddyfile changed concurrently; automatic restore refused.' >&2
        fi
      fi
    }
    ok=0; trap rollback EXIT
    python3 "$DIR/prepare_caddy.py" apply "$CADDY" "$CANDIDATE" "$EXPECTED" "$CANDIDATE_SHA"
    docker kill -s USR1 sasalele-sales-edge-1 >/dev/null
    sleep 3; baseline
    [ "$(status -X POST -H 'Content-Type: application/json' -d '{}' "$DOMAIN/lianxu-phone/mcp/")" = 401 ]
    runuser -u lianxu-phone -- "$RELEASE/.venv/bin/python" "$RELEASE/deploy/health.py" "$CFG" "$DOMAIN/lianxu-phone"
    ok=1; trap - EXIT
    echo 'Published isolated phone route; phone connection and Grok handoff remain unverified.'
    ;;
  *) echo 'Usage: deploy.sh preflight|install|publish' >&2; exit 2 ;;
esac
