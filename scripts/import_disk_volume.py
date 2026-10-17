"""平台受控的固定容量 ext4 卷操作；不运行工程脚本，业务资格由调用方核对。"""
import argparse
import contextlib
import json
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import stat
import errno
import uuid
import base64

ROOT = Path("/var/lib/glyahh/import-disks")
HEADROOM_BYTES = 256 << 20


class VolumeError(Exception):
    pass


def identity(app_id, content_hash, disk_bytes, *, owner_id=None, run_id=None, edit_id=None):
    if not isinstance(app_id, str) or not re.fullmatch(r"[1-9][0-9]{0,18}", app_id) or int(app_id) > 9223372036854775807:
        raise VolumeError("APP_ID_INVALID")
    if not isinstance(content_hash, str) or not re.fullmatch(r"[0-9a-f]{64}", content_hash):
        raise VolumeError("CONTENT_HASH_INVALID")
    if not isinstance(disk_bytes, int) or not 16 << 20 <= disk_bytes <= 8 << 30 or disk_bytes % 4096:
        raise VolumeError("DISK_LIMIT_INVALID")
    result = {"appId": app_id, "contentHash": content_hash, "diskBytes": disk_bytes, "formatVersion": 2}
    if any(value is not None for value in (owner_id, run_id, edit_id)):
        for value in (owner_id, run_id):
            if not isinstance(value, str) or not re.fullmatch(r"[1-9][0-9]{0,18}", value) or int(value) > 9223372036854775807:
                raise VolumeError("EDIT_IDENTITY_INVALID")
        try:
            if not isinstance(edit_id, str) or str(uuid.UUID(edit_id)) != edit_id:
                raise ValueError()
        except (ValueError, TypeError, AttributeError):
            raise VolumeError("EDIT_IDENTITY_INVALID")
        result.update(ownerId=owner_id, runId=run_id, editId=edit_id, formatVersion=3)
    return result


def resource_folder(expected):
    # 编辑与安装卷不同槽位；owner/run/edit/hash必须全部匹配，不复用同应用的旧编辑卷。
    normalized = identity(expected['appId'], expected['contentHash'], expected['diskBytes'],
                          owner_id=expected.get('ownerId'), run_id=expected.get('runId'), edit_id=expected.get('editId'))
    if any(expected.get(key) != value for key, value in normalized.items()):
        raise VolumeError("VOLUME_IDENTITY_CONFLICT")
    return ROOT / ('edit-' + normalized['editId'] if normalized['formatVersion'] == 3 else normalized['appId'])


def container_filter(expected):
    return 'label=glyahh.import.edit=' + expected['editId'] if expected.get('formatVersion') == 3 else 'label=glyahh.import.app=' + expected['appId']


def no_links(path):
    for entry in (path, *path.parents):
        if entry.is_symlink():
            raise VolumeError("RESOURCE_PATH_LINK")


def edit_hard_limits(item):
    """平台从实际容器PID核对内核额度；不能强制swap时不授予执行资格。"""
    try:
        identifier=item['Id'];pid=item['State']['Pid']
        if os.geteuid()!=0 or type(pid) is not int or pid<=0 or not re.fullmatch('[0-9a-f]{64}',identifier):raise ValueError()
        endpoint=os.environ.get('DOCKER_HOST') or command(['docker','context','inspect','--format','{{.Endpoints.docker.Host}}']).stdout.strip()
        if not endpoint.startswith('unix:///') or not stat.S_ISSOCK(Path(endpoint[7:]).stat().st_mode):raise ValueError()
        lines=Path('/proc',str(pid),'cgroup').read_text().splitlines()
        unified=[line[3:] for line in lines if line.startswith('0::')]
        if len(unified)!=1 or not unified[0].startswith('/') or identifier not in unified[0] or '..' in Path(unified[0]).parts:raise ValueError()
        group=Path('/sys/fs/cgroup')/unified[0].lstrip('/');no_links(group)
        memory=int((group/'memory.max').read_text());pids=int((group/'pids.max').read_text())
        quota,period=map(int,(group/'cpu.max').read_text().split())
        if not 0<memory<=1<<30 or not 0<pids<=128 or not 0<quota<=period:raise ValueError()
        swap=group/'memory.swap.max'
        if swap.read_text().strip()!='0':swap.write_text('0\n')
        if swap.read_text().strip()!='0':raise ValueError()
        current=json.loads(command(['docker','inspect',identifier]).stdout)[0]
        if current['Id']!=identifier or current['State']['Pid']!=pid or current['State']['Running'] is not True:raise ValueError()
        return {'memoryBytes':memory,'swapBytes':0,'pids':pids,'cpuQuota':quota,'cpuPeriod':period}
    except (OSError,ValueError,KeyError,TypeError,VolumeError) as error:
        raise VolumeError('EDIT_LIMITS_UNENFORCEABLE') from error


def edit_container(expected, create=False, start_id=None):
    """平台先持久登记创建意图；停止只接受完整身份及挂载匹配的容器。"""
    if expected.get('formatVersion')!=3:raise VolumeError('EDIT_IDENTITY_REQUIRED')
    if start_id is not None and not re.fullmatch('[0-9a-f]{64}',start_id):raise VolumeError('EDIT_CONTAINER_IDENTITY_CONFLICT')
    folder=resource_folder(expected)
    with locked():
        no_links(folder)
        listed=command(['docker','ps','-aq','--no-trunc','--filter',container_filter(expected)]).stdout.split()
        name='glyahh-edit-'+expected['editId']
        if start_id is not None and not folder.exists():raise VolumeError('EDIT_CONTAINER_START_NOT_ALLOWED')
        if not create and not folder.exists():
            absent=command(['docker','inspect',name],allow_failure=True)
            if listed or absent.returncode==0 or 'no such object' not in absent.stderr.lower():raise VolumeError('EDIT_CONTAINER_UNREGISTERED')
            return {**expected,'state':'STOP_CONFIRMED','containerName':name,'containerId':None}
        path=folder/'record.json';no_links(path)
        record=json.loads(path.read_text(encoding='utf-8'));match(expected,record);mounted(folder,record)
        labels={'glyahh.import.edit':expected['editId'],'glyahh.import.owner':expected['ownerId'],
                'glyahh.import.app':expected['appId'],'glyahh.import.run':expected['runId'],
                'glyahh.import.content':expected['contentHash'],'glyahh.session_id':'app:'+expected['appId']}
        registered=record.get('editContainer')
        if start_id is not None and (not isinstance(registered,dict) or registered.get('state')!='CREATED'
                or registered.get('id')!=start_id or listed!=[start_id] or record.get('viewState')!='SEALED'
                or record.get('candidateState') is not None):raise VolumeError('EDIT_CONTAINER_START_NOT_ALLOWED')
        if create:
            if registered is not None or listed or record.get('viewState')!='SEALED' or record.get('candidateState') is not None:
                raise VolumeError('EDIT_CONTAINER_CREATE_NOT_ALLOWED')
            registered={'name':name,'state':'PLANNED','id':None};record['editContainer']=registered;write_record(folder,record)
            args=['docker','create','--name',name,'--network','none','--read-only','--cap-drop','ALL',
                  '--security-opt','no-new-privileges','--memory','1g','--memory-swap','1g','--cpus','1','--pids-limit','128',
                  '--user','65534:65534','--workdir','/workspace','--mount','type=bind,src='+str(folder/'work/view')+',dst=/workspace',
                  '--tmpfs','/tmp:rw,nosuid,nodev,noexec,size=64m']
            for key,value in labels.items():args.extend(['--label',key+'='+value])
            args.extend(['glyahh-sandbox-runtime:24.04','sleep','infinity'])
            identifier=command(args).stdout.strip()
            if not re.fullmatch('[0-9a-f]{64}',identifier):raise VolumeError('EDIT_CONTAINER_CREATE_UNCONFIRMED')
            registered.update(id=identifier,state='CREATED');write_record(folder,record)
            return {**expected,'state':'CONTAINER_CREATED','containerId':identifier}
        if registered is None:
            if listed:raise VolumeError('EDIT_CONTAINER_UNREGISTERED')
            absent=command(['docker','inspect',name],allow_failure=True)
            if absent.returncode==0 or 'no such object' not in absent.stderr.lower():raise VolumeError('EDIT_CONTAINER_UNREGISTERED')
            return {**expected,'state':'STOP_CONFIRMED','containerName':name,'containerId':None}
        if not isinstance(registered,dict) or registered.get('name')!=name or registered.get('state') not in ('PLANNED','CREATED','STOPPING','REMOVED'):
            raise VolumeError('EDIT_CONTAINER_IDENTITY_CONFLICT')
        identifier=registered.get('id')
        if identifier is not None and (not isinstance(identifier,str) or not re.fullmatch('[0-9a-f]{64}',identifier)):
            raise VolumeError('EDIT_CONTAINER_IDENTITY_CONFLICT')
        if len(listed)>1 or any(not re.fullmatch('[0-9a-f]{64}',item) for item in listed):raise VolumeError('EDIT_CONTAINER_IDENTITY_CONFLICT')
        if listed:
            if registered['state']=='REMOVED' or identifier is not None and identifier!=listed[0]:raise VolumeError('EDIT_CONTAINER_IDENTITY_CONFLICT')
            item=json.loads(command(['docker','inspect',listed[0]]).stdout)[0]
            config=item.get('Config',{});host=item.get('HostConfig',{});mounts=item.get('Mounts',[])
            if item.get('Id')!=listed[0] or item.get('Name')!='/'+name or any((config.get('Labels') or {}).get(k)!=v for k,v in labels.items()) \
                    or config.get('User')!='65534:65534' or host.get('Privileged') is not False or host.get('ReadonlyRootfs') is not True \
                    or host.get('NetworkMode')!='none' or len(mounts)!=1 or mounts[0].get('Type')!='bind' \
                    or mounts[0].get('Source')!=str(folder/'work/view') or mounts[0].get('Destination')!='/workspace':
                raise VolumeError('EDIT_CONTAINER_IDENTITY_CONFLICT')
            if start_id is not None:
                checks={'IMAGE':config.get('Image')=='glyahh-sandbox-runtime:24.04','COMMAND':config.get('Cmd')==['sleep','infinity'],
                        'WORKDIR':config.get('WorkingDir')=='/workspace','MEMORY':host.get('Memory')==1<<30,
                        'CPU':host.get('NanoCpus')==1_000_000_000,'PIDS':host.get('PidsLimit')==128,'CAPS':host.get('CapDrop')==['ALL'],
                        'PRIVILEGES':any(value in ('no-new-privileges','no-new-privileges:true') for value in host.get('SecurityOpt',[])),
                        'MOUNT':mounts[0].get('RW') is True}
                for key,valid in checks.items():
                    if not valid:raise VolumeError('EDIT_CONTAINER_START_CONFIG_'+key)
                command(['docker','start',start_id])
                try:
                    running=json.loads(command(['docker','inspect',start_id]).stdout)[0]
                    # WSL引擎可能忽略MemorySwap记录；执行资格只认实际内核swap硬限制为0。
                    limits=edit_hard_limits(running)
                except (VolumeError,ValueError,KeyError,TypeError) as error:
                    registered['state']='STOPPING';write_record(folder,record)
                    command(['docker','stop','-t','5',start_id])
                    stopped=json.loads(command(['docker','inspect',start_id]).stdout)[0]
                    if stopped.get('State',{}).get('Running') is not False or stopped.get('State',{}).get('Pid')!=0:
                        raise VolumeError('EDIT_CONTAINER_STOP_UNCONFIRMED') from error
                    raise VolumeError('EDIT_LIMITS_UNENFORCEABLE') from error
                return {**expected,'state':'CONTAINER_RUNNING','containerId':start_id,'limits':limits}
            identifier=listed[0];registered.update(id=identifier,state='STOPPING');write_record(folder,record)
            command(['docker','stop','-t','5',identifier])
            stopped=json.loads(command(['docker','inspect',identifier]).stdout)[0]
            if stopped.get('State',{}).get('Running') is not False or stopped.get('State',{}).get('Pid')!=0:
                raise VolumeError('EDIT_CONTAINER_STOP_UNCONFIRMED')
            command(['docker','rm',identifier])
        if identifier is not None:
            absent=command(['docker','inspect',identifier],allow_failure=True)
            if absent.returncode==0 or 'no such object' not in absent.stderr.lower():raise VolumeError('EDIT_CONTAINER_REMOVE_UNCONFIRMED')
        if command(['docker','ps','-aq','--filter',container_filter(expected)]).stdout.strip():raise VolumeError('EDIT_CONTAINER_REMOVE_UNCONFIRMED')
        absent=command(['docker','inspect',name],allow_failure=True)
        if absent.returncode==0 or 'no such object' not in absent.stderr.lower():raise VolumeError('EDIT_CONTAINER_REMOVE_UNCONFIRMED')
        registered['state']='REMOVED';write_record(folder,record)
        return {**expected,'state':'STOP_CONFIRMED','containerName':name,'containerId':identifier}


def require_new(folder):
    no_links(folder)
    if folder.exists() and any(folder.iterdir()):
        raise VolumeError("UNREGISTERED_RESOURCE")


def match(expected, actual):
    if any(actual.get(key) != value for key, value in expected.items()):
        raise VolumeError("VOLUME_IDENTITY_CONFLICT")
    if actual.get("state") != "READY":
        raise VolumeError("VOLUME_UNRESOLVED")


def command(args, allow_failure=False):
    try:
        result = subprocess.run(args, capture_output=True, text=True, timeout=90)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise VolumeError("COMMAND_UNAVAILABLE_OR_TIMEOUT") from error
    if result.returncode and not allow_failure:
        raise VolumeError("COMMAND_FAILED:" + args[0] + ":" + str(result.returncode))
    return result


def write_record(folder, record):
    path = folder / "record.json"
    no_links(path)
    temporary = folder / "record.pending"
    no_links(temporary)
    with open(temporary, "w", encoding="utf-8") as stream:
        json.dump(record, stream)
        stream.flush()
        os.fsync(stream.fileno())
    os.chmod(temporary, 0o600)
    os.replace(temporary, path)
    descriptor = os.open(folder, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


@contextlib.contextmanager
def locked():
    if sys.platform != "linux" or os.geteuid() != 0:
        raise VolumeError("LINUX_PLATFORM_ROOT_REQUIRED")
    import fcntl
    no_links(ROOT)
    ROOT.mkdir(parents=True, exist_ok=True, mode=0o700)
    for path in (ROOT, ROOT.parent):
        info = path.stat()
        if info.st_uid != 0 or info.st_mode & 0o022:
            raise VolumeError("RESOURCE_ROOT_UNTRUSTED")
    descriptor = os.open(ROOT / ".lock", os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        # ponytail: 卷操作共用文件锁；接业务并验证并发后再按应用细分，先保证创建/释放不会交叉。
        fcntl.flock(descriptor, fcntl.LOCK_EX)
        yield
    finally:
        os.close(descriptor)


def backing(folder, record):
    image = folder / "volume.img"
    no_links(image)
    if not image.is_file() or image.stat().st_size != record["diskBytes"]:
        raise VolumeError("BACKING_SIZE_MISMATCH")
    if image.stat().st_blocks * 512 < record["diskBytes"]:
        raise VolumeError("HOST_SPACE_NOT_RESERVED")
    features = command(["tune2fs", "-l", str(image)]).stdout
    line = next((value for value in features.splitlines() if value.startswith("Filesystem features:")), "")
    if "has_journal" not in line.split():
        raise VolumeError("JOURNAL_NOT_CONFIRMED")
    return image


def mounted(folder, record):
    image = backing(folder, record)
    work = folder / "work"
    no_links(work)
    result = command(["findmnt", "-M", str(work), "-J", "-o", "TARGET,SOURCE,FSTYPE,OPTIONS"], allow_failure=True)
    if result.returncode:
        raise VolumeError("VOLUME_NOT_MOUNTED")
    data = json.loads(result.stdout)["filesystems"]
    if len(data) != 1 or data[0]["target"] != str(work) or data[0]["fstype"] != "ext4":
        raise VolumeError("MOUNT_IDENTITY_MISMATCH")
    entry = data[0]
    if not re.fullmatch(r"/dev/loop[0-9]+", entry["source"]) or not {"rw", "nosuid", "nodev"} <= set(entry["options"].split(",")):
        raise VolumeError("MOUNT_OPTIONS_MISMATCH")
    backing_file = command(["losetup", "--noheadings", "--output", "BACK-FILE", entry["source"]]).stdout.strip()
    if backing_file != str(image):
        raise VolumeError("LOOP_IDENTITY_MISMATCH")
    actual_bytes = int(command(["blockdev", "--getsize64", entry["source"]]).stdout.strip())
    if actual_bytes != record["diskBytes"]:
        raise VolumeError("BLOCK_SIZE_MISMATCH")
    return {**record, "mountPath": str(work), "loopDevice": entry["source"], "actualBytes": actual_bytes, "journal": True, "reservedBytes": image.stat().st_blocks * 512}


def seed_manifest(manifest, expected):
    if not isinstance(manifest, dict) or any(manifest.get(key) != value for key, value in expected.items()):
        raise VolumeError("VOLUME_IDENTITY_CONFLICT")
    files = manifest.get("files")
    if not isinstance(files, list) or len(files) > 100000:
        raise VolumeError("SEED_MANIFEST_INVALID")
    paths = set()
    total = 0
    for item in files:
        if not isinstance(item, dict):
            raise VolumeError("SEED_MANIFEST_INVALID")
        path = item.get("path")
        if not isinstance(path, str) or len(path) > 4096 or "\\" in path or any(ord(c) < 32 for c in path):
            raise VolumeError("SEED_PATH_INVALID")
        parts = path.split("/")
        if any(part in ("", ".", "..") for part in parts) or parts[0] == "lost+found" or path in paths:
            raise VolumeError("SEED_PATH_INVALID")
        paths.add(path)
        size = item.get("size")
        if type(size) is not int or size < 0 or type(item.get("executable")) is not bool or not isinstance(item.get("sha256"), str) or not re.fullmatch(r"[0-9a-f]{64}", item["sha256"]):
            raise VolumeError("SEED_MANIFEST_INVALID")
        total += size
    if total > expected["diskBytes"] or any(str(parent) in paths for path in paths for parent in Path(path).parents if str(parent) != "."):
        raise VolumeError("SEED_MANIFEST_INVALID")
    directories = manifest.get("directories", [])
    if not isinstance(directories, list) or len(directories) > 100000 or any(not isinstance(path, str) for path in directories) or len(set(directories)) != len(directories):
        raise VolumeError("SEED_MANIFEST_INVALID")
    for path in directories:
        if not isinstance(path, str) or len(path) > 4096 or "\\" in path or any(ord(c) < 32 for c in path) or any(part in ("", ".", "..") for part in path.split("/")) or path.split("/")[0] == "lost+found" or path in paths or any(str(parent) in paths for parent in Path(path).parents):
            raise VolumeError("SEED_PATH_INVALID")
    return hashlib.sha256(json.dumps(manifest, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()


@contextlib.contextmanager
def source_file(source, relative):
    # 每级目录通过描述符打开，不允许链接切换到清单外的路径。
    no_links(source)
    descriptor = os.open(source, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        parts = relative.split("/")
        for part in parts[:-1]:
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=descriptor)
            os.close(descriptor)
            descriptor = child
        file_descriptor = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=descriptor)
        with os.fdopen(file_descriptor, "rb") as stream:
            info = os.fstat(stream.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
                raise VolumeError("SEED_FILE_TYPE_INVALID")
            yield stream
    finally:
        os.close(descriptor)


def seed(expected, source, manifest):
    digest = seed_manifest(manifest, expected)
    source = Path(source).absolute()
    folder = resource_folder(expected)
    with locked():
        no_links(folder / "record.json")
        record = json.loads((folder / "record.json").read_text())
        match(expected, record)
        verified = mounted(folder, record)
        if record.get("seedManifestHash"):
            if record["seedManifestHash"] != digest or record.get("seedState") != "SEALED":
                raise VolumeError("SEED_IDENTITY_CONFLICT")
            return verified
        if command(["docker", "ps", "-aq", "--filter", container_filter(expected)]).stdout.strip():
            raise VolumeError("APP_CONTAINERS_REMAIN")
        work = folder / "work"
        if source == work or source.is_relative_to(folder) or folder.is_relative_to(source):
            raise VolumeError("SEED_SOURCE_NOT_ISOLATED")
        no_links(source)
        if not source.is_dir():
            raise VolumeError("SEED_SOURCE_NOT_DIRECTORY")
        if any(entry.name != "lost+found" for entry in work.iterdir()):
            raise VolumeError("SEED_TARGET_NOT_EMPTY")
        actual = set()
        actual_dirs = set()
        for directory, dirs, files in os.walk(source, followlinks=False):
            for name in dirs + files:
                no_links(Path(directory) / name)
            actual.update(str((Path(directory) / name).relative_to(source)).replace("\\", "/") for name in files)
            actual_dirs.update(str((Path(directory) / name).relative_to(source)).replace("\\", "/") for name in dirs)
            if len(actual) > len(manifest["files"]):
                raise VolumeError("SEED_FILE_LIST_CHANGED")
        if actual != {item["path"] for item in manifest["files"]}:
            raise VolumeError("SEED_FILE_LIST_CHANGED")
        if "directories" in manifest and actual_dirs != set(manifest["directories"]):
            raise VolumeError("SEED_DIRECTORY_LIST_CHANGED")
        # 先登记未决状态。失败时保留部分文件供核对，不重试覆盖或假装卷可执行。
        record.update(state="SEEDING", seedState="COPYING", seedManifestHash=digest)
        write_record(folder, record)
        for directory in manifest.get("directories", []):
            target = work / directory
            target.mkdir(parents=True, exist_ok=True)
            os.chmod(target, 0o755)
            os.chown(target, 65534, 65534)
        for item in manifest["files"]:
            target = work / item["path"]
            target.parent.mkdir(parents=True, exist_ok=True)
            with source_file(source, item["path"]) as incoming, open(target, "xb") as outgoing:
                before = os.fstat(incoming.fileno())
                checksum = hashlib.sha256()
                copied = 0
                while chunk := incoming.read(min(1 << 20, item["size"] - copied + 1)):
                    copied += len(chunk)
                    if copied > item["size"]:
                        raise VolumeError("SEED_CONTENT_CHANGED")
                    checksum.update(chunk)
                    outgoing.write(chunk)
                after = os.fstat(incoming.fileno())
                if copied != item["size"] or checksum.hexdigest() != item["sha256"] or (before.st_size, before.st_mtime_ns, before.st_ctime_ns) != (after.st_size, after.st_mtime_ns, after.st_ctime_ns):
                    raise VolumeError("SEED_CONTENT_CHANGED")
                outgoing.flush()
                os.fsync(outgoing.fileno())
            os.chmod(target, 0o755 if item["executable"] else 0o644)
            os.chown(target, 65534, 65534)
            for parent in target.parents:
                if parent == work:
                    break
                os.chmod(parent, 0o755)
                os.chown(parent, 65534, 65534)
        command(["sync", "-f", str(work)])
        record.update(state="READY", seedState="SEALED", seedFiles=len(manifest["files"]))
        write_record(folder, record)
        return {**verified, **record}


def prepare_edit_view(expected, source):
    """平台root在已有容量卷内复制去秘密视图，私有映射不进入模型挂载点。"""
    if expected.get('formatVersion') != 3:
        raise VolumeError('EDIT_IDENTITY_REQUIRED')
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'agent'))
    from backends.secret_free_view import SecretFreeView, ViewError
    folder = resource_folder(expected)
    with locked():
        no_links(folder)
        record_path = folder / 'record.json'
        no_links(record_path)
        record = json.loads(record_path.read_text(encoding='utf-8'))
        match(expected, record)
        verified = mounted(folder, record)
        if record.get('candidateState') is not None:
            raise VolumeError('EDIT_CANDIDATE_FROZEN')
        if command(['docker', 'ps', '-aq', '--filter', container_filter(expected)]).stdout.strip():
            raise VolumeError('APP_CONTAINERS_REMAIN')
        work = folder / 'work'
        if record.get('viewState') is not None or any(p.name != 'lost+found' for p in work.iterdir()):
            raise VolumeError('EDIT_VIEW_TARGET_NOT_EMPTY')
        # 1. 先登记未决阶段，失败不重试覆盖已有内容；父目录不向普通WSL用户开放。
        record['viewState'] = 'PREPARING'
        write_record(folder, record)
        os.chown(work, 0, 0)
        os.chmod(work, 0o700)
        try:
            view = SecretFreeView.create(source, work / 'view', app_id=expected['appId'],
                                         owner_id=expected['ownerId'], run_id=expected['runId'],
                                         eligible=lambda: True, max_bytes=expected['diskBytes'])
            # 2. 只有去秘密子目录交给容器UID；映射保留在root私有同容量卷中。
            for directory, dirs, files in os.walk(view.root, followlinks=False):
                for name in dirs + files:
                    no_links(Path(directory) / name)
                    os.chown(Path(directory) / name, 65534, 65534)
            os.chown(view.root, 65534, 65534)
            if view._inventory(view.root, view.max_files, view.max_bytes, lambda: True) != view._baseline:
                raise VolumeError('EDIT_VIEW_PERMISSION_CHANGED')
            state_hash = view.save_private_state(work / 'private-state.json', eligible=lambda: True)
            if view._inventory(view.source, view.max_files, view.max_bytes, lambda: True) != view._original:
                raise VolumeError('VIEW_SOURCE_CHANGED')
            command(['sync', '-f', str(work)])
            record.update(viewState='SEALED', viewHash=view.view_hash, viewSourceHash=view.source_hash,
                          viewStateHash=state_hash, viewPath=str(view.root))
            write_record(folder, record)
            return {**verified, **record}
        except (ViewError, VolumeError, OSError) as error:
            record['viewState'] = 'FAILED'
            write_record(folder, record)
            if isinstance(error, ViewError):
                raise VolumeError(str(error)) from error
            raise


def edit_file(expected, request):
    """平台结构化文件修改，不执行脚本；修改意图先记入卷日志，失败保留未决状态。"""
    if expected.get('formatVersion') != 3 or not isinstance(request, dict):
        raise VolumeError('EDIT_IDENTITY_REQUIRED')
    op, name = request.get('operation'), request.get('path')
    fields = {'write': {'operation', 'path', 'content'}, 'delete': {'operation', 'path'},
              'edit': {'operation', 'path', 'oldString', 'newString', 'replaceAll'}}
    if not isinstance(op, str) or op not in fields or set(request) != fields[op] or not isinstance(name, str) \
            or not name or '\\' in name or ':' in name or len(name) > 4096:
        raise VolumeError('EDIT_REQUEST_INVALID')
    if op == 'write' and not isinstance(request['content'], str):
        raise VolumeError('EDIT_REQUEST_INVALID')
    if op == 'edit' and (not isinstance(request['oldString'], str) or not request['oldString']
                         or not isinstance(request['newString'], str) or type(request['replaceAll']) is not bool):
        raise VolumeError('EDIT_REQUEST_INVALID')
    try:
        if len(json.dumps(request, ensure_ascii=False).encode('utf-8')) > 1 << 20:
            raise VolumeError('EDIT_REQUEST_LIMIT')
    except UnicodeError as error:
        raise VolumeError('EDIT_REQUEST_INVALID') from error
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'agent'))
    from backends.secret_free_view import SecretFreeView, ViewError
    folder = resource_folder(expected)
    with locked():
        no_links(folder)
        record_path = folder / 'record.json'
        no_links(record_path)
        record = json.loads(record_path.read_text(encoding='utf-8'))
        match(expected, record)
        mounted(folder, record)
        if record.get('viewState') != 'SEALED' or record.get('editState', 'READY') != 'READY':
            raise VolumeError('EDIT_VIEW_UNRESOLVED')
        if record.get('candidateState') is not None:
            raise VolumeError('EDIT_CANDIDATE_FROZEN')
        if command(['docker', 'ps', '-aq', '--filter', container_filter(expected)]).stdout.strip():
            raise VolumeError('APP_CONTAINERS_REMAIN')
        work = folder / 'work'
        try:
            view = SecretFreeView.load_private_state(work / 'private-state.json',
                    state_hash=record.get('authorizedStateHash', record['viewStateHash']), root=work / 'view',
                    app_id=expected['appId'], owner_id=expected['ownerId'], run_id=expected['runId'], eligible=lambda: True)
            if any(value in name for value in view._secrets if len(value) >= 6):
                raise ViewError('VIEW_SECRET_IN_NAME')
            view.require_editable(name, eligible=lambda: True)
            target = view._path(view.root / name)
            if op == 'edit':
                if target.stat().st_size > 16 << 20:
                    raise VolumeError('EDIT_FILE_LIMIT')
                original = target.read_bytes()
                metadata = view._authorized.get(name, view._baseline[0].get(name))
                if metadata is None or hashlib.sha256(original).hexdigest() != metadata[0]:
                    raise ViewError('VIEW_UNAUTHORIZED_CHANGE')
                text = original.decode('utf-8')
                count = text.count(request['oldString'])
                if count == 0 or (count != 1 and not request['replaceAll']):
                    raise VolumeError('EDIT_STRING_NOT_UNIQUE')
                replacements = count if request['replaceAll'] else 1
                size = len(original) + replacements * (len(request['newString'].encode()) - len(request['oldString'].encode()))
                if size > 16 << 20:
                    raise VolumeError('EDIT_FILE_LIMIT')
                content = text.replace(request['oldString'], request['newString'], -1 if request['replaceAll'] else 1)
            elif op == 'write':
                content = request['content']
            # 1. 未决意图先落盘，崩溃或写满不允许下一次操作把部分修改补成授权。
            record['editState'] = 'APPLYING'
            write_record(folder, record)
            if op == 'delete':
                target.unlink()
            else:
                mode = stat.S_IMODE(target.stat().st_mode) if target.exists() else 0o644
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(content.encode('utf-8'))
                os.chown(target, 65534, 65534)
                os.chmod(target, mode)
                for parent in target.parents:
                    if parent == work:
                        break
                    os.chown(parent, 65534, 65534)
            view.record_edit(name, eligible=lambda: True)
            # 2. 映射与授权共同原子保存，日志摘要更新后才返回修改成功。
            state_hash = view.save_private_state(work / 'private-state.json', eligible=lambda: True, replace=True)
            command(['sync', '-f', str(work)])
            record.update(editState='READY', authorizedStateHash=state_hash)
            write_record(folder, record)
            return {**expected, 'state': 'EDITED', 'authorizedStateHash': state_hash,
                    'authorized': {name: view._authorized[name]}}
        except ViewError as error:
            raise VolumeError(str(error)) from error


def seal_edit_candidate(expected, state_hash):
    """仅在容器已消失后封存私有候选和修改摘要，不授予最终应用发布权。"""
    if expected.get('formatVersion') != 3:
        raise VolumeError('EDIT_IDENTITY_REQUIRED')
    if not isinstance(state_hash, str) or not re.fullmatch(r'[0-9a-f]{64}', state_hash):
        raise VolumeError('EDIT_STATE_HASH_INVALID')
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'agent'))
    from backends.secret_free_view import SecretFreeView, ViewError
    folder = resource_folder(expected)
    with locked():
        no_links(folder)
        no_links(folder / 'record.json')
        record = json.loads((folder / 'record.json').read_text(encoding='utf-8'))
        match(expected, record)
        mounted(folder, record)
        if record.get('viewState') != 'SEALED' or record.get('editState', 'READY') != 'READY':
            raise VolumeError('EDIT_VIEW_NOT_READY')
        if record.get('candidateState') is not None:
            raise VolumeError('EDIT_CANDIDATE_FROZEN')
        if state_hash != record.get('authorizedStateHash', record.get('viewStateHash')):
            raise VolumeError('EDIT_STATE_CHANGED')
        # 1. 停止失败或查询失败时不创建候选；先持久登记封存意图，半途失败不覆盖重试。
        if command(['docker', 'ps', '-aq', '--filter', container_filter(expected)]).stdout.strip():
            raise VolumeError('APP_CONTAINERS_REMAIN')
        work = folder / 'work'
        candidate = work / 'candidate'
        manifest = work / 'candidate-manifest.json'
        no_links(candidate)
        no_links(manifest)
        if candidate.exists() or manifest.exists():
            raise VolumeError('EDIT_CANDIDATE_TARGET_EXISTS')
        record.update(candidateState='SEALING', candidatePath=str(candidate), candidateManifestPath=str(manifest))
        write_record(folder, record)
        try:
            view = SecretFreeView.load_private_state(work / 'private-state.json', state_hash=state_hash,
                    root=work / 'view', app_id=expected['appId'], owner_id=expected['ownerId'],
                    run_id=expected['runId'], eligible=lambda: True)
            view.candidate(candidate, eligible=lambda: True)
            inventory = view._inventory(candidate, view.max_files, view.max_bytes, lambda: True)
            changes = [{'path': name, 'before': view._original[0].get(name), 'after': inventory[0].get(name)}
                       for name in sorted(set(view._original[0]) | set(inventory[0]))
                       if view._original[0].get(name) != inventory[0].get(name)]
            if any(item['path'] not in view._authorized for item in changes):
                raise VolumeError('EDIT_CANDIDATE_UNAUTHORIZED')
            if any(value in name for value in view._secrets if len(value) >= 6
                   for name in (*inventory[0], *inventory[1], *view.retained_outputs)):
                raise VolumeError('VIEW_SECRET_IN_NAME')
            candidate_hash = view._hash(inventory)
            payload = {**expected, 'schemaVersion': 1, 'authorizedStateHash': state_hash,
                       'viewSourceHash': view.source_hash, 'candidateHash': candidate_hash,
                       'files': inventory[0], 'directories': sorted(inventory[1]), 'changes': changes,
                       'authorized': view._authorized, 'retainedOutputs': view.retained_outputs}
            raw = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()
            if len(raw) > 32 << 20:
                raise VolumeError('EDIT_CANDIDATE_MANIFEST_LIMIT')
            # 2. 私有清单及候选先落盘并sync，确认版本未变化后才保存SEALED摘要。
            with manifest.open('xb') as stream:
                os.chmod(manifest, 0o600)
                stream.write(raw)
                stream.flush()
                os.fsync(stream.fileno())
            command(['sync', '-f', str(work)])
            if view._inventory(view.source, view.max_files, view.max_bytes, lambda: True) != view._original:
                raise VolumeError('VIEW_SOURCE_CHANGED')
            if view._inventory(candidate, view.max_files, view.max_bytes, lambda: True) != inventory:
                raise VolumeError('EDIT_CANDIDATE_CHANGED')
            sealed_view_hash = view._hash(view._inventory(view.root, view.max_files, view.max_bytes, lambda: True))
            if command(['docker', 'ps', '-aq', '--filter', container_filter(expected)]).stdout.strip():
                raise VolumeError('APP_CONTAINERS_REMAIN')
            record.update(candidateState='SEALED', candidateHash=candidate_hash,
                          candidateManifestHash=hashlib.sha256(raw).hexdigest(), candidateStateHash=state_hash,
                          candidateViewHash=sealed_view_hash)
            write_record(folder, record)
            return {**expected, 'state': 'CANDIDATE_SEALED', 'candidatePath': str(candidate),
                    'candidateManifestPath': str(manifest), 'candidateHash': candidate_hash,
                    'candidateManifestHash': record['candidateManifestHash'], 'authorizedStateHash': state_hash}
        except ViewError as error:
            raise VolumeError(str(error)) from error


def read_edit_candidate(expected, state_hash, candidate_hash, manifest_hash, part, name, offset, length):
    """平台私有只读分块通道；不接受任意宿主路径，不供模型工具或公共API调用。"""
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'agent'))
    from backends.secret_free_view import SecretFreeView, ViewError
    if expected.get('formatVersion') != 3 or any(not isinstance(v, str) or not re.fullmatch('[0-9a-f]{64}', v)
            for v in (state_hash, candidate_hash, manifest_hash)):
        raise VolumeError('EDIT_READ_IDENTITY_INVALID')
    if part not in ('manifest', 'private-state', 'inventory', 'source-inventory', 'view-inventory', 'file', 'view-file', 'source-file', 'output-file') or type(offset) is not int or offset < 0 \
            or type(length) is not int or not 1 <= length <= 256 << 10:
        raise VolumeError('EDIT_READ_REQUEST_INVALID')
    if part in ('manifest', 'private-state', 'inventory', 'source-inventory', 'view-inventory') and name is not None:
        raise VolumeError('EDIT_READ_REQUEST_INVALID')
    folder = resource_folder(expected)
    with locked():
        no_links(folder / 'record.json')
        record = json.loads((folder / 'record.json').read_bytes())
        match(expected, record)
        mounted(folder, record)
        if record.get('candidateState') != 'SEALED' or (record.get('candidateStateHash'), record.get('candidateHash'),
                record.get('candidateManifestHash')) != (state_hash, candidate_hash, manifest_hash):
            raise VolumeError('EDIT_CANDIDATE_NOT_SEALED')
        if command(['docker', 'ps', '-aq', '--filter', container_filter(expected)]).stdout.strip():
            raise VolumeError('APP_CONTAINERS_REMAIN')
        work = folder / 'work'

        def private_bytes(path, digest):
            no_links(path)
            attributes = path.stat()
            if not stat.S_ISREG(attributes.st_mode) or attributes.st_nlink != 1 or attributes.st_uid != 0 \
                    or stat.S_IMODE(attributes.st_mode) != 0o600 or attributes.st_size > 32 << 20:
                raise VolumeError('EDIT_READ_PRIVATE_FILE_INVALID')
            with path.open('rb') as stream:
                raw = stream.read((32 << 20) + 1)
            if len(raw) > 32 << 20 or hashlib.sha256(raw).hexdigest() != digest:
                raise VolumeError('EDIT_READ_PRIVATE_HASH_CHANGED')
            return raw

        # 1. 身份与已封存摘要必须匹配；清单和私有状态绝不按客户端路径寻找。
        manifest_raw = private_bytes(work / 'candidate-manifest.json', manifest_hash)
        state_raw = private_bytes(work / 'private-state.json', state_hash)
        manifest, state = json.loads(manifest_raw), json.loads(state_raw)
        if any(manifest.get(key) != value for key, value in expected.items()) or manifest.get('candidateHash') != candidate_hash \
                or manifest.get('authorizedStateHash') != state_hash or any(state.get(key) != expected[key]
                for key in ('appId', 'ownerId', 'runId')) or state.get('root') != str(work / 'view'):
            raise VolumeError('EDIT_READ_IDENTITY_INVALID')
        if part in ('inventory', 'source-inventory', 'view-inventory'):
            # 实际遍历目录和文件，不从封存清单反填；每块重新核对，篡改不能继续读。
            try:
                root = work / 'candidate' if part == 'inventory' else work / 'view' if part == 'view-inventory' else Path(state['source'])
                actual = SecretFreeView._inventory(root, state['maxFiles'], state['maxBytes'], lambda: True)
            except ViewError as error:
                raise VolumeError('EDIT_READ_CANDIDATE_CHANGED') from error
            raw = json.dumps({'files': actual[0], 'directories': sorted(actual[1])},
                             sort_keys=True, separators=(',', ':')).encode()
            digest = hashlib.sha256(raw).hexdigest()
            expected_digest = candidate_hash if part == 'inventory' else state['sourceHash'] if part == 'source-inventory' else record.get('candidateViewHash')
            if len(raw) > 32 << 20 or digest != expected_digest:
                raise VolumeError('EDIT_READ_CANDIDATE_CHANGED')
            if offset > len(raw):
                raise VolumeError('EDIT_READ_RANGE_INVALID')
            data, size = raw[offset:offset + length], len(raw)
        elif part in ('manifest', 'private-state'):
            raw = manifest_raw if part == 'manifest' else state_raw
            if offset > len(raw):
                raise VolumeError('EDIT_READ_RANGE_INVALID')
            data, size, digest = raw[offset:offset + length], len(raw), manifest_hash if part == 'manifest' else state_hash
        else:
            catalog = manifest['files'] if part == 'file' else state['original'][0] if part == 'source-file' else manifest['retainedOutputs'] if part == 'output-file' else state['authorized']
            if not isinstance(name, str) or not name or '\\' in name or name.startswith('/') \
                    or any(p in ('', '.', '..') for p in name.split('/')) or name not in catalog or catalog[name] is None:
                raise VolumeError('EDIT_READ_PATH_INVALID')
            root = work / 'candidate' if part == 'file' else Path(state['source']) if part == 'source-file' else work / 'view'
            target = root / name
            no_links(target)
            before = target.stat()
            metadata = catalog[name]
            if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1 or before.st_size != metadata[1] \
                    or stat.S_IMODE(before.st_mode) != metadata[2] or offset > before.st_size:
                raise VolumeError('EDIT_READ_FILE_CHANGED')
            # 2. 返回有界原始字节；Java必须独立计算完整摘要，分块成功不代表文件验证通过。
            with target.open('rb') as stream:
                stream.seek(offset)
                data = stream.read(min(length, before.st_size - offset))
            after = target.stat()
            if (before.st_ino, before.st_size, before.st_mtime_ns, before.st_ctime_ns) != \
                    (after.st_ino, after.st_size, after.st_mtime_ns, after.st_ctime_ns):
                raise VolumeError('EDIT_READ_FILE_CHANGED')
            size, digest = before.st_size, metadata[0]
        return {**expected, 'state': 'PRIVATE_CHUNK', 'part': part, 'path': name, 'offset': offset,
                'size': size, 'sha256': digest, 'data': base64.b64encode(data).decode('ascii'),
                'candidateHash': candidate_hash, 'candidateManifestHash': manifest_hash, 'authorizedStateHash': state_hash}


def apply(action, expected):
    if action not in ("create", "inspect", "release", "suspend", "resume", "recover"):
        raise VolumeError("ACTION_INVALID")
    folder = resource_folder(expected)
    with locked():
        no_links(folder)
        record_path = folder / "record.json"
        no_links(record_path)
        record = json.loads(record_path.read_text()) if record_path.is_file() else None
        if action == "create":
            if record is not None:
                match(expected, record)
                return mounted(folder, record)
            require_new(folder)
            folder.mkdir(exist_ok=True, mode=0o700)
            record = {**expected, "state": "PREPARING"}
            write_record(folder, record)
            if shutil.disk_usage(ROOT).free < expected["diskBytes"] + HEADROOM_BYTES:
                raise VolumeError("HOST_SPACE_INSUFFICIENT")
            image = folder / "volume.img"
            with open(image, "xb") as stream:
                stream.truncate(expected["diskBytes"])
                os.posix_fallocate(stream.fileno(), 0, expected["diskBytes"])
                os.fsync(stream.fileno())
            os.chmod(image, 0o600)
            # 镜像总块数固定；文件系统元数据也计入额度，实际可写容量略小于声明值。
            command(["mkfs.ext4", "-q", "-F", "-m", "0", "-O", "has_journal", "-E", "nodiscard,lazy_itable_init=0,lazy_journal_init=0", str(image)])
            work = folder / "work"
            work.mkdir(mode=0o700)
            command(["mount", "-t", "ext4", "-o", "loop,rw,nosuid,nodev", str(image), str(work)])
            os.chown(work, 65534, 65534)
            os.chmod(work, 0o700)
            verified = mounted(folder, record)
            record["state"] = "READY"
            write_record(folder, record)
            return {**verified, "state": "READY"}
        if record is None and action == 'release' and expected.get('formatVersion') == 3 and not folder.exists():
            # 创建前已登记意图但尚未分配时，仍独立核对容器、挂载和loop设备的实际不存在。
            if command(['docker', 'ps', '-aq', '--filter', container_filter(expected)]).stdout.strip():
                raise VolumeError('APP_CONTAINERS_REMAIN')
            found = command(['findmnt', '-M', str(folder / 'work')], allow_failure=True)
            if found.returncode != 1:
                raise VolumeError('MOUNT_STATE_UNKNOWN')
            if command(['losetup', '-j', str(folder / 'volume.img')]).stdout.strip():
                raise VolumeError('LOOP_STILL_ATTACHED')
            return {**expected, 'state': 'RELEASED', 'verifiedAbsent': True}
        if record is None:
            raise VolumeError("VOLUME_NOT_REGISTERED")
        if any(record.get(key) != value for key, value in expected.items()):
            raise VolumeError("VOLUME_IDENTITY_CONFLICT")
        if action == "inspect":
            match(expected, record)
            return mounted(folder, record)
        # 暂停、恢复和释放前先确认同应用容器已结束，不使用 lazy/force 卸载。
        containers = command(["docker", "ps", "-aq", "--filter", container_filter(expected)])
        if containers.stdout.strip():
            raise VolumeError("APP_CONTAINERS_REMAIN")
        if action == "recover" and record["state"] == "READY":
            if record.get("seedState") != "SEALED":
                raise VolumeError("SEED_NOT_SEALED")
            found = command(["findmnt", "-M", str(folder / "work")], allow_failure=True)
            if found.returncode == 0:
                return mounted(folder, record)
            if found.returncode != 1:
                raise VolumeError("MOUNT_STATE_UNKNOWN")
            record["state"] = "SUSPENDED"
            write_record(folder, record)
        if action in ("resume", "recover"):
            if record["state"] != "SUSPENDED":
                raise VolumeError("VOLUME_NOT_SUSPENDED")
            image = backing(folder, record)
            if command(["losetup", "-j", str(image)]).stdout.strip():
                raise VolumeError("LOOP_STILL_ATTACHED")
            record["state"] = "RESUMING"
            write_record(folder, record)
            checked = command(["e2fsck", "-p", str(image)], allow_failure=True)
            if checked.returncode not in (0, 1):
                raise VolumeError("FILESYSTEM_CHECK_FAILED:" + str(checked.returncode))
            command(["mount", "-t", "ext4", "-o", "loop,rw,nosuid,nodev", str(image), str(folder / "work")])
            verified = mounted(folder, record)
            record.update(state="READY", fsCheckExitCode=checked.returncode)
            write_record(folder, record)
            return {**verified, **record}
        if action == "suspend":
            match(expected, record)
            mounted(folder, record)
            record["state"] = "SUSPENDING"
            write_record(folder, record)
            command(["umount", str(folder / "work")])
            if command(["losetup", "-j", str(folder / "volume.img")]).stdout.strip():
                raise VolumeError("LOOP_STILL_ATTACHED")
            record["state"] = "SUSPENDED"
            write_record(folder, record)
            return record
        work = folder / "work"
        no_links(work)
        found = command(["findmnt", "-M", str(work)], allow_failure=True)
        if found.returncode == 0:
            mounted(folder, record)
            record["state"] = "RELEASING"
            write_record(folder, record)
            command(["umount", str(work)])
        elif found.returncode != 1:
            raise VolumeError("MOUNT_STATE_UNKNOWN")
        image = folder / "volume.img"
        no_links(image)
        if command(["losetup", "-j", str(image)]).stdout.strip():
            raise VolumeError("LOOP_STILL_ATTACHED")
        no_links(folder)
        shutil.rmtree(folder)
        return {**expected, "state": "RELEASED"}


def candidate_review(expected, state_hash, candidate_hash, manifest_hash):
    """仅已封存候选的安全全文投影；秘密映射留在平台，超限拒绝。"""
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'agent'))
    from backends.secret_free_view import SecretFreeView
    from backends.workspace_secrets import WorkspaceSecrets
    folder = resource_folder(expected)
    with locked():
        record = json.loads((folder / 'record.json').read_bytes())
        match(expected, record)
        mounted(folder, record)
        if record.get('candidateState') != 'SEALED' or (record.get('candidateStateHash'), record.get('candidateHash'), record.get('candidateManifestHash')) != (state_hash, candidate_hash, manifest_hash):
            raise VolumeError('EDIT_CANDIDATE_NOT_SEALED')
        if command(['docker', 'ps', '-aq', '--filter', container_filter(expected)]).stdout.strip():
            raise VolumeError('APP_CONTAINERS_REMAIN')
        work = folder / 'work'
        view = SecretFreeView.load_private_state(work / 'private-state.json', state_hash=state_hash,
                    root=work / 'view', app_id=expected['appId'], owner_id=expected['ownerId'], run_id=expected['runId'], eligible=lambda: True)
        no_links(work / 'candidate-manifest.json')
        raw = (work / 'candidate-manifest.json').read_bytes()
        if hashlib.sha256(raw).hexdigest() != manifest_hash:
            raise VolumeError('EDIT_READ_PRIVATE_HASH_CHANGED')
        manifest = json.loads(raw)
        if view._hash(view._inventory(work / 'candidate', view.max_files, view.max_bytes, lambda: True)) != candidate_hash:
            raise VolumeError('EDIT_READ_CANDIDATE_CHANGED')
        registry = WorkspaceSecrets(view.root, view._protected)
        for value in view._secrets:
            registry._remember(value)
        changes = []
        for change in manifest['changes']:
            path = change['path']
            result = dict(change)
            for side, root in (('before', view.source), ('after', work / 'candidate')):
                stamp = change[side]
                if stamp is None:
                    result[side + 'Text'] = None
                    continue
                target = root / path
                no_links(target)
                if stamp[1] > 128 << 10:
                    raise VolumeError('REVIEW_INPUT_LIMIT')
                content = target.read_bytes()
                if len(content) != stamp[1] or hashlib.sha256(content).hexdigest() != stamp[0]:
                    raise VolumeError('EDIT_READ_FILE_CHANGED')
                try:
                    result[side + 'Text'] = registry.mask(content.decode('utf-8'))
                except UnicodeError:
                    raise VolumeError('REVIEW_BINARY_UNSUPPORTED')
            changes.append(result)
        body = {'candidateHash': candidate_hash, 'manifestHash': manifest_hash, 'authorizedHash': state_hash,
                'sourceHash': expected['contentHash'], 'authorized': sorted(manifest['authorized']), 'changes': changes}
        if len(json.dumps(body, ensure_ascii=False).encode()) > 256 << 10:
            raise VolumeError('REVIEW_INPUT_LIMIT')
        return {**expected, 'state': 'REVIEW_READY', 'body': body}


def inspect_edit_action(expected, request):
    """平台从实际视图重算动作，审查不写文件、不授予执行资格。"""
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'agent'))
    from backends.secret_free_view import SecretFreeView
    from backends.import_action import verify_action, action_hash, action_inspection
    folder = resource_folder(expected)
    with locked():
        no_links(folder / 'record.json')
        record = json.loads((folder / 'record.json').read_bytes())
        match(expected, record)
        mounted(folder, record)
        if record.get('viewState') != 'SEALED' or record.get('editState', 'READY') != 'READY' or record.get('candidateState') is not None:
            raise VolumeError('EDIT_VIEW_UNRESOLVED')
        state_hash = record.get('authorizedStateHash', record['viewStateHash'])
        view = SecretFreeView.load_private_state(folder / 'work/private-state.json', state_hash=state_hash,
                    root=folder / 'work/view', app_id=expected['appId'], owner_id=expected['ownerId'],
                    run_id=expected['runId'], eligible=lambda: True)
        action = verify_action(view, state_hash, request, eligible=lambda: True)
        return {**expected, 'state': 'ACTION_VERIFIED', 'actionHash': action_hash(action), **action_inspection(action)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("create", "inspect", "release", "suspend", "resume", "recover", "seed", "edit-view", "file-edit", "edit-candidate", "candidate-read", "edit-container-create", "edit-container-stop", "edit-container-start", "action-inspect", "candidate-review"))
    parser.add_argument('--request-file')
    parser.add_argument('--container-id')
    parser.add_argument('--candidate-hash')
    parser.add_argument('--manifest-hash')
    parser.add_argument('--part')
    parser.add_argument('--file-path')
    parser.add_argument('--offset', type=int, default=0)
    parser.add_argument('--length', type=int, default=256 << 10)
    parser.add_argument('--state-hash')
    parser.add_argument("--app-id", required=True)
    parser.add_argument("--content-hash", required=True)
    parser.add_argument("--bytes", type=int, required=True)
    parser.add_argument("--owner-id")
    parser.add_argument("--run-id")
    parser.add_argument("--edit-id")
    parser.add_argument("--source")
    parser.add_argument("--manifest")
    args = parser.parse_args()
    try:
        expected = identity(args.app_id, args.content_hash, args.bytes,
                            owner_id=args.owner_id, run_id=args.run_id, edit_id=args.edit_id)
        if args.action == 'candidate-review':
            result = candidate_review(expected, args.state_hash, args.candidate_hash, args.manifest_hash)
        elif args.action == 'action-inspect':
            from_path = Path(args.request_file)
            no_links(from_path)
            if from_path.stat().st_size > 1 << 20:
                raise VolumeError('EDIT_REQUEST_LIMIT')
            result = inspect_edit_action(expected, json.loads(from_path.read_bytes()))
        elif args.action in ('edit-container-create','edit-container-stop','edit-container-start'):
            if args.action=='edit-container-start' and args.container_id is None:raise VolumeError('EDIT_CONTAINER_IDENTITY_REQUIRED')
            result=edit_container(expected,args.action=='edit-container-create',args.container_id if args.action=='edit-container-start' else None)
        elif args.action == 'candidate-read':
            result = read_edit_candidate(expected, args.state_hash, args.candidate_hash, args.manifest_hash,
                                         args.part, args.file_path, args.offset, args.length)
        elif args.action == 'edit-candidate':
            result = seal_edit_candidate(expected, args.state_hash)
        elif args.action == 'file-edit':
            raw = sys.stdin.buffer.read((1 << 20) + 1)
            if len(raw) > 1 << 20:
                raise VolumeError('EDIT_REQUEST_LIMIT')
            result = edit_file(expected, json.loads(raw))
        elif args.action == 'edit-view':
            if not args.source:
                raise VolumeError('EDIT_SOURCE_REQUIRED')
            result = prepare_edit_view(expected, args.source)
        elif args.action == "seed":
            if not args.source or not args.manifest:
                raise VolumeError("SEED_ARGUMENTS_REQUIRED")
            path = Path(args.manifest)
            no_links(path)
            if path.stat().st_size > 32 << 20:
                raise VolumeError("SEED_MANIFEST_TOO_LARGE")
            result = seed(expected, args.source, json.loads(path.read_text(encoding="utf-8")))
        else:
            result = apply(args.action, expected)
        print(json.dumps(result))
    except (VolumeError, OSError, ValueError, KeyError, RecursionError) as error:
        reason = str(error) if isinstance(error, VolumeError) else type(error).__name__
        if isinstance(error, OSError) and error.errno == errno.ENOSPC:
            reason = "DISK_QUOTA" if args.action in ('seed', 'edit-view', 'file-edit', 'edit-candidate') else "HOST_SPACE_INSUFFICIENT"
        print(json.dumps({"state": "FAILED", "reason": reason, "phase": args.action.upper()}))
        raise SystemExit(1)


if __name__ == "__main__":
    main()
