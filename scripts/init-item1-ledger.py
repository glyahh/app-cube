"""生成全部待验收行，不沿用旧 PASS，不覆盖既有账本。"""
import argparse
import importlib.util
import json
from pathlib import Path

from item1_requirements import required_rows, requirements_hash

spec = importlib.util.spec_from_file_location('item1_gate', Path(__file__).with_name('verify-item1-complete.py'))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def initialize(cases, run_id, content_hash, cases_hash):
    if not isinstance(run_id, str) or not run_id.strip():
        raise ValueError('RUN_ID_MISSING')
    if requirements_hash(cases) != gate.APPROVED_REQUIREMENTS_HASH:
        raise ValueError('REQUIREMENTS_CHANGED_WITHOUT_APPROVED_REVISION')
    pending = {'status': 'NOT_RUN', 'exitCode': None, 'command': None, 'artifacts': [],
               'runId': run_id, 'sourceHash': content_hash}
    return {'schemaVersion': 1, 'runId': run_id, 'sourceHash': content_hash, 'casesHash': cases_hash,
            'requirementsHash': requirements_hash(cases),
            'environmentFingerprint': None,
            'cleanup': dict(pending), 'cleanReplay': dict(pending),
            'finalAcceptance': {layer: dict(pending) for layer in ['unit', 'api', 'browser', 'docker', 'realModel']},
            'rows': [{**pending, 'caseId': case_id, 'scenarioId': scenario_id, 'layer': layer}
                     for case_id, scenario_id, layer in required_rows(cases)]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    path = gate.ROOT / 'docs/PLANS/project-import-backend-test-cases.json'
    cases = json.loads(path.read_text(encoding='utf-8'))
    ledger = initialize(cases, args.run_id, gate.source_hash(), gate.sha(path))
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    # 独占创建，已有证据账本不能被初始化覆盖。
    with output.open('x', encoding='utf-8') as stream:
        json.dump(ledger, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    print('NOT_COMPLETE | pending rows:', len(ledger['rows']))


if __name__ == '__main__':
    main()
