"""候选封存的真实文件系统单测；仅Docker和挂载检查使用替身。"""
import contextlib
import importlib.util
import json
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import uuid

SPEC = importlib.util.spec_from_file_location('candidate_volume', Path(__file__).parents[1] / 'import_disk_volume.py')
volume = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(volume)


@unittest.skipUnless(sys.platform == 'linux', 'LINUX_FILESYSTEM_REQUIRED')
class EditCandidateTest(unittest.TestCase):
    def test_source_and_complete_view_are_real_frozen_inventories(self):
        import base64
        with self.fixture() as (expected, folder, source, initial, edit):
            sealed=volume.seal_edit_candidate(expected,edit['authorizedStateHash'])
            original=json.loads(base64.b64decode(self.read(expected,sealed,'source-inventory')['data']))
            view=json.loads(base64.b64decode(self.read(expected,sealed,'view-inventory')['data']))
            self.assertIn('.env',original['files']);self.assertNotIn('.env',view['files'])
            self.assertEqual(base64.b64decode(self.read(expected,sealed,'source-file','app.py')['data']),b'print("before")\n')
            (folder/'work/view/new-output').write_bytes(b'late')
            with self.assertRaisesRegex(volume.VolumeError,'EDIT_READ_CANDIDATE_CHANGED'):
                self.read(expected,sealed,'view-inventory')
            (source/'app.py').write_bytes(b'changed')
            with self.assertRaisesRegex(volume.VolumeError,'EDIT_READ_CANDIDATE_CHANGED'):
                self.read(expected,sealed,'source-inventory')

    def test_actual_inventory_rejects_extra_file_empty_directory_and_same_size_tampering(self):
        import base64
        import hashlib
        for mutation in ('file', 'directory', 'bytes'):
            with self.subTest(mutation=mutation), self.fixture() as (expected, folder, source, initial, edit):
                sealed=volume.seal_edit_candidate(expected,edit['authorizedStateHash'])
                chunk=self.read(expected,sealed,'inventory')
                raw=base64.b64decode(chunk['data'])
                self.assertEqual(hashlib.sha256(raw).hexdigest(),sealed['candidateHash'])
                self.assertIn('empty',json.loads(raw)['directories'])
                candidate=folder/'work/candidate'
                if mutation=='file':(candidate/'hidden.py').write_bytes(b'extra')
                elif mutation=='directory':(candidate/'new-empty').mkdir()
                else:(candidate/'asset.bin').write_bytes(b'\x01\xfe')
                with self.assertRaisesRegex(volume.VolumeError,'EDIT_READ_CANDIDATE_CHANGED'):
                    self.read(expected,sealed,'inventory')

    def read(self,expected,sealed,part='manifest',name=None,offset=0,length=256<<10):
        return volume.read_edit_candidate(expected,sealed['authorizedStateHash'],sealed['candidateHash'],
                                         sealed['candidateManifestHash'],part,name,offset,length)

    def test_private_reader_exact_hash_path_and_bounded_chunks(self):
        import base64
        with self.fixture() as (expected, folder, source, initial, edit):
            sealed=volume.seal_edit_candidate(expected,edit['authorizedStateHash'])
            for part,filename in [('manifest','candidate-manifest.json'),('private-state','private-state.json')]:
                chunk=self.read(expected,sealed,part,length=7)
                self.assertEqual(base64.b64decode(chunk['data']), (folder/'work'/filename).read_bytes()[:7])
                self.assertEqual(chunk['offset'],0)
                self.assertLessEqual(len(chunk['data']),12)
            chunk=self.read(expected,sealed,'file','app.py',offset=2,length=5)
            self.assertEqual(base64.b64decode(chunk['data']), b'print("after")\n'[2:7])
            with self.assertRaisesRegex(volume.VolumeError,'EDIT_READ_PATH_INVALID'):
                self.read(expected,sealed,'file','../private-state.json')
            with self.assertRaisesRegex(volume.VolumeError,'EDIT_READ_REQUEST_INVALID'):
                self.read(expected,sealed,length=(256<<10)+1)

    def test_reader_refuses_unsealed_stale_or_foreign_identity(self):
        with self.fixture() as (expected, folder, source, initial, edit):
            bad={'authorizedStateHash':edit['authorizedStateHash'],'candidateHash':'a'*64,'candidateManifestHash':'b'*64}
            with self.assertRaisesRegex(volume.VolumeError,'EDIT_CANDIDATE_NOT_SEALED'):
                self.read(expected,bad)
            sealed=volume.seal_edit_candidate(expected,edit['authorizedStateHash'])
            with self.assertRaisesRegex(volume.VolumeError,'EDIT_CANDIDATE_NOT_SEALED'):
                self.read(expected,{**sealed,'authorizedStateHash':'a'*64})
            with self.assertRaisesRegex(volume.VolumeError,'VOLUME_IDENTITY_CONFLICT'):
                self.read({**expected,'ownerId':'8'},sealed)

    def test_reader_refuses_private_state_tampering_and_container_presence(self):
        with self.fixture() as (expected, folder, source, initial, edit):
            sealed=volume.seal_edit_candidate(expected,edit['authorizedStateHash'])
            with patch.object(volume,'command',return_value=subprocess.CompletedProcess([],0,'container','')):
                with self.assertRaisesRegex(volume.VolumeError,'APP_CONTAINERS_REMAIN'):
                    self.read(expected,sealed)
            (folder/'work/private-state.json').write_bytes(b'{}')
            with self.assertRaisesRegex(volume.VolumeError,'EDIT_READ_PRIVATE_HASH_CHANGED'):
                self.read(expected,sealed)

    @contextlib.contextmanager
    def fixture(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(volume, 'ROOT', Path(directory) / 'volumes'), \
                patch.object(volume, 'mounted', side_effect=lambda folder, record: record), \
                patch.object(volume, 'command', return_value=subprocess.CompletedProcess([], 0, '', '')):
            expected = volume.identity('123', 'a' * 64, 64 << 20,
                                       owner_id='7', run_id='201', edit_id=str(uuid.uuid4()))
            folder = volume.resource_folder(expected)
            (folder / 'work').mkdir(parents=True)
            volume.write_record(folder, {**expected, 'state': 'READY'})
            source = Path(directory) / 'source'
            source.mkdir()
            (source / '.env').write_bytes(b'API_TOKEN=fixture-private-912811\n')
            (source / 'app.py').write_bytes(b'print("before")\n')
            (source / 'asset.bin').write_bytes(b'\x00\xff')
            (source / 'empty').mkdir()
            initial = volume.prepare_edit_view(expected, source)
            edit = volume.edit_file(expected, {'operation': 'edit', 'path': 'app.py',
                                             'oldString': 'before', 'newString': 'after', 'replaceAll': False})
            yield expected, folder, source, initial, edit

    def test_sealed_candidate_preserves_secrets_binary_and_empty_directory(self):
        with self.fixture() as (expected, folder, source, initial, edit):
            (folder / 'work/view/test-output.json').write_bytes(b'output')
            result = volume.seal_edit_candidate(expected, edit['authorizedStateHash'])
            self.assertEqual(result['state'], 'CANDIDATE_SEALED')
            candidate = folder / 'work/candidate'
            self.assertEqual((candidate / '.env').read_bytes(), (source / '.env').read_bytes())
            self.assertEqual((candidate / 'asset.bin').read_bytes(), b'\x00\xff')
            self.assertEqual((candidate / 'app.py').read_bytes(), b'print("after")\n')
            self.assertTrue((candidate / 'empty').is_dir())
            self.assertFalse((candidate / 'test-output.json').exists())
            manifest = json.loads((folder / 'work/candidate-manifest.json').read_bytes())
            self.assertEqual([item['path'] for item in manifest['changes']], ['app.py'])
            self.assertEqual(manifest['editId'], expected['editId'])
            self.assertIn('test-output.json', manifest['retainedOutputs'])
            import base64
            self.assertEqual(base64.b64decode(self.read(expected, result, 'output-file', 'test-output.json')['data']), b'output')
            with self.assertRaisesRegex(volume.VolumeError, 'EDIT_READ_PATH_INVALID'):
                self.read(expected, result, 'output-file', 'app.py')
            self.assertNotIn('fixture-private-912811', json.dumps(manifest))
            self.assertEqual(stat.S_IMODE((folder / 'work/candidate-manifest.json').stat().st_mode), 0o600)
            self.assertEqual(stat.S_IMODE(candidate.stat().st_mode), 0o700)
            self.assertEqual((source / 'app.py').read_bytes(), b'print("before")\n')

    def test_sealed_candidate_cannot_be_replaced_or_edited(self):
        with self.fixture() as (expected, folder, source, initial, edit):
            volume.seal_edit_candidate(expected, edit['authorizedStateHash'])
            with self.assertRaisesRegex(volume.VolumeError, 'EDIT_CANDIDATE_FROZEN'):
                volume.seal_edit_candidate(expected, edit['authorizedStateHash'])
            with self.assertRaisesRegex(volume.VolumeError, 'EDIT_CANDIDATE_FROZEN'):
                volume.edit_file(expected, {'operation': 'write', 'path': 'app.py', 'content': 'overwrite'})
            self.assertEqual((folder / 'work/view/app.py').read_bytes(), b'print("after")\n')

    def test_old_private_state_hash_is_rejected_before_intent(self):
        with self.fixture() as (expected, folder, source, initial, edit):
            with self.assertRaisesRegex(volume.VolumeError, 'EDIT_STATE_CHANGED'):
                volume.seal_edit_candidate(expected, initial['viewStateHash'])
            self.assertNotIn('candidateState', json.loads((folder / 'record.json').read_text()))

    def test_existing_container_is_not_stopped_or_ignored(self):
        with self.fixture() as (expected, folder, source, initial, edit), \
                patch.object(volume, 'command', return_value=subprocess.CompletedProcess([], 0, 'container', '')):
            with self.assertRaisesRegex(volume.VolumeError, 'APP_CONTAINERS_REMAIN'):
                volume.seal_edit_candidate(expected, edit['authorizedStateHash'])
            self.assertNotIn('candidateState', json.loads((folder / 'record.json').read_text()))

    def test_engine_error_does_not_mean_container_absent(self):
        with self.fixture() as (expected, folder, source, initial, edit), \
                patch.object(volume, 'command', side_effect=volume.VolumeError('ENGINE_UNKNOWN')):
            with self.assertRaisesRegex(volume.VolumeError, 'ENGINE_UNKNOWN'):
                volume.seal_edit_candidate(expected, edit['authorizedStateHash'])
            self.assertFalse((folder / 'work/candidate').exists())

    def test_sync_failure_retains_unresolved_intent_and_blocks_retry(self):
        with self.fixture() as (expected, folder, source, initial, edit):
            def commands(args, **kwargs):
                if args[0] == 'sync':
                    raise volume.VolumeError('SYNC_FAILED')
                return subprocess.CompletedProcess(args, 0, '', '')
            with patch.object(volume, 'command', side_effect=commands), self.assertRaisesRegex(volume.VolumeError, 'SYNC_FAILED'):
                volume.seal_edit_candidate(expected, edit['authorizedStateHash'])
            self.assertEqual(json.loads((folder / 'record.json').read_text())['candidateState'], 'SEALING')
            with self.assertRaisesRegex(volume.VolumeError, 'EDIT_CANDIDATE_FROZEN'):
                volume.seal_edit_candidate(expected, edit['authorizedStateHash'])

    def test_shell_changes_after_authorization_cannot_seal(self):
        with self.fixture() as (expected, folder, source, initial, edit):
            (folder / 'work/view/app.py').write_bytes(b'shell')
            with self.assertRaisesRegex(volume.VolumeError, 'VIEW_EDIT_CHANGED_AFTER_AUTHORIZATION'):
                volume.seal_edit_candidate(expected, edit['authorizedStateHash'])
            self.assertEqual(json.loads((folder / 'record.json').read_text())['candidateState'], 'SEALING')
            self.assertFalse((folder / 'work/candidate').exists())

    def test_source_conflict_cannot_seal(self):
        with self.fixture() as (expected, folder, source, initial, edit):
            (source / 'app.py').write_bytes(b'external new version')
            with self.assertRaisesRegex(volume.VolumeError, 'VIEW_SOURCE_CHANGED'):
                volume.seal_edit_candidate(expected, edit['authorizedStateHash'])
            self.assertFalse((folder / 'work/candidate').exists())

    def test_container_appearing_during_sync_keeps_sealing(self):
        with self.fixture() as (expected, folder, source, initial, edit):
            queries = 0
            def commands(args, **kwargs):
                nonlocal queries
                if args[0] == 'docker':
                    queries += 1
                return subprocess.CompletedProcess(args, 0, 'late-container' if queries == 2 else '', '')
            with patch.object(volume, 'command', side_effect=commands), self.assertRaisesRegex(volume.VolumeError, 'APP_CONTAINERS_REMAIN'):
                volume.seal_edit_candidate(expected, edit['authorizedStateHash'])
            self.assertEqual(json.loads((folder / 'record.json').read_text())['candidateState'], 'SEALING')


if __name__ == '__main__':
    unittest.main()
