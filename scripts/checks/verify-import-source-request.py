"""实际来源请求找回、重放、隔离与数据库约束；不使用私有平台 Token。"""
import http.cookiejar
import json
import secrets
import time
import urllib.request
import urllib.error
import uuid
from pathlib import Path
import psycopg


def main():
    evidence = Path('temp/import-backend-publication-20261004/source-request-api')
    evidence.mkdir(parents=True, exist_ok=True)
    report = {'status': 'RUNNING', 'checks': [], 'wholeFirstRequirement': 'NOT_COMPLETE'}
    dsn = next(line.split('=', 1)[1].strip().strip('"').strip("'") for line in Path('.env').read_text(encoding='utf-8-sig').splitlines() if line.startswith('AGENT_POSTGRES_DSN='))

    def client():
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        csrf = None
        def call(path, body=None, expected=20000, method=None):
            headers = {'Origin': 'http://localhost:5173'}
            if csrf: headers['X-Project-Import-CSRF'] = csrf
            if body is not None: headers['Content-Type'] = 'application/json'
            request = urllib.request.Request('http://127.0.0.1:8124/api'+path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method or ('GET' if body is None else 'POST'))
            try: response = opener.open(request, timeout=30)
            except urllib.error.HTTPError as error: response = error
            with response: result = json.loads(response.read())
            assert result['code'] == expected, f'{path}: {result["code"]} != {expected}'
            return result.get('data')
        account = 'source_request_'+secrets.token_hex(8)
        password = secrets.token_urlsafe(24)
        owner = str(call('/user/register', {'userAccount': account, 'userPassword': password, 'checkPassword': password}))
        call('/user/login', {'userAccount': account, 'userPassword': password})
        csrf = call('/project/import/csrf')
        return owner, call

    task = None
    try:
        owner, call = client()
        foreign, other = client()
        key = str(uuid.uuid4())
        prefix = '/project/import/git/tasks'
        payload = {'ownerId': owner, 'appName': '来源找回验证', 'provider': 'GitHub', 'repositoryUrl': 'https://github.com/octocat/Hello-World', 'requestId': key,
                   'options': {'skipDependencies': False, 'skipBuildOutputs': False, 'includeSensitive': False}}
        task = call(prefix, payload)
        report.update(ownerId=owner, foreignOwnerId=foreign, requestId=key, taskId=task['taskId'])
        replay = call(prefix, payload)
        assert replay['taskId'] == task['taskId']
        found = call(prefix+'/request/'+key)
        assert found['taskId'] == task['taskId']
        other(prefix+'/request/'+key, expected=40400)
        call(prefix, {**payload, 'appName': '改名'}, expected=40900)
        call(prefix, {**payload, 'options': {**payload['options'], 'skipBuildOutputs': True}}, expected=40900)
        call(prefix, {**payload, 'credentialId': str(uuid.uuid4()), 'credentialVersion': '1'}, expected=40900)
        report['checks'].append({'name': '同请求原任务、参数冲突、跨用户不可见', 'status': 'PASS'})
        deadline = time.monotonic()+120
        while found['status'] == 'RESOLVING_SOURCE' and time.monotonic()<deadline:
            time.sleep(.3)
            found = call(prefix+'/request/'+key)
        assert found['status'] == 'WAITING_BRANCH', found.get('error')
        selected = call(prefix+'/'+task['taskId']+'/branch', {'ownerId': owner, 'revision': found['revision'], 'branch': found['branches'][0]['name']})
        deadline = time.monotonic()+180
        while selected['status'] == 'READING_CONTENT' and time.monotonic()<deadline:
            time.sleep(.3)
            selected = call(prefix+'/request/'+key)
        assert selected['status'] == 'WAITING_CONFIRMATION', selected.get('error')
        changed = call(prefix+'/'+task['taskId']+'/range', {'ownerId': owner, 'revision': selected['revision'], 'appName': '调整后名称', 'options': payload['options']})
        deadline = time.monotonic()+120
        while changed['status'] == 'READING_CONTENT' and time.monotonic()<deadline:
            time.sleep(.3)
            changed = call(prefix+'/request/'+key)
        assert changed['status'] == 'WAITING_CONFIRMATION', changed.get('error')
        replay = call(prefix, payload)
        assert replay['taskId'] == task['taskId'] and replay['definition']['appName'] == '调整后名称'
        assert replay['commit'] == selected['commit'] and replay['contentHash'] == changed['contentHash']
        report['checks'].append({'name': '范围更新后创建摘要不变、恢复当前固定内容', 'status': 'PASS'})
        with psycopg.connect(dsn) as db:
            row = db.execute('SELECT owner_id,client_request_id,client_request_hash,resources_json FROM biz.project_import_source WHERE task_id=%s', (uuid.UUID(task['taskId']),)).fetchone()
            assert str(row[0]) == owner and str(row[1]) == key and len(row[2]) == 64
            assert db.execute('SELECT count(*) FROM biz.project_import_source WHERE owner_id=%s AND client_request_id=%s', (int(owner), uuid.UUID(key))).fetchone()[0] == 1
            # 每个约束试验只改一个字段，嵌套事务失败回滚，不修改正式状态。
            for statement, args in [
                ('UPDATE biz.project_import_source SET client_request_hash=NULL WHERE task_id=%s', (uuid.UUID(task['taskId']),)),
                ('UPDATE biz.project_import_source SET client_request_id=NULL WHERE task_id=%s', (uuid.UUID(task['taskId']),)),
                ('UPDATE biz.project_import_source SET client_request_hash=%s WHERE task_id=%s', ('bad', uuid.UUID(task['taskId']))),
            ]:
                rejected = False
                try:
                    with db.transaction(): db.execute(statement, args)
                except psycopg.errors.CheckViolation: rejected = True
                assert rejected
            report['checks'].append({'name': 'V18 实际数据库拒绝半空与非法摘要；仅一个来源', 'status': 'PASS'})
        stopped = call(prefix+'/'+task['taskId']+'/cancel?revision='+changed['revision'], method='POST')
        assert stopped['status'] == 'CANCELLED' and stopped['cleanup'] == 'DONE'
        again = call(prefix, payload)
        assert again['taskId'] == task['taskId'] and again['status'] == 'CANCELLED'
        assert not Path('temp/project_import_private', task['taskId']+'.git').exists()
        assert not Path('temp/project_import_private', task['taskId']).exists()
        with psycopg.connect(dsn) as db:
            assert db.execute('SELECT count(*) FROM biz.project_import_publication WHERE task_id=%s', (uuid.UUID(task['taskId']),)).fetchone()[0] == 0
        report['checks'].append({'name': '取消后找回旧终态，不重新联网或创建应用；两类暂存清理', 'status': 'PASS'})
        report['status'] = 'PASS'
    except Exception as error:
        report['status'] = 'FAIL'
        report['errorType'] = type(error).__name__
        report['error'] = str(error)[:300]
        raise
    finally:
        (evidence/'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps({'status': report['status'], 'evidence': str(evidence)}, ensure_ascii=False))


if __name__ == '__main__': main()
