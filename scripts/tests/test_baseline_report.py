"""基线报告解析的固定样本测试。"""
import importlib.util
import json
from pathlib import Path

import pytest

SPEC = importlib.util.spec_from_file_location(
    'summarize_test_results', Path(__file__).parents[1] / 'checks' / 'summarize-test-results.py')
baseline = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(baseline)

SUREFIRE = '''<testsuite name="X" tests="3" failures="1" errors="0" skipped="1">
  <testcase classname="demo.X" name="ok"/>
  <testcase classname="demo.X" name="bad"><failure message="boom"/></testcase>
  <testcase classname="demo.X" name="skip"><skipped/></testcase>
</testsuite>'''

JUNIT = '''<testsuites><testsuite name="pytest" tests="2" failures="0" errors="1" skipped="0">
  <testcase classname="tests.test_a" name="test_one"/>
  <testcase classname="tests.test_a" name="test_two"><error message="err"/></testcase>
</testsuite></testsuites>'''

VITEST = json.dumps({
    'testResults': [{'assertionResults': [
        {'fullName': 'a > b', 'status': 'passed'},
        {'fullName': 'a > c', 'status': 'failed'},
        {'fullName': 'a > d', 'status': 'skipped'},
    ]}]
})


def test_surefire_counts_each_case(tmp_path):
    report = tmp_path / 'TEST-demo.X.xml'
    report.write_text(SUREFIRE, encoding='utf-8')
    summary = baseline.summarize_surefire(tmp_path)
    assert (summary['total'], summary['passed'], summary['failed'], summary['skipped']) == (3, 1, 1, 1)
    assert {c['name'] for c in summary['cases']} == {'demo.X#ok', 'demo.X#bad', 'demo.X#skip'}


def test_junit_treats_error_as_failure(tmp_path):
    report = tmp_path / 'pytest.xml'
    report.write_text(JUNIT, encoding='utf-8')
    summary = baseline.summarize_junit(report)
    assert summary['failed'] == 1 and summary['passed'] == 1


def test_vitest_maps_status(tmp_path):
    report = tmp_path / 'vitest.json'
    report.write_text(VITEST, encoding='utf-8')
    summary = baseline.summarize_vitest(report)
    assert (summary['total'], summary['passed'], summary['failed'], summary['skipped']) == (3, 1, 1, 1)


def test_missing_report_is_not_reported_as_pass(tmp_path):
    summary = baseline.summarize_surefire(tmp_path / 'absent')
    assert summary['report'] == 'missing' and summary['total'] == 0


def test_compare_runs_marks_changed_cases():
    first = [{'name': 'a', 'status': 'passed'}, {'name': 'b', 'status': 'passed'}]
    second = [{'name': 'a', 'status': 'failed'}, {'name': 'b', 'status': 'passed'}]
    assert baseline.compare_runs(first, second) == ['a']


if __name__ == '__main__':
    raise SystemExit(pytest.main([__file__]))
