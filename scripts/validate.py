#!/usr/bin/env python3
"""Dependency-free static repository checks. Not a replacement for Gradle/lint."""
import pathlib
import re
import subprocess
import xml.etree.ElementTree as ET

root = pathlib.Path(__file__).resolve().parents[1]
props = dict(line.split('=', 1) for line in (root / 'version.properties').read_text().splitlines() if '=' in line)
assert re.fullmatch(r'\d+\.\d+\.\d+', props['versionName']), 'Invalid semantic version'
assert int(props['versionCode']) > 0
for path in (root / 'app/src/main').rglob('*.xml'):
    ET.parse(path)
manifest = (root / 'app/src/main/AndroidManifest.xml').read_text()
assert 'android.permission.INTERNET' not in manifest, 'Core app must remain offline'
assert 'android:exported="true"' in manifest
for secret in ('VYNOX_KEYSTORE_BASE64', 'VYNOX_KEYSTORE_PASSWORD', 'VYNOX_KEY_ALIAS', 'VYNOX_KEY_PASSWORD'):
    assert '${{ secrets.' + secret + ' }}' in (root / '.github/workflows/release.yml').read_text()
for path in subprocess.check_output(['git', 'ls-files'], cwd=root, text=True).splitlines():
    assert not path.endswith(('.jks', '.keystore', '.p12', '.pem')), f'Secret file tracked: {path}'
assert 'releases/latest/download/Vynox.apk' in (root / 'docs/index.html').read_text()
assert 'uses: ./.github/workflows/pages.yml' in (root / '.github/workflows/release.yml').read_text()
assert '--clobber' not in (root / 'scripts/release.py').read_text().replace('# Interrupted drafts are resumable only when existing bytes match; no --clobber.', '')
print('PASS: version, XML, offline manifest, signing references, tracked-file hygiene, release → Pages wiring')
