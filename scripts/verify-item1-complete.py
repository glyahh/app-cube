"""第一项严格完成门禁；不启动服务，不修改产品状态，不引用历史核心断言。"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from item1_requirements import requirements_hash


ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOTS = ('src/', 'agent/', 'ai-generate-code-frontend/', 'scripts/', 'deploy/')
SOURCE_NAMES = {'pom.xml', 'mvnw', 'mvnw.cmd', '.env.example'}
EXCLUDED_PARTS = {'__pycache__', '.pytest_cache', 'node_modules', '.venv', 'dist', 'target'}
APPROVED_CASE_IDS = {f'{prefix}{number:02d}' for prefix, count in
                     [('F', 7), ('G', 12), ('C', 5), ('U', 6), ('D', 8), ('S', 7), ('R', 8), ('E', 6)]
                     for number in range(1, count + 1)}
# 仅明确修订范围时更新；重算证据摘要不能批准删减要求。
APPROVED_REQUIREMENTS_HASH = '935a882f0658eecaad9fe0d1630eff84a64678e8eb6db3316d3c055c313b43b5'
APPROVED_REQUIRED_CHECKS = 856


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        while block := stream.read(1 << 20):
            digest.update(block)
    return digest.hexdigest()


def source_hash():
    listed = subprocess.run(['git', 'ls-files', '-co', '--exclude-standard', '-z'], cwd=ROOT,
                            capture_output=True, check=True)
    records = []
    for name in sorted(set(listed.stdout.decode('utf-8').split('\0')) - {''}):
        if name not in SOURCE_NAMES and not name.startswith(SOURCE_ROOTS):
            continue
        path = ROOT / name
        if any(part in EXCLUDED_PARTS for part in path.relative_to(ROOT).parts):
            continue
        if path.is_symlink() or not path.is_file():
            records.append([name, 'MISSING_OR_LINK'])
        else:
            records.append([name, sha(path)])
    if not records:
        raise ValueError('SOURCE_INVENTORY_EMPTY')
    return hashlib.sha256(json.dumps(records, separators=(',', ':')).encode()).hexdigest()


def validate(cases, ledger, content_hash, cases_hash, *, expected_case_ids=None):
    errors, required = [], set()
    seen_cases = set()
    if cases.get('supportedProviders') != ['GitHub']:
        errors.append('PROVIDER_SCOPE_MISMATCH')
    if not isinstance(cases.get('cases'), list) or not cases['cases']:
        errors.append('CASE_MANIFEST_EMPTY')
    for case in cases.get('cases', []):
        case_id = case.get('id')
        if not isinstance(case_id, str) or case_id in seen_cases:
            errors.append('CASE_ID_INVALID_OR_DUPLICATE')
            continue
        seen_cases.add(case_id)
        if case.get('status') == 'REMOVED_FROM_SCOPE':
            if case_id not in {'G03', 'G04'} or not case.get('scopeReason'):
                errors.append(f'{case_id}:UNAPPROVED_SCOPE_REMOVAL')
            continue
        subscenarios = case.get('subscenarios')
        if not isinstance(subscenarios, list) or not subscenarios:
            errors.append(f'{case_id}:SUBSCENARIOS_NOT_FROZEN')
            continue
        seen = set()
        for scenario in subscenarios:
            scenario_id = scenario.get('id')
            layers = scenario.get('layers')
            if not isinstance(scenario_id, str) or scenario_id in seen or not scenario.get('requirement'):
                errors.append(f'{case_id}:SUBSCENARIO_INVALID_OR_DUPLICATE')
                continue
            seen.add(scenario_id)
            if (not isinstance(layers, list) or not layers
                    or any(not isinstance(layer, str) or not layer for layer in layers)
                    or len(set(layers)) != len(layers)):
                errors.append(f'{case_id}/{scenario_id}:LAYERS_NOT_FROZEN')
                continue
            required.update((case_id, scenario_id, layer) for layer in layers)

    if seen_cases != (APPROVED_CASE_IDS if expected_case_ids is None else expected_case_ids):
        errors.append('CASE_SET_CHANGED_WITHOUT_APPROVED_SCOPE_REVISION')
    if expected_case_ids is None:
        if requirements_hash(cases) != APPROVED_REQUIREMENTS_HASH:
            errors.append('REQUIREMENTS_CHANGED_WITHOUT_APPROVED_REVISION')
        if len(required) != APPROVED_REQUIRED_CHECKS:
            errors.append('REQUIRED_CHECK_COUNT_CHANGED')

    if ledger.get('sourceHash') != content_hash:
        errors.append('SOURCE_VERSION_MISMATCH')
    if ledger.get('casesHash') != cases_hash:
        errors.append('CASE_VERSION_MISMATCH')
    run_id = ledger.get('runId')
    if not isinstance(run_id, str) or not run_id:
        errors.append('RUN_ID_MISSING')
    if not ledger.get('environmentFingerprint'):
        errors.append('ENVIRONMENT_FINGERPRINT_MISSING')
    if ledger.get('cleanup', {}).get('status') != 'PASS':
        errors.append('CLEANUP_UNCONFIRMED')
    if ledger.get('cleanReplay', {}).get('status') != 'PASS':
        errors.append('CLEAN_REPLAY_UNCONFIRMED')

    def evidence(record, label):
        if record.get('runId') != run_id or record.get('sourceHash') != content_hash:
            errors.append(label + ':EVIDENCE_IDENTITY_MISMATCH')
        if (record.get('status') != 'PASS' or type(record.get('exitCode')) is not int
                or record['exitCode'] != 0 or not record.get('command')):
            errors.append(label + ':NOT_PASS_OR_NOT_EXECUTED')
        artifacts = record.get('artifacts')
        if not isinstance(artifacts, list) or not artifacts:
            errors.append(label + ':ARTIFACTS_MISSING')
            return
        for artifact in artifacts:
            path = Path(artifact.get('path', ''))
            if not path.is_absolute() or path.is_symlink() or not path.is_file() or sha(path) != artifact.get('sha256'):
                errors.append(label + ':ARTIFACT_INVALID')

    for label in ('cleanup', 'cleanReplay'):
        evidence(ledger.get(label, {}), label)
    required_acceptance = {'unit', 'api', 'browser', 'docker', 'realModel'}
    acceptance = ledger.get('finalAcceptance', {})
    if not isinstance(acceptance, dict) or set(acceptance) != required_acceptance:
        errors.append('FINAL_ACCEPTANCE_LAYERS_MISSING_OR_UNKNOWN')
        acceptance = acceptance if isinstance(acceptance, dict) else {}
    for layer in required_acceptance:
        record = acceptance.get(layer, {})
        evidence(record, 'finalAcceptance/' + layer)
        if layer == 'realModel' and (type(record.get('actualModelCalls')) is not int or record['actualModelCalls'] <= 0):
            errors.append('REAL_MODEL_CALL_NOT_PROVEN')
        if layer == 'docker' and (type(record.get('actualContainers')) is not int or record['actualContainers'] <= 0):
            errors.append('REAL_DOCKER_EXECUTION_NOT_PROVEN')
    seen_rows = set()
    for row in ledger.get('rows', []):
        key = (row.get('caseId'), row.get('scenarioId'), row.get('layer'))
        label = '/'.join(str(value) for value in key)
        if key in seen_rows or key not in required:
            errors.append(label + ':UNKNOWN_OR_DUPLICATE_COVERAGE')
        seen_rows.add(key)
        evidence(row, label)
    errors.extend('/'.join(key) + ':NOT_RUN' for key in sorted(required - seen_rows))
    return {'status': 'NOT_COMPLETE' if errors else 'COMPLETE', 'requiredChecks': len(required),
            'recordedChecks': len(seen_rows), 'sourceHash': content_hash, 'casesHash': cases_hash, 'errors': errors}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cases', default=str(ROOT / 'docs/PLANS/project-import-backend-test-cases.json'))
    parser.add_argument('--ledger', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    cases_path, ledger_path = Path(args.cases), Path(args.ledger)
    cases = json.loads(cases_path.read_text(encoding='utf-8'))
    ledger = json.loads(ledger_path.read_text(encoding='utf-8')) if ledger_path.is_file() else {}
    result = validate(cases, ledger, source_hash(), sha(cases_path))
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(result['status'], '| required:', result['requiredChecks'], '| errors:', len(result['errors']))
    return 0 if result['status'] == 'COMPLETE' else 1


if __name__ == '__main__':
    raise SystemExit(main())
