"""专用卷真实边界验收，不走业务 API，仅运行受控夹具。"""
import hashlib
import json
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "scripts/import_disk_volume.py"
PREFIX = ["wsl.exe", "-d", "Ubuntu", "-u", "root", "--exec"]
IMAGE = "glyahh-sandbox-runtime:24.04"
LIMIT = 64 << 20


def main():
    evidence = ROOT / "temp" / ("import-disk-volume-" + str(time.time_ns()))
    evidence.mkdir(parents=True)
    ids = [str(9000000000000000000 + time.time_ns() % 1000000000000 + index) for index in range(2)]
    content_hash = hashlib.sha256(("controlled-empty-volume-" + evidence.name).encode()).hexdigest()
    report = {"status": "RUNNING", "firstRequirement": "NOT_COMPLETE", "scope": "UNCONNECTED_DISK_CORE", "appIds": ids,
              "contentHash": content_hash, "diskBytes": LIMIT, "helperSha256": hashlib.sha256(HELPER.read_bytes()).hexdigest(), "steps": [], "checks": []}
    owned = set()
    names = []
    seed_fixture = None
    seed_owned = False

    def run(args, allow_failure=False):
        try:
            result = subprocess.run(PREFIX + args, capture_output=True, timeout=120)
        except subprocess.TimeoutExpired as error:
            result = subprocess.CompletedProcess(PREFIX + args, 124, error.stdout or b"", b"COMMAND_TIMEOUT")
        except OSError:
            result = subprocess.CompletedProcess(PREFIX + args, 126, b"", b"COMMAND_UNAVAILABLE")
        report["steps"].append({"argv": PREFIX + args, "exitCode": result.returncode,
                                "stdout": result.stdout.decode("utf-8", "replace")[:3000], "stderr": result.stderr.decode("utf-8", "replace")[:1000]})
        if result.returncode and not allow_failure:
            raise RuntimeError("STEP_FAILED:" + args[0] + ":" + str(result.returncode))
        return result

    helper_path = "/mnt/" + str(HELPER)[0].lower() + str(HELPER)[2:].replace("\\", "/")

    def volume(action, app, digest=content_hash, size=LIMIT, allow_failure=False):
        result = run(["python3", helper_path, action, "--app-id", app, "--content-hash", digest, "--bytes", str(size)], allow_failure)
        return result.returncode, json.loads(result.stdout)

    def check(name, ok, detail=None):
        report["checks"].append({"name": name, "status": "PASS" if ok else "FAIL", "detail": detail})
        if not ok:
            raise AssertionError(name)

    def container(app, path, code):
        name = "glyahh-imp-disk-test-" + app
        names.append(name)
        return run(["docker", "run", "--rm", "--name", name, "--label", "glyahh.import.app=" + app,
                    "--network", "none", "--read-only", "--user", "65534:65534", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                    "--memory", "128m", "--cpus", "0.5", "--pids-limit", "16", "--log-driver", "none", "--tmpfs", "/tmp:size=1m",
                    "-v", path + ":/work:rw", "--entrypoint", "python3", IMAGE, "-c", code])

    try:
        mounts = []
        for app in ids:
            absent = run(["python3", "-c", "import pathlib,sys;sys.exit(1 if pathlib.Path(sys.argv[1]).exists() else 0)", "/var/lib/glyahh/import-disks/" + app], True)
            if absent.returncode:
                raise RuntimeError("FIXTURE_NAMESPACE_ALREADY_EXISTS")
            owned.add(app)
            _, info = volume("create", app)
            mounts.append(info["mountPath"])
            check("固定块设备容量且挂载身份核对", info["state"] == "READY" and info["actualBytes"] == LIMIT, info)
            check("宿主实际空间预留及 journal 核对", info["journal"] is True and info["reservedBytes"] >= LIMIT, info)
        seed_fixture = "/tmp/glyahh-import-seed-fixture-" + ids[0]
        run(["python3", "-c", "import pathlib,sys;pathlib.Path(sys.argv[1]).mkdir(mode=0o700)", seed_fixture])
        seed_owned = True
        fixture = """import pathlib,sys,json,hashlib
root=pathlib.Path(sys.argv[1])
source=root/'sealed';(source/'src').mkdir(parents=True)
data=b'frozen-binary\\x00\\xff';(source/'src/data.bin').write_bytes(data)
manifest={'appId':sys.argv[2],'contentHash':sys.argv[3],'diskBytes':int(sys.argv[4]),'formatVersion':2,
'files':[{'path':'src/data.bin','size':len(data),'sha256':hashlib.sha256(data).hexdigest(),'executable':False}]}
(root/'manifest.json').write_text(json.dumps(manifest))
"""
        run(["python3", "-c", fixture, seed_fixture, ids[0], content_hash, str(LIMIT)])
        seed_args = ["python3", helper_path, "seed", "--app-id", ids[0], "--content-hash", content_hash, "--bytes", str(LIMIT),
                     "--source", seed_fixture + "/sealed", "--manifest", seed_fixture + "/manifest.json"]
        seeded = json.loads(run(seed_args).stdout)
        check("冻结清单复制完成后才登记SEALED", seeded["seedState"] == "SEALED" and seeded["seedFiles"] == 1)
        copied = container(ids[0], mounts[0], "from pathlib import Path; p=Path('/work/src/data.bin'); assert p.read_bytes()==b'frozen-binary\\x00\\xff'; p.write_bytes(b'installed-change'); print('seed-readable-writable')")
        check("非root容器可读取完整二进制并写执行产物", copied.stdout.strip() == b"seed-readable-writable")
        reused_seed = json.loads(run(seed_args).stdout)
        check("重复种子请求保持原清单身份", reused_seed["seedManifestHash"] == seeded["seedManifestHash"])
        preserved_seed = container(ids[0], mounts[0], "from pathlib import Path; assert Path('/work/src/data.bin').read_bytes()==b'installed-change'; print('not-overwritten')")
        check("重复复制请求不覆盖已安装变化", preserved_seed.stdout.strip() == b"not-overwritten")
        code = """import errno,json,os
from pathlib import Path
root=Path('/work')
(root/'installed.txt').write_text('fixture-install')
written=0
error=None
try:
    with open(root/'filler','wb',buffering=0) as output:
        while written < 100*1024*1024:
            written += output.write(b'X'*(1024*1024))
except OSError as failure:
    error=failure.errno
size=(root/'filler').stat().st_size
print(json.dumps({'errno':error,'written':written,'size':size,'artifact':(root/'installed.txt').read_text()}))
assert error == errno.ENOSPC and size < 64*1024*1024
"""
        outcome = json.loads(container(ids[0], mounts[0], code).stdout)
        check("容器内累计写入被文件系统拒绝而非事后统计", outcome["errno"] == 28 and outcome["size"] < LIMIT, outcome)
        check("超限后既有安装产物完整", outcome["artifact"] == "fixture-install")
        _, reused = volume("create", ids[0])
        check("重复请求只复用同身份受限卷", reused["mountPath"] == mounts[0] and reused["actualBytes"] == LIMIT)
        read = container(ids[0], mounts[0], "from pathlib import Path; p=Path('/work'); assert (p/'installed.txt').read_text()=='fixture-install'; (p/'filler').unlink(); print('artifact-persisted')")
        check("换容器后产物仍保留", read.stdout.strip() == b"artifact-persisted")
        _, suspended = volume("suspend", ids[0])
        check("暂停卷确实卸载并解绑loop且保留身份", suspended["state"] == "SUSPENDED")
        exit_code, not_ready = volume("inspect", ids[0], allow_failure=True)
        check("未挂载卷不冒充可用", exit_code == 1 and not_ready["reason"] == "VOLUME_UNRESOLVED")
        _, resumed = volume("resume", ids[0])
        check("恢复前校验文件系统且限额未变", resumed["state"] == "READY" and resumed["actualBytes"] == LIMIT and resumed["fsCheckExitCode"] in (0, 1), resumed)
        preserved = container(ids[0], mounts[0], "from pathlib import Path; assert Path('/work/installed.txt').read_text()=='fixture-install'; print('remount-persisted')")
        check("卸载重挂载后安装产物仍完整", preserved.stdout.strip() == b"remount-persisted")
        run(["umount", mounts[0]])
        exit_code, missing_mount = volume("inspect", ids[0], allow_failure=True)
        check("登记仍READY但实际挂载丢失时明确拒绝", exit_code == 1 and missing_mount["reason"] == "VOLUME_NOT_MOUNTED")
        _, recovered = volume("recover", ids[0])
        check("冻结卷挂载丢失后经文件系统校验恢复原容量", recovered["state"] == "READY" and recovered["actualBytes"] == LIMIT and recovered["fsCheckExitCode"] in (0, 1))
        recovered_file = container(ids[0], mounts[0], "from pathlib import Path; assert Path('/work/installed.txt').read_text()=='fixture-install'; print('recovered-original')")
        check("恢复没有重格式化或覆盖既有安装产物", recovered_file.stdout.strip() == b"recovered-original")
        other = container(ids[1], mounts[1], "from pathlib import Path; p=Path('/work'); assert not (p/'installed.txt').exists(); (p/'other.txt').write_text('other-app'); print('isolated')")
        check("另一应用卷不受写满影响且不可见首应用文件", other.stdout.strip() == b"isolated")
        exit_code, conflict = volume("create", ids[0], "b" * 64, allow_failure=True)
        check("内容身份变化不覆盖旧产物", exit_code == 1 and conflict["reason"] == "VOLUME_IDENTITY_CONFLICT")
        name = "glyahh-imp-disk-test-held-" + ids[0]
        names.append(name)
        run(["docker", "create", "--name", name, "--label", "glyahh.import.app=" + ids[0], "--network", "none", "--read-only", "--cap-drop", "ALL", IMAGE, "true"])
        exit_code, blocked = volume("release", ids[0], allow_failure=True)
        check("应用容器未清理时拒绝释放卷", exit_code == 1 and blocked["reason"] == "APP_CONTAINERS_REMAIN")
        run(["docker", "rm", name])
        report["status"] = "PASS"
    except Exception as error:
        report["status"] = "FAIL"
        report["error"] = type(error).__name__ + ":" + str(error)[:300]
    finally:
        for name in names:
            # 只清理本脚本分配的容器名，不按全局标签删除其他资源。
            result = run(["docker", "ps", "-aq", "--filter", "name=^/" + name + "$"], True)
            if result.returncode:
                report["status"] = "FAIL"
            elif result.stdout.strip():
                if run(["docker", "rm", "-f", name], True).returncode:
                    report["status"] = "FAIL"
        for app in owned:
            try:
                _, released = volume("release", app)
                absent = run(["python3", "-c", "import pathlib,sys;sys.exit(1 if pathlib.Path(sys.argv[1]).exists() else 0)", "/var/lib/glyahh/import-disks/" + app], True)
                check("卷卸载、loop 解绑和私有目录清理确认", released["state"] == "RELEASED" and absent.returncode == 0, app)
            except Exception as error:
                report["status"] = "FAIL"
                report.setdefault("cleanupErrors", []).append({"appId": app, "error": type(error).__name__})
        if seed_owned:
            removed = run(["python3", "-c", "import pathlib,shutil,sys; p=pathlib.Path(sys.argv[1]); assert p.parent==pathlib.Path('/tmp') and p.name.startswith('glyahh-import-seed-fixture-') and not p.is_symlink(); shutil.rmtree(p) if p.exists() else None; assert not p.exists()", seed_fixture], True)
            if removed.returncode:
                report["status"] = "FAIL"
            report["checks"].append({"name": "专用冻结源目录清理确认", "status": "PASS" if removed.returncode == 0 else "FAIL", "detail": seed_fixture})
        (evidence / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({"status": report["status"], "checks": report["checks"], "error": report.get("error"), "evidence": str(evidence)}, ensure_ascii=False))
    raise SystemExit(0 if report["status"] == "PASS" else 1)


if __name__ == "__main__":
    main()
