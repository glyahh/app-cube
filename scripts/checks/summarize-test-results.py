"""汇总基线测试报告；报告缺失时明确标记，不把缺失当成零失败。"""
import argparse
import json
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

MISSING = {'report': 'missing', 'total': 0, 'passed': 0, 'failed': 0, 'skipped': 0, 'cases': []}


def _status(node):
    if node.find('failure') is not None or node.find('error') is not None:
        return 'failed'
    if node.find('skipped') is not None:
        return 'skipped'
    return 'passed'


def _case(name, status):
    return {'name': name, 'status': status}


def _tally(cases):
    return {
        'report': 'ok',
        'total': len(cases),
        'passed': sum(1 for c in cases if c['status'] == 'passed'),
        'failed': sum(1 for c in cases if c['status'] == 'failed'),
        'skipped': sum(1 for c in cases if c['status'] == 'skipped'),
        'cases': cases,
    }


def _cases_from_suite(root):
    suites = [root] if root.tag == 'testsuite' else root.findall('testsuite')
    cases = []
    for suite in suites:
        for node in suite.findall('testcase'):
            name = '#'.join(filter(None, [node.get('classname'), node.get('name')]))
            cases.append(_case(name, _status(node)))
    return cases


def summarize_surefire(directory):
    if directory is None or not Path(directory).is_dir():
        return dict(MISSING)
    files = sorted(Path(directory).glob('TEST-*.xml'))
    if not files:
        return dict(MISSING)
    cases = []
    for file in files:
        cases.extend(_cases_from_suite(ET.parse(file).getroot()))
    return _tally(cases)


def summarize_junit(path):
    if path is None or not Path(path).is_file():
        return dict(MISSING)
    return _tally(_cases_from_suite(ET.parse(path).getroot()))


def summarize_vitest(path):
    if path is None or not Path(path).is_file():
        return dict(MISSING)
    data = json.loads(Path(path).read_text(encoding='utf-8'))
    cases = []
    for result in data.get('testResults', []):
        for assertion in result.get('assertionResults', []):
            status = assertion.get('status')
            mapped = 'passed' if status == 'passed' else 'failed' if status == 'failed' else 'skipped'
            cases.append(_case(assertion.get('fullName') or assertion.get('title') or '', mapped))
    return _tally(cases)


def compare_runs(first, second):
    """返回两次运行中状态不一致的用例，供基线标记为不稳定。"""
    left = {(c['name'], c['status']) for c in first}
    right = {(c['name'], c['status']) for c in second}
    names = {name for name, _ in left ^ right}
    return sorted(names)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--surefire', type=Path)
    parser.add_argument('--junit', type=Path)
    parser.add_argument('--vitest', type=Path)
    parser.add_argument('--java-exit', type=int, default=None)
    parser.add_argument('--python-exit', type=int, default=None)
    parser.add_argument('--frontend-exit', type=int, default=None)
    parser.add_argument('--sha', default='')
    parser.add_argument('--dirty', action='store_true')
    parser.add_argument('--previous', type=Path, help='上一次同代码基线的 JSON，用于标记 unstable')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()

    java = summarize_surefire(args.surefire)
    python = summarize_junit(args.junit)
    frontend = summarize_vitest(args.vitest)
    for summary, code in ((java, args.java_exit), (python, args.python_exit), (frontend, args.frontend_exit)):
        summary['exitCode'] = code

    report = {
        'sha': args.sha,
        'generatedAt': datetime.now(timezone.utc).isoformat(),
        'dirty': args.dirty,
        'unstable': [],
        'java': java,
        'python': python,
        'frontend': frontend,
    }
    if args.previous and args.previous.is_file():
        previous = json.loads(args.previous.read_text(encoding='utf-8'))
        unstable = set(previous.get('unstable', []))
        for key in ('java', 'python', 'frontend'):
            unstable.update(compare_runs(previous.get(key, {}).get('cases', []), report[key]['cases']))
        report['unstable'] = sorted(unstable)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')

    failed = java['failed'] + python['failed'] + frontend['failed']
    missing = [k for k in ('java', 'python', 'frontend') if report[k]['report'] == 'missing']
    nonzero = [k for k in ('java', 'python', 'frontend') if report[k]['exitCode'] not in (0, None)]
    print(f"java={java['passed']}/{java['total']} python={python['passed']}/{python['total']} "
          f"frontend={frontend['passed']}/{frontend['total']} failed={failed} unstable={len(report['unstable'])}")
    if missing:
        print('missing reports: ' + ', '.join(missing))
    if nonzero:
        print('nonzero exits: ' + ', '.join(nonzero))
    return 1 if failed or missing or nonzero else 0


if __name__ == '__main__':
    raise SystemExit(main())
