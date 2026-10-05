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

ROOT = Path("/var/lib/glyahh/import-disks")
HEADROOM_BYTES = 256 << 20


class VolumeError(Exception):
    pass


def identity(app_id, content_hash, disk_bytes):
    if not isinstance(app_id, str) or not re.fullmatch(r"[1-9][0-9]{0,18}", app_id) or int(app_id) > 9223372036854775807:
        raise VolumeError("APP_ID_INVALID")
    if not isinstance(content_hash, str) or not re.fullmatch(r"[0-9a-f]{64}", content_hash):
        raise VolumeError("CONTENT_HASH_INVALID")
    if not isinstance(disk_bytes, int) or not 16 << 20 <= disk_bytes <= 8 << 30 or disk_bytes % 4096:
        raise VolumeError("DISK_LIMIT_INVALID")
    return {"appId": app_id, "contentHash": content_hash, "diskBytes": disk_bytes, "formatVersion": 2}


def no_links(path):
    for entry in (path, *path.parents):
        if entry.is_symlink():
            raise VolumeError("RESOURCE_PATH_LINK")


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
    folder = ROOT / expected["appId"]
    with locked():
        no_links(folder / "record.json")
        record = json.loads((folder / "record.json").read_text())
        match(expected, record)
        verified = mounted(folder, record)
        if record.get("seedManifestHash"):
            if record["seedManifestHash"] != digest or record.get("seedState") != "SEALED":
                raise VolumeError("SEED_IDENTITY_CONFLICT")
            return verified
        if command(["docker", "ps", "-aq", "--filter", "label=glyahh.import.app=" + expected["appId"]]).stdout.strip():
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


def apply(action, expected):
    if action not in ("create", "inspect", "release", "suspend", "resume", "recover"):
        raise VolumeError("ACTION_INVALID")
    folder = ROOT / expected["appId"]
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
        if record is None:
            raise VolumeError("VOLUME_NOT_REGISTERED")
        if any(record.get(key) != value for key, value in expected.items()):
            raise VolumeError("VOLUME_IDENTITY_CONFLICT")
        if action == "inspect":
            match(expected, record)
            return mounted(folder, record)
        # 暂停、恢复和释放前先确认同应用容器已结束，不使用 lazy/force 卸载。
        containers = command(["docker", "ps", "-aq", "--filter", "label=glyahh.import.app=" + expected["appId"]])
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


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("create", "inspect", "release", "suspend", "resume", "recover", "seed"))
    parser.add_argument("--app-id", required=True)
    parser.add_argument("--content-hash", required=True)
    parser.add_argument("--bytes", type=int, required=True)
    parser.add_argument("--source")
    parser.add_argument("--manifest")
    args = parser.parse_args()
    try:
        expected = identity(args.app_id, args.content_hash, args.bytes)
        if args.action == "seed":
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
    except (VolumeError, OSError, ValueError, KeyError) as error:
        reason = str(error) if isinstance(error, VolumeError) else type(error).__name__
        if isinstance(error, OSError) and error.errno == errno.ENOSPC:
            reason = "DISK_QUOTA" if args.action == "seed" else "HOST_SPACE_INSUFFICIENT"
        print(json.dumps({"state": "FAILED", "reason": reason, "phase": args.action.upper()}))
        raise SystemExit(1)


if __name__ == "__main__":
    main()
