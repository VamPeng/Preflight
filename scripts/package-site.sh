#!/usr/bin/env bash
# 打包 git.vambda.com 介绍页：构建正式版 DMG，和 site/introduction 一起放进发布目录并压缩。
# 只在本地运行，不连接服务器。上传见 site/deploy/upload.sh。
#
# 产物（build/site/）：
#   gitviz-site/                 发布目录：public/（页面与 downloads/）、release.json、SHA256SUMS.txt
#   gitviz-site-<版本>.zip       同一目录的压缩包，旁边是 .sha256
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="$(sed -n 's/^ *packageVersion = "\([0-9.]*\)".*/\1/p' build.gradle.kts | head -1)"
[[ $VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "build.gradle.kts 里找不到 packageVersion" >&2; exit 1; }

./gradlew packageDmg --console=plain -q
DMG="build/compose/packages.noindex/release/main/dmg/Preflight-$VERSION.dmg"
[[ -f $DMG ]] || { echo "缺少 $DMG" >&2; exit 1; }
hdiutil verify "$DMG" >/dev/null

ARCH="$(uname -m)"
[[ $ARCH == arm64 ]] || { echo "当前只发布 Apple 芯片版本，请在 arm64 Mac 上打包" >&2; exit 1; }
DL_NAME="Preflight-$VERSION-macos-arm64.dmg"

OUT="$ROOT/build/site"
SITE="$OUT/gitviz-site"
rm -rf "$SITE" "$OUT/gitviz-site-$VERSION.zip" "$OUT/gitviz-site-$VERSION.zip.sha256"
mkdir -p "$SITE/public/downloads"
cp -R site/introduction/. "$SITE/public/"
find "$SITE" -name .DS_Store -delete
cp "$DMG" "$SITE/public/downloads/$DL_NAME"
(cd "$SITE/public/downloads" && shasum -a 256 "$DL_NAME" > "$DL_NAME.sha256")

python3 -I - "$SITE" "$VERSION" "/downloads/$DL_NAME" <<'PY'
import hashlib, json, sys
from datetime import datetime, timezone
from pathlib import Path
site, version, url = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
page = site / 'public/index.html'
s = page.read_text()
# 目前只有 macOS（Apple 芯片）安装包；Windows、Linux 留空，页面对这些系统显示「即将上线」。
old = 'data-dl-mac="" data-dl-windows="" data-dl-linux="" data-version=""'
assert s.count(old) == 1, 'index.html 的 <html> 属性已被改动，无法填写下载地址'
page.write_text(s.replace(old, f'data-dl-mac="{url}" data-dl-windows="" data-dl-linux="" data-version="{version}"'))

# 页面只能引用同站文件：检查本地引用都存在，且没有内联样式或脚本（会被 CSP 拦截）。
import re
html = page.read_text()
assert not re.search(r'<style|<script>|\sstyle=|\son[a-z]+=', html), '页面含内联样式或脚本'
refs = re.findall(r'(?:src|href)="([^"#][^"]*)"', html)
refs += [u for u in re.findall(r'data-dl-[a-z]+="([^"]+)"', html)]
for ref in refs:
    if ref.startswith(('http://', 'https://', 'mailto:')):
        assert ref in ('https://vambda.com', 'https://github.com/VamPeng/gitviz'), f'外部引用：{ref}'
        continue
    target = site / 'public' / ref.lstrip('/')
    assert target.is_file(), f'缺少引用的文件：{ref}'

def files():
    return [p for p in sorted(site.rglob('*')) if p.is_file() and p.name not in ('release.json', 'SHA256SUMS.txt')]
digest = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
release = {
    'product': 'Preflight',
    'prepared_at': datetime.now(timezone.utc).astimezone().isoformat(timespec='seconds'),
    'deployed': False,
    'site_url': 'https://git.vambda.com/',
    'mac_version': version,
    'mac_architecture': 'arm64',
    'mac_signing': 'ad-hoc',
    'mac_notarized': False,
    'download': url,
    'files': {p.relative_to(site).as_posix(): {'bytes': p.stat().st_size, 'sha256': digest(p)} for p in files()},
}
(site / 'release.json').write_text(json.dumps(release, ensure_ascii=False, indent=2) + '\n')
(site / 'SHA256SUMS.txt').write_text(''.join(
    f'{digest(p)}  {p.relative_to(site).as_posix()}\n'
    for p in sorted(site.rglob('*')) if p.is_file() and p.name != 'SHA256SUMS.txt'))
PY

(cd "$OUT" && rm -f "gitviz-site-$VERSION.zip" && COPYFILE_DISABLE=1 zip -qrX "gitviz-site-$VERSION.zip" gitviz-site \
  && shasum -a 256 "gitviz-site-$VERSION.zip" > "gitviz-site-$VERSION.zip.sha256")
(cd "$SITE" && shasum -a 256 -c --quiet SHA256SUMS.txt)
echo "介绍页已打包：$OUT/gitviz-site-$VERSION.zip"
echo "下载文件：public/downloads/$DL_NAME"
