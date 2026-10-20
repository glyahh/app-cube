"""核心18项完成门禁；校验证据内容和版本，不修改产品状态。"""
import argparse
import importlib.util
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from item1_core_requirements import CHECKPOINTS, profile

spec = importlib.util.spec_from_file_location('strict_item1_gate', Path(__file__).with_name('verify-item1-complete.py'))
strict = importlib.util.module_from_spec(spec)
spec.loader.exec_module(strict)


def validate(manifest, ledger, current_hash):
    errors, passed = [], set()
    if manifest != profile():
        errors.append('CORE_SCOPE_CHANGED')
    run = ledger.get('runId')
    if not isinstance(run, str) or not run:
        errors.append('RUN_ID_MISSING')
    if ledger.get('sourceHash') != current_hash:
        errors.append('SOURCE_VERSION_MISMATCH')
    if not ledger.get('environmentFingerprint'):
        errors.append('ENVIRONMENT_FINGERPRINT_MISSING')
    required = {(key, assertion) for key, assertions in CHECKPOINTS.items() for assertion in assertions}
    observed = set()
    for row in ledger.get('rows', []):
        key = (row.get('checkpoint'), row.get('assertion'))
        label = '/'.join(str(part) for part in key)
        before = len(errors)
        if key not in required or key in observed:
            errors.append(label + ':UNKNOWN_OR_DUPLICATE')
        observed.add(key)
        if row.get('runId') != run or row.get('sourceHash') != current_hash:
            errors.append(label + ':EVIDENCE_VERSION_MISMATCH')
        if row.get('status') != 'PASS' or type(row.get('exitCode')) is not int or row['exitCode'] != 0 or not row.get('command'):
            errors.append(label + ':NOT_PASS')
        # 每项引用报告内的精确检查名；只有文件摘要或整份报告PASS不足以证明子场景。
        evidence = row.get('evidence')
        if not isinstance(evidence, list) or not evidence:
            errors.append(label + ':EVIDENCE_MISSING')
            evidence = []
        for item in evidence:
            try:
                path = Path(item['path'])
                if not path.is_absolute() or path.is_symlink() or not path.is_file() or strict.sha(path) != item['sha256']:
                    raise ValueError('ARTIFACT_INVALID')
                report = json.loads(path.read_text(encoding='utf-8'))
                if report.get('runId') != run or report.get('sourceHash') != current_hash or report.get('status') != 'PASS':
                    raise ValueError('REPORT_VERSION_OR_STATUS_INVALID')
                matches = [c for c in report.get('checks', []) if c.get('name') == item.get('check')]
                if len(matches) != 1 or matches[0].get('status') != 'PASS' or matches[0].get('assertion') != key[1]:
                    raise ValueError('ASSERTION_NOT_PROVEN')
                check = matches[0]
                if key[1] in {'formal-sse-model-edit', 'formal-websocket-model-edit', 'clean-source-real-model-business-replay'}:
                    if type(check.get('actualModelCalls')) is not int or check['actualModelCalls'] < 1:
                        raise ValueError('REAL_MODEL_NOT_PROVEN')
                if key[1] in {'real-docker-cpu-memory-swap-pids', 'real-disk-limit', 'real-old-move-interruption-recovery'}:
                    if check.get('realExecution') is not True:
                        raise ValueError('REAL_EXECUTION_NOT_PROVEN')
                if key[1] == 'independent-resource-cleanup':
                    # 清理必须逐资源读回，不接受顶层cleanup=CONFIRMED代替实际观测。
                    resources = check.get('resources')
                    if not isinstance(resources, list) or not resources or any(
                            not isinstance(r, dict) or not r.get('identity') or not r.get('probe')
                            or r.get('remaining') is not False or r.get('observed') is not True for r in resources):
                        raise ValueError('RESOURCE_CLEANUP_NOT_PROVEN')
            except (OSError, ValueError, KeyError, TypeError, AttributeError):
                errors.append(label + ':ARTIFACT_OR_ASSERTION_INVALID')
        if before == len(errors) and key in required:
            passed.add(key)
    errors.extend('/'.join(key) + ':NOT_RUN' for key in sorted(required - observed))
    complete = [key for key, assertions in CHECKPOINTS.items() if all((key, a) in passed for a in assertions)]
    packages = {f'P{i}': sum(f'P{i}.{j}' in complete for j in range(1, 4)) for i in range(1, 7)}
    return {'status': 'NOT_COMPLETE' if errors else 'COMPLETE（核心交付范围）',
            'fullAcceptance': 'FULL_ACCEPTANCE_PENDING', 'sourceHash': current_hash,
            'checkpointsPassed': len(complete), 'checkpointsRequired': 18,
            'packagesPassed': sum(n == 3 for n in packages.values()), 'packagesRequired': 6,
            'packages': packages, 'assertionsRequired': len(required), 'assertionsPassed': len(passed), 'errors': errors}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--profile', default=str(strict.ROOT / 'docs/tests/project-import-core-checkpoints.json'))
    parser.add_argument('--ledger', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    manifest = json.loads(Path(args.profile).read_text(encoding='utf-8'))
    path = Path(args.ledger)
    ledger = json.loads(path.read_text(encoding='utf-8')) if path.is_file() else {}
    result = validate(manifest, ledger, strict.source_hash())
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(result['status'], f"{result['checkpointsPassed']}/18", f"{result['packagesPassed']}/6")
    return 1 if result['errors'] else 0


if __name__ == '__main__':
    raise SystemExit(main())
