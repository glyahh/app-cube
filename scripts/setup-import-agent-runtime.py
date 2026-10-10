"""从现有uv.lock准备独立WSL Agent环境；不改Windows虚拟环境、不执行工程脚本。"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True)
    parser.add_argument('--resume', action='store_true')
    parser.add_argument('--requirements')
    args = parser.parse_args()
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    root = Path(__file__).resolve().parents[1]
    lock_hash = hashlib.sha256((root / 'agent/uv.lock').read_bytes()).hexdigest()
    runtime = '/var/lib/glyahh/agent-runtimes/item1-' + lock_hash
    report = {'status': 'PREPARING', 'scope': 'LINUX_AGENT_DEPENDENCIES_NOT_MODEL_ACCEPTANCE',
              'lockHash': lock_hash, 'runtimePath': runtime}
    prefix = ['wsl.exe', '-d', 'Ubuntu', '-u', 'root', '--exec']

    def command(args, timeout=120, cwd=None):
        result = subprocess.run(args, capture_output=True, text=True, encoding='utf-8', errors='replace',
                                timeout=timeout, cwd=cwd)
        # 依赖工具的诊断不保留可能来自平台代理的URL用户信息。
        log = re.sub(r'(https?://)[^/\s@]+@', r'\1***@', result.stdout + result.stderr)
        with (output / 'operations.log').open('a', encoding='utf-8') as stream:
            stream.write(log)
        if result.returncode != 0:
            raise RuntimeError('RUNTIME_COMMAND_FAILED')
        return result.stdout.strip()

    try:
        requirements = Path(args.requirements).resolve() if args.requirements else output / 'requirements.txt'
        if args.resume:
            if not args.requirements or not requirements.is_file():
                raise RuntimeError('RESUME_REQUIRES_ORIGINAL_REQUIREMENTS')
        else:
            if args.requirements:
                raise RuntimeError('REQUIREMENTS_OVERRIDE_NOT_ALLOWED')
            command(['uv', 'export', '--frozen', '--no-dev', '--no-emit-project', '--format', 'requirements-txt',
                     '--output-file', str(requirements)], cwd=root / 'agent')
        requirements_hash = hashlib.sha256(requirements.read_bytes()).hexdigest()
        report['requirementsHash'] = requirements_hash
        report['status'] = 'CREATING'
        initialize = r'''
import fcntl,json,os,pathlib,stat,sys
base=pathlib.Path('/var/lib/glyahh/agent-runtimes'); path=pathlib.Path(sys.argv[1])
assert path.parent==base and path.name=='item1-'+sys.argv[2]
for p in (base,*base.parents):assert not p.is_symlink()
base.mkdir(mode=0o700,parents=True,exist_ok=True)
assert base.stat().st_uid==0 and stat.S_IMODE(base.stat().st_mode)==0o700
lock=base/'.lock';assert not lock.is_symlink()
with lock.open('a') as guard:
 fcntl.flock(guard,fcntl.LOCK_EX)
 if sys.argv[4]=='resume':
  assert not path.is_symlink() and path.stat().st_uid==0 and stat.S_IMODE(path.stat().st_mode)==0o700
  p=path/'runtime.json';assert not p.is_symlink() and p.stat().st_uid==0 and stat.S_IMODE(p.stat().st_mode)==0o600
  data=json.loads(p.read_text());assert data=={'state':'PLANNED','lockHash':sys.argv[2],'requirementsHash':sys.argv[3],'runtimePath':str(path)}
  for p in path.rglob('*'):
   if p.is_symlink():
    assert (p.parent==path/'venv/bin' and p.name in ('python','python3','python3.14','\U0001d70bthon') and p.resolve()==pathlib.Path('/usr/bin/python3').resolve()) or (p==path/'venv/lib64' and os.readlink(p)=='lib')
 else:
  assert not path.exists(),'runtime exists; inspect it instead of overwriting'
  path.mkdir(mode=0o700)
  data={'state':'PLANNED','lockHash':sys.argv[2],'requirementsHash':sys.argv[3],'runtimePath':str(path)}
  with (path/'runtime.json').open('x') as f:os.chmod(f.name,0o600);json.dump(data,f);f.flush();os.fsync(f.fileno())
print('RUNTIME_INTENT_REGISTERED')
'''
        command(prefix + ['python3', '-c', initialize, runtime, lock_hash, requirements_hash,
                          'resume' if args.resume else 'create'])
        command(prefix + ['python3', '-m', 'venv', runtime + '/venv'])
        requirements_linux = command(prefix + ['wslpath', '-u', str(requirements)])
        report['status'] = 'INSTALLING'
        (output / 'result.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
        command(prefix + ['env', '-u', 'PIP_INDEX_URL', '-u', 'PIP_EXTRA_INDEX_URL', 'PIP_CONFIG_FILE=/dev/null',
                         runtime + '/venv/bin/python', '-m', 'pip', 'install', '--disable-pip-version-check',
                         '--index-url', 'https://pypi.org/simple', '--only-binary=:all:', '--require-hashes', '-r', requirements_linux], timeout=600)
        probe = '''import importlib.metadata as m,json,sys
import deepagents,langgraph,langchain,psycopg,langchain_openai
v={p:m.version(p) for p in ['deepagents','langgraph','langchain','psycopg','langchain-openai']}
assert v['deepagents']=='0.7.13' and v['langgraph']=='1.2.11' and v['langchain']=='1.4.0'
assert (3,12)<=sys.version_info[:2]<(3,15)
print(json.dumps({'versions':v,'python':sys.version.split()[0],'executable':sys.executable}))'''
        report['probe'] = json.loads(command(prefix + [runtime + '/venv/bin/python', '-c', probe]))
        ready = '''import json,os,pathlib,sys
p=pathlib.Path(sys.argv[1])/'runtime.json';v=json.loads(p.read_text());assert v['lockHash']==sys.argv[2]
v['state']='READY';t=p.with_suffix('.pending');assert not t.exists()
with t.open('x') as f:os.chmod(t,0o600);json.dump(v,f);f.flush();os.fsync(f.fileno())
os.replace(t,p);fd=os.open(p.parent,os.O_RDONLY|os.O_DIRECTORY);os.fsync(fd);os.close(fd)
print('RUNTIME_READY')'''
        command(prefix + ['python3', '-c', ready, runtime, lock_hash])
        report['status'] = 'PASS'
    except Exception as error:
        report.update(status='FAIL', errorType=type(error).__name__)
        raise
    finally:
        (output / 'result.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
        print(json.dumps({'status': report['status'], 'runtimePath': runtime}))


if __name__ == '__main__':
    main()
