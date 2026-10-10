"""受限卷身份和恢复门禁；Linux 以平台 root 运行，Docker 与挂载操作在单元中替换。"""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import uuid
import json
import subprocess
import sys
import hashlib
from unittest.mock import patch
from types import SimpleNamespace

SPEC = importlib.util.spec_from_file_location("import_disk_volume", Path(__file__).parents[1] / "import_disk_volume.py")
volume = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(volume)


class DiskVolumeTest(unittest.TestCase):
    def test_edit_file_rejects_malformed_requests_before_platform_access(self):
        expected = volume.identity('123', 'a'*64, 64<<20, owner_id='7', run_id='201', edit_id=str(uuid.uuid4()))
        requests = [{'operation':{},'path':'app.py'}, {'operation':'delete','path':'a\\b'},
                    {'operation':'write','path':'app.py','content':1},
                    {'operation':'write','path':'app.py','content':'\ud800'},
                    {'operation':'edit','path':'app.py','oldString':'','newString':'x','replaceAll':False},
                    {'operation':'edit','path':'app.py','oldString':'x','newString':'y','replaceAll':1}]
        with patch.object(volume, 'locked', side_effect=AssertionError('must not touch resources')):
            for request in requests:
                with self.assertRaisesRegex(volume.VolumeError, 'EDIT_REQUEST_INVALID'):
                    volume.edit_file(expected, request)
            with self.assertRaisesRegex(volume.VolumeError, 'EDIT_REQUEST_LIMIT'):
                volume.edit_file(expected, {'operation':'write','path':'app.py','content':'x'*(1<<20)})
    @unittest.skipUnless(sys.platform == 'linux', 'LINUX_FILESYSTEM_REQUIRED')
    def test_edit_view_rechecks_source_after_private_mapping_is_saved(self):
        expected = volume.identity('123', 'a'*64, 64<<20, owner_id='7', run_id='201', edit_id=str(uuid.uuid4()))
        # 先装配纯标准库平台核心，不依赖Windows Agent虚拟环境。
        sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'agent'))
        from backends.secret_free_view import SecretFreeView
        with tempfile.TemporaryDirectory() as directory, patch.object(volume, 'ROOT', Path(directory)/'volumes'), \
                patch.object(volume, 'mounted', side_effect=lambda folder, record: record), \
                patch.object(volume, 'command', return_value=subprocess.CompletedProcess([], 0, '', '')):
            folder = volume.resource_folder(expected)
            (folder/'work').mkdir(parents=True)
            volume.write_record(folder, {**expected, 'state':'READY'})
            source = Path(directory)/'source'
            source.mkdir()
            payload = source/'app.py'
            payload.write_bytes(b'print("before")')
            original = SecretFreeView.save_private_state
            def changed(view, path, **kwargs):
                digest = original(view, path, **kwargs)
                payload.write_bytes(b'print("after")')
                return digest
            with patch.object(SecretFreeView, 'save_private_state', changed), \
                    self.assertRaisesRegex(volume.VolumeError, 'VIEW_SOURCE_CHANGED'):
                volume.prepare_edit_view(expected, source)
            self.assertEqual(json.loads((folder/'record.json').read_text())['viewState'], 'FAILED')

    @unittest.skipUnless(sys.platform == 'linux', 'LINUX_FILESYSTEM_REQUIRED')
    def test_edit_view_retains_failed_intent_without_framework_dependencies(self):
        expected = volume.identity('123', 'a'*64, 64<<20, owner_id='7', run_id='201', edit_id=str(uuid.uuid4()))
        with tempfile.TemporaryDirectory() as directory, patch.object(volume, 'ROOT', Path(directory)/'volumes'), \
                patch.object(volume, 'mounted', side_effect=lambda folder, record: record), \
                patch.object(volume, 'command', return_value=subprocess.CompletedProcess([], 0, '', '')):
            folder = volume.resource_folder(expected)
            (folder/'work').mkdir(parents=True)
            volume.write_record(folder, {**expected, 'state':'READY'})
            source = Path(directory)/'source'
            source.mkdir()
            (source/'.env').write_bytes(b'API_TOKEN=fixture-secret-123456\n')
            (source/'asset.bin').write_bytes(b'\xfffixture-secret-123456')
            with self.assertRaisesRegex(volume.VolumeError, 'VIEW_SECRET_IN_BINARY'):
                volume.prepare_edit_view(expected, source)
            record = json.loads((folder/'record.json').read_text())
            self.assertEqual(record['viewState'], 'FAILED')
            self.assertFalse((folder/'work/view').exists())
            with self.assertRaisesRegex(volume.VolumeError, 'EDIT_VIEW_TARGET_NOT_EMPTY'):
                volume.prepare_edit_view(expected, source)

    @unittest.skipUnless(sys.platform == 'linux', 'LINUX_FILESYSTEM_REQUIRED')
    def test_edit_view_rejects_live_container_before_any_copy_or_permission_change(self):
        expected = volume.identity('123', 'a'*64, 64<<20, owner_id='7', run_id='201', edit_id=str(uuid.uuid4()))
        with tempfile.TemporaryDirectory() as directory, patch.object(volume, 'ROOT', Path(directory)/'volumes'), \
                patch.object(volume, 'mounted', side_effect=lambda folder, record: record), \
                patch.object(volume, 'command', return_value=subprocess.CompletedProcess([], 0, 'container', '')):
            folder = volume.resource_folder(expected)
            (folder/'work').mkdir(parents=True)
            volume.write_record(folder, {**expected, 'state':'READY'})
            with self.assertRaisesRegex(volume.VolumeError, 'APP_CONTAINERS_REMAIN'):
                volume.prepare_edit_view(expected, Path(directory)/'not-read')
            self.assertNotIn('viewState', json.loads((folder/'record.json').read_text()))
            self.assertEqual(list((folder/'work').iterdir()), [])

    def test_edit_identity_is_complete_string_bound_and_separate_from_install(self):
        eid=str(uuid.uuid4())
        expected=volume.identity('123','a'*64,64<<20,owner_id='7',run_id='201',edit_id=eid)
        self.assertEqual(expected['formatVersion'],3)
        self.assertEqual(volume.resource_folder(expected),volume.ROOT/('edit-'+eid))
        self.assertNotEqual(volume.resource_folder(expected),volume.resource_folder(volume.identity('123','a'*64,64<<20)))
        self.assertEqual(volume.container_filter(expected),'label=glyahh.import.edit='+eid)
        for changed in ({'owner_id':None},{'run_id':'01'},{'run_id':201},{'edit_id':'../escape'},
                        {'edit_id':eid.upper()},{'owner_id':'9223372036854775808'}):
            args={'owner_id':'7','run_id':'201','edit_id':eid};args.update(changed)
            with self.assertRaises(volume.VolumeError):volume.identity('123','a'*64,64<<20,**args)
        for field,value in [('ownerId','8'),('runId','202'),('editId',str(uuid.uuid4())),('contentHash','b'*64)]:
            with self.assertRaises(volume.VolumeError):volume.match(expected,{**expected,field:value,'state':'READY'})

    @unittest.skipUnless(sys.platform=='linux','LINUX_FILESYSTEM_REQUIRED')
    def test_absent_edit_release_requires_no_container_no_mount_and_no_loop(self):
        expected=volume.identity('123','a'*64,64<<20,owner_id='7',run_id='201',edit_id=str(uuid.uuid4()))
        with tempfile.TemporaryDirectory() as directory,patch.object(volume,'ROOT',Path(directory)/'volumes'):
            def commands(args,**kwargs):return subprocess.CompletedProcess(args,1 if args[0]=='findmnt' else 0,'','')
            with patch.object(volume,'command',side_effect=commands):
                self.assertTrue(volume.apply('release',expected)['verifiedAbsent'])
            for fault in ['docker','findmnt','losetup']:
                def failing(args,**kwargs):
                    result=commands(args,**kwargs)
                    if args[0]==fault:return subprocess.CompletedProcess(args,2 if fault=='findmnt' else 0,'remaining','')
                    return result
                with patch.object(volume,'command',side_effect=failing),self.assertRaises(volume.VolumeError):
                    volume.apply('release',expected)

    @unittest.skipUnless(sys.platform=='linux','LINUX_FILESYSTEM_REQUIRED')
    def test_other_edit_cannot_inspect_or_release_existing_edit_volume(self):
        expected=volume.identity('123','a'*64,64<<20,owner_id='7',run_id='201',edit_id=str(uuid.uuid4()))
        with tempfile.TemporaryDirectory() as directory,patch.object(volume,'ROOT',Path(directory)/'volumes'):
            folder=volume.resource_folder(expected);folder.mkdir(parents=True)
            volume.write_record(folder,{**expected,'state':'READY'})
            for field,value in [('ownerId','8'),('runId','202'),('contentHash','b'*64)]:
                changed={**expected,field:value}
                with self.assertRaisesRegex(volume.VolumeError,'VOLUME_IDENTITY_CONFLICT'):
                    volume.apply('release',changed)
                self.assertTrue((folder/'record.json').exists())
    def manifest(self, expected, data=b"frozen"):
        return {**expected, "files": [{"path": "src/main.py", "size": len(data), "sha256": hashlib.sha256(data).hexdigest(), "executable": False}]}

    def test_seed_manifest_rejects_identity_paths_duplicates_and_bad_sizes(self):
        expected = volume.identity("123", "a" * 64, 64 << 20)
        valid = self.manifest(expected)
        volume.seed_manifest(valid, expected)
        for field, value in (("appId", "124"), ("contentHash", "b" * 64)):
            with self.subTest(field=field), self.assertRaises(volume.VolumeError):
                volume.seed_manifest({**valid, field: value}, expected)
        for path in ("../escape", "/absolute", "a//b", "a\\b", "lost+found/data", "a/./b"):
            with self.subTest(path=path), self.assertRaises(volume.VolumeError):
                volume.seed_manifest({**valid, "files": [{**valid["files"][0], "path": path}]}, expected)
        with self.assertRaises(volume.VolumeError):
            volume.seed_manifest({**valid, "files": valid["files"] * 2}, expected)
        with self.assertRaises(volume.VolumeError):
            volume.seed_manifest({**valid, "files": [{**valid["files"][0], "size": True}]}, expected)

    @unittest.skipUnless(sys.platform == "linux", "LINUX_FILESYSTEM_REQUIRED")
    def test_seed_checks_bytes_and_keeps_existing_outputs_on_reuse(self):
        expected = volume.identity("123", "a" * 64, 64 << 20)
        with tempfile.TemporaryDirectory() as directory, patch.object(volume, "ROOT", Path(directory) / "volumes"), patch.object(volume, "mounted", side_effect=lambda folder, record: record), patch.object(volume, "command", return_value=subprocess.CompletedProcess([], 0, "", "")):
            folder = volume.ROOT / "123"
            (folder / "work").mkdir(parents=True)
            volume.write_record(folder, {**expected, "state": "READY"})
            source = Path(directory) / "sealed"
            (source / "src").mkdir(parents=True)
            (source / "src/main.py").write_bytes(b"frozen")
            manifest = self.manifest(expected)
            result = volume.seed(expected, source, manifest)
            self.assertEqual(result["seedState"], "SEALED")
            output = folder / "work/src/main.py"
            self.assertEqual(output.read_bytes(), b"frozen")
            output.write_bytes(b"installed-output")
            volume.seed(expected, source, manifest)
            self.assertEqual(output.read_bytes(), b"installed-output")
            with self.assertRaisesRegex(volume.VolumeError, "SEED_IDENTITY_CONFLICT"):
                volume.seed(expected, source, self.manifest(expected, b"other"))

    @unittest.skipUnless(sys.platform == "linux", "LINUX_FILESYSTEM_REQUIRED")
    def test_seed_failure_is_persistent_and_not_retried_over_partial_files(self):
        expected = volume.identity("123", "a" * 64, 64 << 20)
        with tempfile.TemporaryDirectory() as directory, patch.object(volume, "ROOT", Path(directory) / "volumes"), patch.object(volume, "mounted", side_effect=lambda folder, record: record), patch.object(volume, "command", return_value=subprocess.CompletedProcess([], 0, "", "")):
            folder = volume.ROOT / "123"
            (folder / "work").mkdir(parents=True)
            volume.write_record(folder, {**expected, "state": "READY"})
            source = Path(directory) / "sealed"
            (source / "src").mkdir(parents=True)
            (source / "src/main.py").write_bytes(b"broken")
            with self.assertRaisesRegex(volume.VolumeError, "SEED_CONTENT_CHANGED"):
                volume.seed(expected, source, self.manifest(expected))
            record = json.loads((folder / "record.json").read_text())
            self.assertEqual(record["state"], "SEEDING")
            with self.assertRaisesRegex(volume.VolumeError, "VOLUME_UNRESOLVED"):
                volume.seed(expected, source, self.manifest(expected))

    @unittest.skipUnless(sys.platform == "linux", "LINUX_FILESYSTEM_REQUIRED")
    def test_seed_extra_files_links_or_container_never_start_copy(self):
        expected = volume.identity("123", "a" * 64, 64 << 20)
        with tempfile.TemporaryDirectory() as directory, patch.object(volume, "ROOT", Path(directory) / "volumes"), patch.object(volume, "mounted", side_effect=lambda folder, record: record):
            folder = volume.ROOT / "123"
            (folder / "work").mkdir(parents=True)
            volume.write_record(folder, {**expected, "state": "READY"})
            source = Path(directory) / "sealed"
            (source / "src").mkdir(parents=True)
            payload = source / "src/main.py"
            payload.write_bytes(b"frozen")
            with patch.object(volume, "command", return_value=subprocess.CompletedProcess([], 0, "container", "")):
                with self.assertRaisesRegex(volume.VolumeError, "APP_CONTAINERS_REMAIN"):
                    volume.seed(expected, source, self.manifest(expected))
            with patch.object(volume, "command", return_value=subprocess.CompletedProcess([], 0, "", "")):
                extra = source / "extra"
                extra.write_bytes(b"unlisted")
                with self.assertRaisesRegex(volume.VolumeError, "SEED_FILE_LIST_CHANGED"):
                    volume.seed(expected, source, self.manifest(expected))
                extra.unlink()
                payload.unlink()
                payload.symlink_to(extra)
                with self.assertRaisesRegex(volume.VolumeError, "RESOURCE_PATH_LINK"):
                    volume.seed(expected, source, self.manifest(expected))
            self.assertEqual(json.loads((folder / "record.json").read_text())["state"], "READY")
            self.assertFalse((folder / "work/src").exists())

    @unittest.skipUnless(sys.platform == "linux", "LINUX_FILESYSTEM_REQUIRED")
    def test_seed_source_hardlinks_are_not_read(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory)
            (source / "a").write_bytes(b"x")
            volume.os.link(source / "a", source / "b")
            with self.assertRaisesRegex(volume.VolumeError, "SEED_FILE_TYPE_INVALID"):
                with volume.source_file(source, "a"):
                    self.fail("hardlink was opened as approved content")

    @unittest.skipUnless(sys.platform == "linux", "LINUX_FILESYSTEM_REQUIRED")
    def test_empty_directories_survive_seed_and_bad_directory_type_is_rejected(self):
        expected = volume.identity("123", "a" * 64, 64 << 20)
        manifest = self.manifest(expected)
        with self.assertRaises(volume.VolumeError):
            volume.seed_manifest({**manifest,"directories":[{}]},expected)
        with tempfile.TemporaryDirectory() as directory, patch.object(volume,"ROOT",Path(directory)/"volumes"), patch.object(volume,"mounted",side_effect=lambda folder,record:record), patch.object(volume,"command",return_value=subprocess.CompletedProcess([],0,"","")):
            folder=volume.ROOT/"123";(folder/"work").mkdir(parents=True)
            volume.write_record(folder,{**expected,"state":"READY"})
            source=Path(directory)/"sealed";(source/"src").mkdir(parents=True);(source/"empty/nested").mkdir(parents=True)
            (source/"src/main.py").write_bytes(b"frozen")
            result=volume.seed(expected,source,{**manifest,"directories":["src","empty","empty/nested"]})
            self.assertEqual(result["seedState"],"SEALED")
            self.assertTrue((folder/"work/empty/nested").is_dir())

    @unittest.skipUnless(sys.platform == "linux", "LINUX_FILESYSTEM_REQUIRED")
    def test_recover_refuses_unsealed_volume_or_uncertain_mount(self):
        expected=volume.identity("123","a"*64,64<<20)
        with tempfile.TemporaryDirectory() as directory, patch.object(volume,"ROOT",Path(directory)/"volumes"):
            folder=volume.ROOT/"123";folder.mkdir(parents=True)
            volume.write_record(folder,{**expected,"state":"READY"})
            with patch.object(volume,"command",return_value=subprocess.CompletedProcess([],0,"","")):
                with self.assertRaisesRegex(volume.VolumeError,"SEED_NOT_SEALED"):
                    volume.apply("recover",expected)
            volume.write_record(folder,{**expected,"state":"READY","seedState":"SEALED"})
            def commands(args,**kwargs):
                return subprocess.CompletedProcess(args,2 if args[0]=="findmnt" else 0,"","")
            with patch.object(volume,"command",side_effect=commands):
                with self.assertRaisesRegex(volume.VolumeError,"MOUNT_STATE_UNKNOWN"):
                    volume.apply("recover",expected)
            self.assertEqual(json.loads((folder/"record.json").read_text())["state"],"READY")

    def test_identity_is_numeric_and_capacity_is_aligned(self):
        self.assertEqual(volume.identity("123", "a" * 64, 64 << 20)["appId"], "123")
        for app in ("0", "-1", "../12", "12/13", "01", "1;id", "9223372036854775808"):
            with self.subTest(app=app), self.assertRaises(volume.VolumeError):
                volume.identity(app, "a" * 64, 64 << 20)
        for size in (0, (1 << 20) - 1, (64 << 20) + 1, (8 << 30) + 4096):
            with self.subTest(size=size), self.assertRaises(volume.VolumeError):
                volume.identity("123", "a" * 64, size)
        with self.assertRaises(volume.VolumeError):
            volume.identity("123", "not-a-hash", 64 << 20)

    def test_unknown_action_never_becomes_release(self):
        with self.assertRaises(volume.VolumeError):
            volume.apply("delete-everything", volume.identity("123", "a" * 64, 64 << 20))

    def test_legacy_volume_cannot_be_silently_reused(self):
        expected = volume.identity("123", "a" * 64, 64 << 20)
        self.assertEqual(expected["formatVersion"], 2)
        legacy = {key: value for key, value in expected.items() if key != "formatVersion"}
        with self.assertRaises(volume.VolumeError):
            volume.match(expected, {**legacy, "state": "READY"})

    def test_journal_minimum_is_not_silently_downgraded(self):
        with self.assertRaises(volume.VolumeError):
            volume.identity("123", "a" * 64, 8 << 20)

    @unittest.skipUnless(sys.platform == "linux", "LINUX_FILESYSTEM_REQUIRED")
    def test_space_reservation_failure_keeps_preparing_and_does_not_format(self):
        expected = volume.identity("123", "a" * 64, 64 << 20)
        with tempfile.TemporaryDirectory() as directory, patch.object(volume, "ROOT", Path(directory) / "volumes"), patch.object(volume.os, "posix_fallocate", side_effect=OSError("no space")), patch.object(volume, "command") as command:
            with self.assertRaises(OSError):
                volume.apply("create", expected)
            command.assert_not_called()
            self.assertEqual(json.loads((volume.ROOT / "123/record.json").read_text())["state"], "PREPARING")

    @unittest.skipUnless(sys.platform == "linux", "LINUX_FILESYSTEM_REQUIRED")
    def test_host_headroom_rejects_before_reservation_or_format(self):
        expected = volume.identity("123", "a" * 64, 64 << 20)
        with tempfile.TemporaryDirectory() as directory, patch.object(volume, "ROOT", Path(directory) / "volumes"), patch.object(volume.shutil, "disk_usage", return_value=SimpleNamespace(free=expected["diskBytes"]+volume.HEADROOM_BYTES-1)), patch.object(volume.os, "posix_fallocate") as allocate, patch.object(volume, "command") as command:
            with self.assertRaisesRegex(volume.VolumeError, "HOST_SPACE_INSUFFICIENT"):
                volume.apply("create", expected)
            allocate.assert_not_called()
            command.assert_not_called()

    @unittest.skipUnless(sys.platform == "linux", "LINUX_FILESYSTEM_REQUIRED")
    def test_resume_refuses_live_or_uncertain_resource(self):
        expected = volume.identity("123", "a" * 64, 64 << 20)
        with tempfile.TemporaryDirectory() as directory, patch.object(volume, "ROOT", Path(directory) / "volumes"):
            folder = volume.ROOT / "123"
            folder.mkdir(parents=True)
            volume.write_record(folder, {**expected, "state": "SUSPENDED"})
            with patch.object(volume, "command", return_value=subprocess.CompletedProcess(["docker"], 0, "fixture-container", "")):
                with self.assertRaisesRegex(volume.VolumeError, "APP_CONTAINERS_REMAIN"):
                    volume.apply("resume", expected)
            self.assertEqual(json.loads((folder / "record.json").read_text())["state"], "SUSPENDED")

    def test_existing_volume_never_changes_content_or_capacity(self):
        expected = volume.identity("123", "a" * 64, 64 << 20)
        volume.match(expected, {**expected, "state": "READY"})
        for field, value in (("appId", "124"), ("contentHash", "b" * 64), ("diskBytes", 128 << 20)):
            with self.subTest(field=field), self.assertRaises(volume.VolumeError):
                volume.match(expected, {**expected, field: value, "state": "READY"})

    def test_partial_creation_is_not_reused_as_ready(self):
        expected = volume.identity("123", "a" * 64, 64 << 20)
        for state in ("PREPARING", "RELEASING", "BROKEN", None):
            with self.subTest(state=state), self.assertRaises(volume.VolumeError):
                volume.match(expected, {**expected, "state": state})

    def test_links_and_unmarked_existing_content_are_not_adopted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "target"
            target.mkdir()
            link = root / "link"
            try:
                link.symlink_to(target, target_is_directory=True)
            except OSError:
                self.skipTest("SYMLINK_NOT_AVAILABLE")
            with self.assertRaises(volume.VolumeError):
                volume.no_links(link)
            (target / "volume.img").write_bytes(b"existing")
            with self.assertRaises(volume.VolumeError):
                volume.require_new(target)

    def test_command_failure_is_not_silently_successful(self):
        with patch.object(volume.subprocess, "run", return_value=subprocess.CompletedProcess(["mount"], 1, "", "denied")):
            with self.assertRaisesRegex(volume.VolumeError, "COMMAND_FAILED"):
                volume.command(["mount"])
            self.assertEqual(volume.command(["mount"], allow_failure=True).returncode, 1)

    @unittest.skipUnless(sys.platform == "linux", "LINUX_FILESYSTEM_REQUIRED")
    def test_interrupted_creation_keeps_unresolved_identity(self):
        expected = volume.identity("123", "a" * 64, 64 << 20)
        with tempfile.TemporaryDirectory() as directory, patch.object(volume, "ROOT", Path(directory) / "volumes"), patch.object(volume, "command", side_effect=volume.VolumeError("FORMAT_FAILED")):
            with self.assertRaisesRegex(volume.VolumeError, "FORMAT_FAILED"):
                volume.apply("create", expected)
            path = volume.ROOT / "123" / "record.json"
            self.assertEqual(json.loads(path.read_text())["state"], "PREPARING")
            with self.assertRaisesRegex(volume.VolumeError, "VOLUME_UNRESOLVED"):
                volume.apply("create", expected)

    @unittest.skipUnless(sys.platform == "linux", "LINUX_FILESYSTEM_REQUIRED")
    def test_engine_failure_and_unknown_mount_never_delete_resource(self):
        expected = volume.identity("123", "a" * 64, 64 << 20)
        with tempfile.TemporaryDirectory() as directory, patch.object(volume, "ROOT", Path(directory) / "volumes"):
            folder = volume.ROOT / "123"
            folder.mkdir(parents=True)
            volume.write_record(folder, {**expected, "state": "READY"})
            with patch.object(volume, "command", side_effect=volume.VolumeError("ENGINE_UNKNOWN")):
                with self.assertRaisesRegex(volume.VolumeError, "ENGINE_UNKNOWN"):
                    volume.apply("release", expected)
            self.assertTrue((folder / "record.json").exists())
            def commands(args, **kwargs):
                return subprocess.CompletedProcess(args, 2 if args[0] == "findmnt" else 0, "", "")
            with patch.object(volume, "command", side_effect=commands):
                with self.assertRaisesRegex(volume.VolumeError, "MOUNT_STATE_UNKNOWN"):
                    volume.apply("release", expected)
            self.assertEqual(json.loads((folder / "record.json").read_text())["state"], "READY")

    def test_wrong_backing_size_is_not_mounted_or_adopted(self):
        expected = volume.identity("123", "a" * 64, 64 << 20)
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            (folder / "volume.img").write_bytes(b"wrong-size")
            with patch.object(volume, "command") as command:
                with self.assertRaisesRegex(volume.VolumeError, "BACKING_SIZE_MISMATCH"):
                    volume.mounted(folder, expected)
                command.assert_not_called()

    @unittest.skipUnless(sys.platform == "linux", "LINUX_FILESYSTEM_REQUIRED")
    def test_missing_journal_is_explicitly_refused(self):
        expected = volume.identity("123", "a" * 64, 64 << 20)
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            with open(folder / "volume.img", "wb") as stream:
                volume.os.posix_fallocate(stream.fileno(), 0, expected["diskBytes"])
            with patch.object(volume, "command", return_value=subprocess.CompletedProcess(["tune2fs"], 0, "Filesystem features: extents\n", "")):
                with self.assertRaisesRegex(volume.VolumeError, "JOURNAL_NOT_CONFIRMED"):
                    volume.backing(folder, expected)

    @unittest.skipUnless(sys.platform == "linux", "LINUX_FILESYSTEM_REQUIRED")
    def test_uncorrected_filesystem_never_mounts_or_becomes_ready(self):
        expected = volume.identity("123", "a" * 64, 64 << 20)
        with tempfile.TemporaryDirectory() as directory, patch.object(volume, "ROOT", Path(directory) / "volumes"):
            folder = volume.ROOT / "123"
            folder.mkdir(parents=True)
            volume.write_record(folder, {**expected, "state": "SUSPENDED"})
            calls = []
            def commands(args, **kwargs):
                calls.append(args[0])
                return subprocess.CompletedProcess(args, 4 if args[0] == "e2fsck" else 0, "", "")
            with patch.object(volume, "backing", return_value=folder / "volume.img"), patch.object(volume, "command", side_effect=commands):
                with self.assertRaisesRegex(volume.VolumeError, "FILESYSTEM_CHECK_FAILED:4"):
                    volume.apply("resume", expected)
            self.assertNotIn("mount", calls)
            self.assertEqual(json.loads((folder / "record.json").read_text())["state"], "RESUMING")


if __name__ == "__main__":
    unittest.main()
