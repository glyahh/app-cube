"""完成门禁按子场景和验证层拒绝缺证据、旧版本与伪成功。"""
import copy
import importlib.util
import json
from pathlib import Path

import pytest


spec = importlib.util.spec_from_file_location('item1_complete', Path(__file__).parents[1] / 'checks' / 'verify-item1-complete.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


@pytest.fixture
def baseline(tmp_path):
    artifact = tmp_path / 'evidence.json'
    artifact.write_text('{"actual":"fixture only"}', encoding='utf-8')
    record = {'runId': 'test-run', 'sourceHash': 'current', 'status': 'PASS', 'exitCode': 0,
              'command': 'fixture command', 'artifacts': [{'path': str(artifact), 'sha256': gate.sha(artifact)}]}
    cases = {'supportedProviders': ['GitHub'], 'cases': [{'id': 'F01', 'subscenarios': [
        {'id': 'chinese-path', 'requirement': 'fixture assertion', 'layers': ['unit', 'browser']},
        {'id': 'binary-file', 'requirement': 'fixture assertion', 'layers': ['disk']}]}]}
    ledger = {'runId': 'test-run', 'sourceHash': 'current', 'casesHash': 'cases', 'environmentFingerprint': 'fixture-env',
              'cleanup': copy.deepcopy(record), 'cleanReplay': copy.deepcopy(record), 'rows': []}
    ledger['finalAcceptance'] = {layer: copy.deepcopy(record) for layer in ['unit', 'api', 'browser', 'docker', 'realModel']}
    ledger['finalAcceptance']['docker']['actualContainers'] = 1
    ledger['finalAcceptance']['realModel']['actualModelCalls'] = 1
    for scenario in cases['cases'][0]['subscenarios']:
        for layer in scenario['layers']:
            ledger['rows'].append({**copy.deepcopy(record), 'caseId': 'F01', 'scenarioId': scenario['id'], 'layer': layer})
    return cases, ledger, artifact


def test_all_required_fixture_layers_can_pass_validator(baseline):
    cases, ledger, _ = baseline
    assert gate.validate(cases, ledger, 'current', 'cases', expected_case_ids={'F01'})['status'] == 'COMPLETE'


@pytest.mark.parametrize('fault', ['missing-row', 'partial', 'stale-source', 'stale-case', 'cleanup', 'replay',
                                 'duplicate-row', 'changed-artifact', 'unfrozen', 'scope-removal', 'no-model', 'no-docker'])
def test_core_assertion_and_false_success_never_close_the_item(baseline, fault):
    cases, ledger, artifact = baseline
    if fault == 'missing-row': ledger['rows'].pop()
    elif fault == 'partial': ledger['rows'][0]['status'] = 'PARTIAL'
    elif fault == 'stale-source': ledger['sourceHash'] = 'old'
    elif fault == 'stale-case': ledger['casesHash'] = 'old'
    elif fault == 'cleanup': ledger['cleanup']['status'] = 'NOT_RUN'
    elif fault == 'replay': ledger['cleanReplay']['status'] = 'NOT_RUN'
    elif fault == 'duplicate-row': ledger['rows'].append(copy.deepcopy(ledger['rows'][0]))
    elif fault == 'changed-artifact': artifact.write_text('changed', encoding='utf-8')
    elif fault == 'unfrozen': del cases['cases'][0]['subscenarios']
    elif fault == 'scope-removal': cases['cases'][0].update(status='REMOVED_FROM_SCOPE', scopeReason='not authorized')
    elif fault == 'no-model': ledger['finalAcceptance']['realModel']['actualModelCalls'] = 0
    elif fault == 'no-docker': ledger['finalAcceptance']['docker']['actualContainers'] = 0
    assert gate.validate(cases, ledger, 'current', 'cases', expected_case_ids={'F01'})['status'] == 'NOT_COMPLETE'


def test_product_manifest_cannot_drop_most_cases_to_claim_complete(baseline):
    cases, ledger, _ = baseline
    result = gate.validate(cases, ledger, 'current', 'cases')
    assert 'CASE_SET_CHANGED_WITHOUT_APPROVED_SCOPE_REVISION' in result['errors']


def product_cases():
    return json.loads((gate.ROOT / 'docs/tests/project-import-backend-test-cases.json').read_text(encoding='utf-8'))


def test_full_product_denominator_is_frozen_without_reusing_old_evidence():
    cases = product_cases()
    assert gate.requirements_hash(cases) == gate.APPROVED_REQUIREMENTS_HASH
    active = [case for case in cases['cases'] if case.get('status') != 'REMOVED_FROM_SCOPE']
    assert len(active) == 57
    assert sum(len(case['subscenarios']) for case in active) == 345
    result = gate.validate(cases, {}, 'current', 'cases')
    assert result['requiredChecks'] == 856
    assert not any('NOT_FROZEN' in error or 'REQUIREMENTS_CHANGED' in error for error in result['errors'])
    assert result['status'] == 'NOT_COMPLETE'
    for case in active:
        assert len({child['id'] for child in case['subscenarios']}) == len(case['subscenarios'])
        if case['id'] != 'G12':
            for child in case['subscenarios']:
                assert child['layers'] == case['layers'].split('、')
                assert child['expected'] == case['expected']


@pytest.mark.parametrize('fault', ['drop-child', 'drop-layer', 'replace-layer', 'replace-requirement',
                                 'replace-expected', 'scope-reason', 'revision', 'duplicate-layer'])
def test_rehashing_reduced_manifest_does_not_approve_changed_requirements(fault):
    cases = product_cases()
    case = next(case for case in cases['cases'] if case['id'] == 'F01')
    child = case['subscenarios'][0]
    if fault == 'drop-child': case['subscenarios'].pop()
    elif fault == 'drop-layer': child['layers'].pop()
    elif fault == 'replace-layer': child['layers'][0] = 'fixture-only'
    elif fault == 'replace-requirement': child['requirement'] = 'HTTP 200 only'
    elif fault == 'replace-expected': child['expected'] = 'process listening only'
    elif fault == 'scope-reason':
        next(case for case in cases['cases'] if case['id'] == 'G03')['scopeReason'] = 'different scope'
    elif fault == 'revision': cases['requirementsRevision'] = 'silently changed'
    elif fault == 'duplicate-layer': child['layers'].append(child['layers'][0])
    result = gate.validate(cases, {'casesHash': 'recomputed'}, 'current', 'recomputed')
    assert 'REQUIREMENTS_CHANGED_WITHOUT_APPROVED_REVISION' in result['errors']
    assert result['status'] == 'NOT_COMPLETE'


def test_status_and_old_evidence_pointers_do_not_change_requirements():
    cases = product_cases()
    child = cases['cases'][0]['subscenarios'][0]
    child.update(status='PASS', evidence=['historical.json'])
    cases['cases'][0]['implementedTests'] = ['test locator only']
    assert gate.requirements_hash(cases) == gate.APPROVED_REQUIREMENTS_HASH
    assert gate.validate(cases, {}, 'current', 'cases')['status'] == 'NOT_COMPLETE'


def test_manifest_order_does_not_change_requirements():
    cases = product_cases()
    cases['cases'].reverse()
    for case in cases['cases']:
        case.get('subscenarios', []).reverse()
    assert gate.requirements_hash(cases) == gate.APPROVED_REQUIREMENTS_HASH


def test_boolean_exit_code_is_not_actual_success(baseline):
    cases, ledger, _ = baseline
    ledger['rows'][0]['exitCode'] = False
    result = gate.validate(cases, ledger, 'current', 'cases', expected_case_ids={'F01'})
    assert result['status'] == 'NOT_COMPLETE'


def test_initial_ledger_contains_every_pending_row_and_no_claimed_environment():
    spec = importlib.util.spec_from_file_location('item1_init', Path(__file__).parents[1] / 'checks' / 'init-item1-ledger.py')
    initializer = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(initializer)
    cases = product_cases()
    ledger = initializer.initialize(cases, 'new-run', 'current', 'cases')
    assert len(ledger['rows']) == 856
    assert {row['status'] for row in ledger['rows']} == {'NOT_RUN'}
    assert all(row['exitCode'] is None and not row['artifacts'] for row in ledger['rows'])
    assert ledger['environmentFingerprint'] is None
    assert gate.validate(cases, ledger, 'current', 'cases')['status'] == 'NOT_COMPLETE'
    cases['cases'][0]['subscenarios'].pop()
    with pytest.raises(ValueError, match='REQUIREMENTS_CHANGED'):
        initializer.initialize(cases, 'new-run', 'current', 'changed-cases')


def test_initializer_refuses_overwriting_existing_evidence(tmp_path, monkeypatch):
    spec = importlib.util.spec_from_file_location('item1_init_write', Path(__file__).parents[1] / 'checks' / 'init-item1-ledger.py')
    initializer = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(initializer)
    output = tmp_path / 'ledger.json'
    output.write_text('existing evidence', encoding='utf-8')
    monkeypatch.setattr(initializer.gate, 'source_hash', lambda: 'current')
    monkeypatch.setattr('sys.argv', ['init-item1-ledger.py', '--run-id', 'test-run', '--output', str(output)])
    with pytest.raises(FileExistsError):
        initializer.main()
    assert output.read_text(encoding='utf-8') == 'existing evidence'
