#!/usr/bin/env bash
# 打包并发布 git.vambda.com（远程变更，每次运行都需要当次授权）。
# 在持有 Lightsail SSH 私钥的 Mac 上运行，经 vamyu 的严格 SSH 入口连接；
# 服务器端由 install.sh 原子切换发布并在失败时回退。
# 首次上线前 Cloudflare 需要有 git.vambda.com 的 A 记录（54.249.231.98，Proxied）。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CONNECT="${VAM_CONNECT_SSH:-$ROOT/../vamyu/scripts/connect-ssh.sh}"
[[ -x "$CONNECT" ]] || { echo "Missing SSH entry: $CONNECT" >&2; exit 1; }

"$ROOT/scripts/package-site.sh"
SITE="$ROOT/build/site/gitviz-site"
[[ -f "$SITE/public/index.html" && -f "$SITE/SHA256SUMS.txt" ]]

RELEASE_ID="$(TZ=Asia/Taipei date +%Y%m%d-%H%M%S)"
STAGE="/home/ubuntu/gitviz-upload-$RELEASE_ID"
BUNDLE="$(mktemp -d "${TMPDIR:-/tmp}/gitviz-upload.XXXXXX")"
trap 'rm -rf "$BUNDLE"' EXIT
cp -R "$SITE" "$BUNDLE/gitviz-site"
mkdir "$BUNDLE/deploy"
cp "$ROOT/site/deploy/gitviz.Caddyfile" "$ROOT/site/deploy/install.sh" "$BUNDLE/deploy/"

echo "Uploading release $RELEASE_ID …"
COPYFILE_DISABLE=1 tar --no-xattrs -C "$BUNDLE" -cf - gitviz-site deploy | \
  "$CONNECT" "sudo -n bash -c 'set -eu; test \"\$(hostname)\" = ip-172-26-15-41; mkdir -m 755 \"$STAGE\"; tar -xf - -C \"$STAGE\"; bash \"$STAGE/deploy/install.sh\" \"$RELEASE_ID\" \"$STAGE\"; rm -rf \"$STAGE\"'"

echo "Checking the public site …"
DL="$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["download"])' "$SITE/release.json")"
for path in / /style.css /app.js "$DL"; do
  code="$(curl -sS -o /dev/null -I -w '%{http_code}' "https://git.vambda.com$path")"
  echo "$code $path"; [[ $code == 200 ]]
done
remote="$(curl -sS "https://git.vambda.com$DL" | shasum -a 256 | cut -d' ' -f1)"
local_sum="$(cut -d' ' -f1 "$SITE/public$DL.sha256")"
[[ $remote == "$local_sum" ]] || { echo "Downloaded installer checksum mismatch" >&2; exit 1; }
echo "Preflight site published: $RELEASE_ID"
