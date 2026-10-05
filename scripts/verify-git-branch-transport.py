"""只读公开样本：独立 Git 配置与显式现有本机代理，不执行工程、不读取凭据。"""
import argparse
import json
import os
import shutil
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(); parser.add_argument("--output", required=True)
    parser.add_argument("--proxy-port", required=True, type=int)
    args = parser.parse_args()
    if not 1 <= args.proxy_port <= 65535:
        raise RuntimeError("代理端口无效。")
    evidence = Path(args.output).resolve(); evidence.mkdir(parents=True, exist_ok=False)
    git = shutil.which("git")
    if not git:
        raise RuntimeError("Git 未安装，不自动安装。")
    environment = {name: value for name, value in os.environ.items()
                   if not name.upper().startswith(("GIT_", "GCM_", "CURL_", "SSH_")) and not name.upper().endswith("_PROXY")}
    home = evidence / "private-home"; home.mkdir()
    empty = evidence / "empty-config"; empty.write_text("", encoding="utf-8")
    template = evidence / "empty-template"; template.mkdir()
    environment.update({"HOME": str(home), "USERPROFILE": str(home), "XDG_CONFIG_HOME": str(home),
                        "GIT_CONFIG_GLOBAL": str(empty), "GIT_CONFIG_NOSYSTEM": "1", "GIT_TERMINAL_PROMPT": "0",
                        "GCM_INTERACTIVE": "never", "GIT_CEILING_DIRECTORIES": str(evidence.parent)})
    config = ["http.sslVerify=true", "http.followRedirects=false", "http.extraHeader=", "http.cookieFile=",
              "http.saveCookies=false", "credential.helper=", "credential.useHttpPath=true", "core.askPass=",
              f"core.hooksPath={template}", "protocol.allow=never", "protocol.https.allow=always",
              f"http.proxy=http://127.0.0.1:{args.proxy_port}"]
    prefix = [git]
    for value in config: prefix.extend(["-c", value])
    report = {"status": "RUNNING", "gitExecutable": git, "transportEvidence": "EXPLICIT_PROXY_TLS_HOST",
              "directDnsAcceptance": "NOT_RUN", "privateAcceptance": "NOT_RUN", "tests": []}
    try:
        for provider, url in [("GitHub", "https://github.com/octocat/Hello-World.git"), ("Gitee", "https://gitee.com/sdk/gitee5j.git")]:
            directory = evidence / provider; directory.mkdir()
            initialized = subprocess.run(prefix + ["init", "--bare", f"--template={template}", str(directory)],
                                         cwd=evidence, env=environment, capture_output=True, timeout=30)
            assert initialized.returncode == 0
            command = prefix + [f"--git-dir={directory}", "ls-remote", "--heads", "--", url]
            completed = subprocess.run(command, cwd=directory, env=environment, capture_output=True, timeout=60)
            record = {"provider": provider, "repository": url, "command": command, "exitCode": completed.returncode,
                      "stdoutBytes": len(completed.stdout), "stderrBytes": len(completed.stderr)}
            report["tests"].append(record)
            (evidence / f"{provider}-stdout.txt").write_bytes(completed.stdout)
            (evidence / f"{provider}-stderr.txt").write_bytes(completed.stderr)
            assert completed.returncode == 0 and 0 < len(completed.stdout) <= 8 * 1024 * 1024
            lines = completed.stdout.decode("utf-8").splitlines()
            branches = []
            for line in lines:
                sha, ref = line.split("\t")
                assert len(sha) == 40 and all(c in "0123456789abcdef" for c in sha) and ref.startswith("refs/heads/")
                branches.append({"name": ref[len("refs/heads/"):], "commit": sha})
            assert len({row["name"] for row in branches}) == len(branches)
            record["branches"] = branches; record["status"] = "PASS"
        report["status"] = "PASS"
    except Exception as error:
        report["status"] = "FAIL"; report["errorType"] = type(error).__name__; raise
    finally:
        (evidence / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({"status": report["status"], "platforms": len(report["tests"]), "evidence": str(evidence)}))


if __name__ == "__main__": main()
