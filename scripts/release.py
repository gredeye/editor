#!/usr/bin/env python3
"""Stage a release as a draft; never mutate an already published release.
All gh failures are fatal (auth errors are not mistaken for missing releases).
"""
import hashlib
import json
import os
import pathlib
import re
import subprocess
import tempfile


def run(*args):
    return subprocess.check_output(args, text=True).strip()


def version_tuple(tag):
    if not re.fullmatch(r"v\d+\.\d+\.\d+", tag):
        raise ValueError("Only stable semantic version tags are supported")
    return tuple(map(int, tag[1:].split('.')))


def main():
    props = dict(line.split('=', 1) for line in pathlib.Path('version.properties').read_text().splitlines() if '=' in line)
    tag = 'v' + props['versionName']
    version_tuple(tag)
    sha = run('git', 'rev-parse', 'HEAD')
    repo = os.environ['GH_REPO']
    # Listing via API distinguishes API failures from a legitimately absent release.
    releases = json.loads(run('gh', 'api', '--paginate', '--slurp', f'repos/{repo}/releases?per_page=100'))
    releases = [release for page in releases for release in page]
    existing = next((r for r in releases if r['tag_name'] == tag), None)
    for r in releases:
        if not r['draft'] and re.fullmatch(r'v\d+\.\d+\.\d+', r['tag_name']):
            if version_tuple(r['tag_name']) > version_tuple(tag):
                raise RuntimeError('Refusing to mark an older version as latest')
    # An existing tag must identify this exact source commit (including annotated tags).
    refs = run('git', 'ls-remote', '--tags', 'origin', f'refs/tags/{tag}', f'refs/tags/{tag}^{{}}').splitlines()
    if refs:
        commits = {line.split()[1]: line.split()[0] for line in refs}
        target = commits.get(f'refs/tags/{tag}^{{}}', commits.get(f'refs/tags/{tag}'))
        if target != sha:
            raise RuntimeError('Existing version tag points to different source; bump the version')
    if existing and not existing['draft']:
        assets = {a['name'] for a in existing['assets']}
        if not {'Vynox.apk', 'Vynox.apk.sha256'} <= assets:
            raise RuntimeError('Published release lacks required assets; refusing to mutate it')
        print(f'{tag} is already published; leaving release and assets unchanged.')
        return
    if not existing:
        run('gh', 'release', 'create', tag, '--target', sha, '--title', f'Vynox {tag}', '--draft', '--generate-notes')
    # Interrupted drafts are resumable only when existing bytes match; no --clobber.
    info = json.loads(run('gh', 'release', 'view', tag, '--json', 'assets'))
    names = {a['name'] for a in info['assets']}
    for filename in ('Vynox.apk', 'Vynox.apk.sha256'):
        path = pathlib.Path('release-assets') / filename
        if filename in names:
            with tempfile.TemporaryDirectory() as tmp:
                run('gh', 'release', 'download', tag, '--pattern', filename, '--dir', tmp)
                if hashlib.sha256(path.read_bytes()).digest() != hashlib.sha256((pathlib.Path(tmp) / filename).read_bytes()).digest():
                    raise RuntimeError(f'Draft asset differs: {filename}. Review draft manually; nothing overwritten.')
        else:
            run('gh', 'release', 'upload', tag, str(path))
    run('gh', 'release', 'edit', tag, '--draft=false', '--latest')
    print(f'Published Vynox {tag}')


if __name__ == '__main__':
    main()
