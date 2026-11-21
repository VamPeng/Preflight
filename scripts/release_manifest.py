#!/usr/bin/env python3
"""从已打包的安装文件生成公开更新清单；不发布内部部署状态或其他文件清单。"""
import hashlib
import json
from pathlib import Path
import re
import sys


def write_manifest(site):
    site = Path(site)
    meta = json.loads((site / 'release.json').read_text())
    if meta.get('product') != 'Preflight':
        raise ValueError('Unexpected product')
    releases = []
    downloads = meta.get('downloads', {'mac': meta['download']})
    for key, platform, arch, suffix in [('mac', 'mac', 'arm64', '.dmg'), ('mac_x64', 'mac', 'x64', '.dmg'), ('windows', 'windows', 'x64', '.exe')]:
        url = downloads.get(key)
        if not url:
            continue
        # 早期清单的两个平台使用同一版本；以后允许 Windows 单独指定版本。
        version = meta.get(platform + '_version', meta['mac_version'])
        if not re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', version):
            raise ValueError('Invalid release version')
        if not re.fullmatch(r'/downloads/[A-Za-z0-9._-]+', url) or not url.endswith(suffix):
            raise ValueError('Invalid download path')
        artifact = site / 'public' / url.lstrip('/')
        digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
        recorded = meta['files']['public' + url]
        if digest != recorded['sha256'] or artifact.stat().st_size != recorded['bytes']:
            raise ValueError('Installer does not match release metadata')
        releases.append(dict(platform=platform, arch=arch, version=version,
                             url='https://git.vambda.com' + url, sha256=digest))
    manifest = dict(schema=1, product='Preflight', releases=releases)
    target = site / 'public/release.json'
    target.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    # 外部清单也纳入发布包完整性记录，避免上传时遗漏。
    meta['files']['public/release.json'] = dict(bytes=target.stat().st_size,
        sha256=hashlib.sha256(target.read_bytes()).hexdigest())
    (site / 'release.json').write_text(json.dumps(meta, ensure_ascii=False, indent=2) + '\n')
    (site / 'SHA256SUMS.txt').write_text(''.join(
        f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(site).as_posix()}\n'
        for p in sorted(site.rglob('*')) if p.is_file() and p.name != 'SHA256SUMS.txt'))
    return manifest


if __name__ == '__main__':
    write_manifest(sys.argv[1])
