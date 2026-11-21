#!/usr/bin/env bash
# 服务器端：安装一次 gitviz 静态发布，切换 current 并重载网关；任何一步失败都恢复原状。
# 由 upload.sh 以 root 调用：install.sh <发布号> <上传目录>
set -euo pipefail
[[ $(id -u) == 0 && $(hostname) == ip-172-26-15-41 ]]
RELEASE_ID=${1:?release ID required}
STAGE=${2:?upload directory required}
[[ $RELEASE_ID =~ ^[0-9]{8}-[0-9]{6}$ ]]
[[ $STAGE == "/home/ubuntu/gitviz-upload-$RELEASE_ID" ]]
BASE=/opt/gitviz
RELEASE="$BASE/releases/$RELEASE_ID"
BACKUP="/var/backups/gitviz/$RELEASE_ID"
ROUTE=/etc/personal-blog/sites/gitviz.Caddyfile
CFG=/etc/personal-blog/Caddyfile
DIR=$(cd "$(dirname "$0")" && pwd)
grep -Fxq 'import /etc/personal-blog/sites/*.Caddyfile' "$CFG"
[[ ! -e $RELEASE && ! -e $BACKUP ]]
[[ ! -e $BASE/current || -L $BASE/current ]]
(cd "$STAGE/gitviz-site" && sha256sum --quiet -c SHA256SUMS.txt)

install -d -m 0755 "$BASE" "$BASE/releases"
install -d -m 0700 "$BACKUP"
if [[ -e $ROUTE ]]; then cp -p "$ROUTE" "$BACKUP/route.before"; fi
PREVIOUS=$(readlink "$BASE/current" || true)
printf '%s\n' "$PREVIOUS" > "$BACKUP/current.before"

cp -R "$STAGE/gitviz-site" "$RELEASE"
# 已打开的旧页面仍指向旧安装包：保留以前发布过的下载文件。
if [[ -d $BASE/current/public/downloads ]]; then
  cp -an "$BASE/current/public/downloads/." "$RELEASE/public/downloads/"
fi
python3 - "$RELEASE" <<'PY'
from datetime import datetime
from zoneinfo import ZoneInfo
import json, sys
from pathlib import Path
meta = Path(sys.argv[1]) / 'release.json'
data = json.loads(meta.read_text())
data['deployed'] = True
data['published_at'] = datetime.now(ZoneInfo('Asia/Taipei')).isoformat(timespec='seconds')
meta.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
PY
chown -R root:root "$RELEASE"
find "$RELEASE" -type d -exec chmod 0755 {} +
find "$RELEASE" -type f -exec chmod 0644 {} +

rollback() {
  local status=$?
  trap - ERR
  set +e
  if [[ -f $BACKUP/route.before ]]; then cp -p "$BACKUP/route.before" "$ROUTE"; else rm -f "$ROUTE"; fi
  if [[ -n $PREVIOUS ]]; then
    ln -s "$PREVIOUS" "$BASE/rollback-$RELEASE_ID"
    mv -Tf "$BASE/rollback-$RELEASE_ID" "$BASE/current"
  else
    rm -f "$BASE/current"
  fi
  sudo -u personal-blog /usr/local/bin/caddy-blog validate --config "$CFG" --adapter caddyfile
  systemctl kill --kill-who=main --signal=USR1 personal-blog.service
  echo "GitViz site deployment rolled back; retained release: $RELEASE" >&2
  exit "$status"
}
trap rollback ERR
ln -s "$RELEASE" "$BASE/next-$RELEASE_ID"
mv -Tf "$BASE/next-$RELEASE_ID" "$BASE/current"
install -m 0640 -o root -g personal-blog "$DIR/gitviz.Caddyfile" "$ROUTE"
sudo -u personal-blog /usr/local/bin/caddy-blog validate --config "$CFG" --adapter caddyfile
systemctl kill --kill-who=main --signal=USR1 personal-blog.service
# 首次上线时 Caddy 需要申请证书，最多等 60 秒。
for attempt in {1..120}; do
  if curl --silent --fail --resolve gitviz.vambda.com:443:127.0.0.1 https://gitviz.vambda.com/ -o "$BACKUP/http-check.html" \
     && cmp -s "$BACKUP/http-check.html" "$RELEASE/public/index.html"; then break; fi
  [[ $attempt != 120 ]]
  sleep 0.5
done
systemctl is-active --quiet personal-blog.service
trap - ERR
printf 'GitViz site release: %s\nRollback state: %s\n' "$RELEASE" "$BACKUP"
