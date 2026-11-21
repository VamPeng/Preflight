import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from release_manifest import write_manifest


class ReleaseManifestTest(unittest.TestCase):
    def test_platform_versions_and_digests_come_from_actual_installers(self):
        with tempfile.TemporaryDirectory() as directory:
            site = Path(directory)
            (site / 'public/downloads').mkdir(parents=True)
            files, downloads = {}, {}
            for platform, name in [('mac', 'Preflight-1.0.10-arm64.dmg'), ('mac_x64', 'Preflight-1.0.10-x64.dmg'), ('windows', 'Preflight-1.0.2.exe')]:
                data = platform.encode()
                path = '/downloads/' + name
                (site / ('public' + path)).write_bytes(data)
                files['public' + path] = dict(bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
                downloads[platform] = path
            meta = dict(product='Preflight', mac_version='1.0.10', windows_version='1.0.2',
                        download=downloads['mac'], downloads=downloads, files=files, deployed=False)
            (site / 'release.json').write_text(json.dumps(meta))
            result = write_manifest(site)
            self.assertEqual(['1.0.10', '1.0.10', '1.0.2'], [r['version'] for r in result['releases']])
            self.assertEqual(['arm64', 'x64', 'x64'], [r['arch'] for r in result['releases']])
            self.assertNotIn('deployed', result)
            self.assertIn('public/release.json', (site / 'SHA256SUMS.txt').read_text())
            (site / ('public' + downloads['windows'])).write_bytes(b'changed')
            with self.assertRaisesRegex(ValueError, 'does not match'):
                write_manifest(site)
            del meta['downloads']['windows']
            (site / 'release.json').write_text(json.dumps(meta))
            self.assertEqual(['mac', 'mac'], [r['platform'] for r in write_manifest(site)['releases']])


if __name__ == '__main__':
    unittest.main()
