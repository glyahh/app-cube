"""证明18项门禁拒绝删项、过期证据及虚构清理；夹具不是业务验收。"""
import copy
import importlib.util
import json
from pathlib import Path
import pytest

spec = importlib.util.spec_from_file_location('core_gate', Path(__file__).parents[1] / 'checks' / 'verify-item1-core-complete.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


@pytest.fixture
def baseline(tmp_path):
    rows, checks = [], []
    for key, assertions in gate.CHECKPOINTS.items():
        for assertion in assertions:
            name = key + '/' + assertion
            checks.append({'name': name, 'assertion': assertion, 'status': 'PASS', 'actualModelCalls': 1,
                           'realExecution': True, 'resources': [{'identity': 'fixture-container', 'probe': 'fixture-only',
                           'remaining': False, 'observed': True}]})
            rows.append({'checkpoint': key, 'assertion': assertion, 'status': 'PASS', 'runId': 'fixture-run',
                         'sourceHash': 'fixture-source', 'exitCode': 0, 'command': 'fixture-only', 'evidence': []})
    report = {'status': 'PASS', 'runId': 'fixture-run', 'sourceHash': 'fixture-source', 'checks': checks}
    path = tmp_path / 'report.json'
    path.write_text(json.dumps(report), encoding='utf-8')
    for row in rows:
        row['evidence'] = [{'path': str(path), 'sha256': gate.strict.sha(path),
                            'check': row['checkpoint'] + '/' + row['assertion']}]
    return gate.profile(), {'runId': 'fixture-run', 'sourceHash': 'fixture-source',
                           'environmentFingerprint': 'fixture-only', 'rows': rows}, path, report


def test_fixed_eighteen_checkpoints_and_six_packages(baseline):
    manifest, ledger, _, _ = baseline
    result = gate.validate(manifest, ledger, 'fixture-source')
    assert not result['errors'] and result['checkpointsPassed'] == 18 and result['packagesPassed'] == 6
    assert result['fullAcceptance'] == 'FULL_ACCEPTANCE_PENDING'


@pytest.mark.parametrize('fault', ['missing', 'partial', 'old-source', 'old-report', 'drop-scope', 'duplicate',
                                  'changed-bytes', 'false-cleanup', 'empty-cleanup', 'no-model', 'fake-docker',
                                  'wrong-check', 'no-evidence', 'nonzero', 'wrong-assertion'])
def test_negative_evidence_cannot_complete(baseline, fault):
    manifest, ledger, path, report = copy.deepcopy(baseline)
    if fault == 'missing': ledger['rows'].pop()
    elif fault == 'partial': ledger['rows'][0]['status'] = 'PARTIAL'
    elif fault == 'old-source': ledger['sourceHash'] = 'old'
    elif fault == 'drop-scope': manifest['checkpoints'].pop()
    elif fault == 'duplicate': ledger['rows'].append(copy.deepcopy(ledger['rows'][0]))
    elif fault == 'no-evidence': ledger['rows'][0]['evidence'] = []
    elif fault == 'nonzero': ledger['rows'][0]['exitCode'] = 1
    elif fault == 'wrong-check': ledger['rows'][0]['evidence'][0]['check'] = 'unknown'
    elif fault == 'changed-bytes': path.write_text('{}', encoding='utf-8')
    else:
        if fault == 'old-report': report['sourceHash'] = 'old'
        elif fault == 'false-cleanup':
            next(c for c in report['checks'] if c['assertion'] == 'independent-resource-cleanup')['resources'][0]['remaining'] = True
        elif fault == 'empty-cleanup':
            next(c for c in report['checks'] if c['assertion'] == 'independent-resource-cleanup')['resources'] = []
        elif fault == 'no-model':
            next(c for c in report['checks'] if c['assertion'] == 'formal-sse-model-edit')['actualModelCalls'] = 0
        elif fault == 'fake-docker':
            next(c for c in report['checks'] if c['assertion'] == 'real-disk-limit')['realExecution'] = False
        elif fault == 'wrong-assertion': report['checks'][0]['assertion'] = 'unrelated'
        path.write_text(json.dumps(report), encoding='utf-8')
        for row in ledger['rows']: row['evidence'][0]['sha256'] = gate.strict.sha(path)
    assert gate.validate(manifest, ledger, 'fixture-source')['errors']


def test_checked_in_profile_matches_frozen_requirements():
    path = gate.strict.ROOT / 'docs/tests/project-import-core-checkpoints.json'
    assert json.loads(path.read_text(encoding='utf-8')) == gate.profile()
