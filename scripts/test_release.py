"""Offline release safety tests, with gh/git mocked: no network or credentials."""
import importlib.util
import json
import os
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('release', 'scripts/release.py')
r = importlib.util.module_from_spec(spec)
spec.loader.exec_module(r)


class ReleaseTests(unittest.TestCase):
    def test_semver_numeric(self):
        self.assertGreater(r.version_tuple('v0.10.0'), r.version_tuple('v0.9.9'))

    def test_injection_rejected(self):
        with self.assertRaises(ValueError):
            r.version_tuple('v0.1.0; echo bad')

    def invoke(self, releases, tagsha='source'):
        calls = []
        def fake(*args):
            calls.append(args)
            if args[:2] == ('git', 'rev-parse'): return 'source'
            if args[:2] == ('gh', 'api'): return json.dumps([releases])
            if args[:2] == ('git', 'ls-remote'): return tagsha + '\trefs/tags/v0.1.0'
            raise AssertionError('Unexpected mutation: ' + str(args))
        with patch.object(r, 'run', side_effect=fake), patch.dict(os.environ, {'GH_REPO': 'owner/repo'}):
            r.main()
        return calls

    def test_published_is_immutable(self):
        calls = self.invoke([{'tag_name': 'v0.1.0', 'draft': False, 'assets': [{'name': 'Vynox.apk'}, {'name': 'Vynox.apk.sha256'}]}])
        self.assertFalse(any('upload' in c or 'edit' in c for c in calls))

    def test_tag_conflict_fails(self):
        with self.assertRaisesRegex(RuntimeError, 'different source'):
            self.invoke([], 'different')

    def test_older_release_fails(self):
        with self.assertRaisesRegex(RuntimeError, 'older version'):
            self.invoke([{'tag_name': 'v0.2.0', 'draft': False}])

    def test_incomplete_public_release_not_overwritten(self):
        with self.assertRaisesRegex(RuntimeError, 'lacks required assets'):
            self.invoke([{'tag_name': 'v0.1.0', 'draft': False, 'assets': []}])


if __name__ == '__main__': unittest.main()
