"""独立读回本轮应用资源；不执行删除，不把产品删除响应当清理证明。"""
import json
from pathlib import Path
import subprocess
import psycopg


def verify_cleanup(root, dsn, app, owner, task, account):
    resources = []
    def gone(identity, probe, remaining):
        resources.append({'identity': identity, 'probe': probe, 'remaining': bool(remaining), 'observed': True})
        assert not remaining, 'RESOURCE_REMAINS:' + probe
    def linux(args):
        result = subprocess.run(['wsl.exe', '-d', 'Ubuntu', '-u', 'root', '--exec', *args],
                                capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=45)
        assert result.returncode == 0, 'CLEANUP_PROBE_FAILED'
        return result.stdout.strip()
    with psycopg.connect(dsn) as db:
        gone(str(app), 'database-active-app', db.execute('SELECT count(*) FROM biz.app WHERE id=%s AND user_id=%s AND is_delete=0', (int(app), int(owner))).fetchone()[0])
        gone(account, 'database-active-account', db.execute('SELECT count(*) FROM biz."user" WHERE id=%s AND user_account=%s AND is_delete=0', (int(owner), account)).fetchone()[0])
        edits = db.execute('SELECT edit_id,resources_json,cleanup,closed_at IS NOT NULL FROM biz.project_import_agent_edit WHERE app_id=%s AND owner_id=%s', (int(app), int(owner))).fetchall()
        outputs = db.execute('SELECT edit_id,resource_path,phase FROM biz.project_import_agent_edit_outputs WHERE app_id=%s AND owner_id=%s', (int(app), int(owner))).fetchall()
    for path in (root / 'temp/code_output' / ('project_' + str(app)), root / 'temp/project_import_private' / str(task)):
        gone(str(path), 'filesystem-exists', path.exists())
    mounts = set(linux(['findmnt', '-rn', '-o', 'TARGET']).splitlines())
    for edit, registered, cleanup, closed in edits:
        assert closed and cleanup == 'DONE' and all(r['state'] == 'RELEASED' for r in registered), 'EDIT_CLEANUP_LEDGER_OPEN'
        folder = '/var/lib/glyahh/import-disks/edit-' + str(edit)
        gone(str(edit), 'docker-exact-edit-label', linux(['docker', 'ps', '-aq', '--filter', 'label=glyahh.import.edit=' + str(edit)]))
        gone(folder + '/work', 'findmnt-exact-target', folder + '/work' in mounts)
        gone(folder + '/volume.img', 'losetup-exact-backing-file', linux(['/usr/sbin/losetup', '-j', folder + '/volume.img']))
        exists = linux(['python3', '-c', 'import os,sys;print(int(os.path.lexists(sys.argv[1])))', folder])
        assert exists in ('0', '1'), 'VOLUME_PROBE_INVALID'
        gone(folder, 'linux-directory-exists', exists == '1')
        gone(str(edit), 'publication-directory-exists', (root / 'temp/project_edit_publish' / str(edit)).exists())
    for edit, resource, phase in outputs:
        expected = (root / 'temp/project_edit_outputs' / str(edit)).resolve()
        assert Path(resource).resolve() == expected and phase == 'RELEASED', 'OUTPUT_CLEANUP_IDENTITY_INVALID'
        gone(str(expected), 'output-resource-directory-exists', expected.exists())
    return {'name': 'independent-resource-cleanup', 'status': 'PASS', 'resources': resources}
