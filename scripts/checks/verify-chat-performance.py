"""汇总交替回放的原始报告；失败样本保留，目标只用同场景数据核对。"""
import argparse
import json
import statistics
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--directory', type=Path, default=Path('temp/logs/chat-performance-20261020'))
    args = parser.parse_args()
    report = {'frontend': [], 'acceptance': {}}
    baselines = [json.loads((args.directory / f'baseline-final{i}.json').read_text()) for i in range(1, 4)]
    optimized = [json.loads((args.directory / f'optimized-final{i}.json').read_text()) for i in range(1, 4)]
    for index, sample in enumerate(baselines[0]['results']):
        row = {'kind': sample['kind'], 'size': sample.get('count', sample.get('stages'))}
        for metric in ['input', 'stream', 'toggle', 'scroll']:
            if metric not in sample:
                continue
            before = [r['results'][index][metric]['p95'] for r in baselines]
            after = [r['results'][index][metric]['p95'] for r in optimized]
            b, a = statistics.median(before), statistics.median(after)
            row[metric] = {'beforeP95': before, 'afterP95': after, 'medianBeforeP95': b,
                           'medianAfterP95': a, 'reductionPercent': (1 - a / b) * 100 if b else None}
        report['frontend'].append(row)
    history = next(r for r in report['frontend'] if r['kind'] == 'history' and r['size'] == 300)['input']
    report['acceptance']['history300'] = max(history['afterP95']) < 30 and history['reductionPercent'] >= 60
    review_path = args.directory / 'review-performance.json'
    if review_path.exists():
        reviews = json.loads(review_path.read_text())
        review = {'startupMs': reviews['startupMs']}
        for metric in ['prepareMs', 'invokeMs', 'totalMs']:
            groups = {mode: [r[metric] if metric == 'totalMs' else r['timing'][metric]
                             for r in reviews['results'] if r['mode'] == mode] for mode in ['cold', 'warm']}
            b, a = statistics.median(groups['cold']), statistics.median(groups['warm'])
            review[metric] = {**groups, 'medianCold': b, 'medianWarm': a, 'reductionPercent': (1 - a / b) * 100}
        report['review'] = review
        report['acceptance']['reviewPreparation'] = review['prepareMs']['reductionPercent'] >= 80
    report['real'] = {}
    for phase in ['before', 'after']:
        path = args.directory / f'{phase}-real.json'
        if path.exists():
            data = json.loads(path.read_text(encoding='utf-8'))
            successes = [r['elapsedMs'] for r in data['runs'] if r['succeeded']]
            report['real'][phase] = {'samples': data['runs'], 'successes': len(successes),
                                    'failures': len(data['runs']) - len(successes),
                                    'successfulMedianMs': statistics.median(successes) if successes else None}
    if all(report['real'].get(k, {}).get('successfulMedianMs') for k in ['before', 'after']):
        report['real']['successfulMedianReductionPercent'] = (1 - report['real']['after']['successfulMedianMs'] /
                                                              report['real']['before']['successfulMedianMs']) * 100
    (args.directory / 'comparison.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report['acceptance']))
    return 0 if all(report['acceptance'].values()) else 1


if __name__ == '__main__':
    raise SystemExit(main())
