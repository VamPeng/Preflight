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
[[ $ARCH == arm64 ]] || { echo "站点打包入口需要 arm64 Mac；Intel 包请先用 x64 JDK 构建" >&2; exit 1; }
DL_NAME="Preflight-$VERSION-macos-arm64.dmg"
INTEL_INSTALLER="${PREFLIGHT_MAC_X64_INSTALLER:-$ROOT/build/intel/compose/packages.noindex/release/main/dmg/Preflight-$VERSION.dmg}"
[[ -z ${PREFLIGHT_MAC_X64_INSTALLER:-} || -f $INTEL_INSTALLER ]] || { echo "Intel installer not found: $INTEL_INSTALLER" >&2; exit 1; }
INTEL_URL=""
# Windows 安装包由 Windows 构建机提供；也可放入本机 build/installers/ 供后续发布复用。
WINDOWS_VERSION="${PREFLIGHT_WINDOWS_VERSION:-$VERSION}"
[[ $WINDOWS_VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "Invalid Windows version" >&2; exit 1; }
WINDOWS_INSTALLER="${PREFLIGHT_WINDOWS_INSTALLER:-$ROOT/build/installers/Preflight-$WINDOWS_VERSION-windows-x64.exe}"
[[ -z ${PREFLIGHT_WINDOWS_INSTALLER:-} || -f $WINDOWS_INSTALLER ]] || { echo "Windows installer not found: $WINDOWS_INSTALLER" >&2; exit 1; }
WIN_URL=""

OUT="$ROOT/build/site"
SITE="$OUT/gitviz-site"
rm -rf "$SITE" "$OUT/gitviz-site-$VERSION.zip" "$OUT/gitviz-site-$VERSION.zip.sha256"
mkdir -p "$SITE/public/downloads"
cp -R site/introduction/. "$SITE/public/"
find "$SITE" -name .DS_Store -delete
cp "$DMG" "$SITE/public/downloads/$DL_NAME"
(cd "$SITE/public/downloads" && shasum -a 256 "$DL_NAME" > "$DL_NAME.sha256")
if [[ -f $INTEL_INSTALLER ]]; then
  hdiutil verify "$INTEL_INSTALLER" >/dev/null
  INTEL_NAME="Preflight-$VERSION-macos-x64.dmg"
  cp "$INTEL_INSTALLER" "$SITE/public/downloads/$INTEL_NAME"
  (cd "$SITE/public/downloads" && shasum -a 256 "$INTEL_NAME" > "$INTEL_NAME.sha256")
  INTEL_URL="/downloads/$INTEL_NAME"
fi
if [[ -f $WINDOWS_INSTALLER ]]; then
  python3 -I - "$WINDOWS_INSTALLER" <<'PY'
import struct, sys
from pathlib import Path
b = Path(sys.argv[1]).read_bytes()
assert b[:2] == b'MZ', 'Not a Windows executable'
offset = struct.unpack_from('<I', b, 0x3c)[0]
assert b[offset:offset + 4] == b'PE\0\0' and struct.unpack_from('<H', b, offset + 4)[0] == 0x8664, 'Expected Windows x64 installer'
PY
  WIN_NAME="Preflight-$WINDOWS_VERSION-windows-x64.exe"
  cp "$WINDOWS_INSTALLER" "$SITE/public/downloads/$WIN_NAME"
  (cd "$SITE/public/downloads" && shasum -a 256 "$WIN_NAME" > "$WIN_NAME.sha256")
  WIN_URL="/downloads/$WIN_NAME"
fi

python3 -I - "$SITE" "$VERSION" "/downloads/$DL_NAME" "$WIN_URL" "$WINDOWS_VERSION" "$INTEL_URL" <<'PY'
import hashlib, json, sys
from datetime import datetime, timezone
from pathlib import Path
site, version, url, win_url = Path(sys.argv[1]), sys.argv[2], sys.argv[3], sys.argv[4]
page = site / 'public/index.html'
s = page.read_text()
# 没有对应安装包的平台留空，页面显示「即将上线」。
old = 'data-dl-mac="" data-dl-mac-x64="" data-dl-windows="" data-dl-linux="" data-version="" data-version-windows=""'
assert s.count(old) == 1, 'index.html 的 <html> 属性已被改动，无法填写下载地址'
page.write_text(s.replace(old, f'data-dl-mac="{url}" data-dl-mac-x64="{sys.argv[6]}" data-dl-windows="{win_url}" data-dl-linux="" data-version="{version}" data-version-windows="{sys.argv[5] if win_url else ""}"'))

# 页面只能引用同站文件：检查本地引用都存在，且没有内联样式或脚本（会被 CSP 拦截）。
import re
html = page.read_text()
assert not re.search(r'<style|<script>|\sstyle=|\son[a-z]+=', html), '页面含内联样式或脚本'
refs = re.findall(r'(?:src|href)="([^"#][^"]*)"', html)
refs += [u for u in re.findall(r'data-dl-[a-z0-9-]+="([^"]+)"', html)]
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
    'mac_architectures': ['arm64'] + (['x64'] if sys.argv[6] else []),
    'mac_signing': 'ad-hoc',
    'mac_notarized': False,
    'download': url,
    'downloads': {'mac': url, **({'mac_x64': sys.argv[6]} if sys.argv[6] else {}), **({'windows': win_url} if win_url else {})},
    **({'windows_version': sys.argv[5]} if win_url else {}),
    'files': {p.relative_to(site).as_posix(): {'bytes': p.stat().st_size, 'sha256': digest(p)} for p in files()},
}
(site / 'release.json').write_text(json.dumps(release, ensure_ascii=False, indent=2) + '\n')
(site / 'SHA256SUMS.txt').write_text(''.join(
    f'{digest(p)}  {p.relative_to(site).as_posix()}\n'
    for p in sorted(site.rglob('*')) if p.is_file() and p.name != 'SHA256SUMS.txt'))
PY
python3 -I "$ROOT/scripts/release_manifest.py" "$SITE"

(cd "$OUT" && rm -f "gitviz-site-$VERSION.zip" && COPYFILE_DISABLE=1 zip -qrX "gitviz-site-$VERSION.zip" gitviz-site \
  && shasum -a 256 "gitviz-site-$VERSION.zip" > "gitviz-site-$VERSION.zip.sha256")
(cd "$SITE" && shasum -a 256 -c --quiet SHA256SUMS.txt)
echo "介绍页已打包：$OUT/gitviz-site-$VERSION.zip"
echo "下载文件：public/downloads/$DL_NAME"
