"""第一项验收要求的稳定摘要；运行结果和历史证据不改变分母。"""
import hashlib
import json


def inventory(cases):
    records = []
    for case in cases.get('cases', []):
        removed = case.get('status') == 'REMOVED_FROM_SCOPE'
        records.append({
            'id': case.get('id'), 'removed': removed,
            'scopeReason': case.get('scopeReason') if removed else None,
            'scenario': case.get('scenario'), 'expected': case.get('expected'),
            'layers': case.get('layers'),
            'subscenarios': sorted([
                {'id': child.get('id'), 'requirement': child.get('requirement'),
                 'expected': child.get('expected'), 'layers': sorted(child.get('layers', []))}
                for child in case.get('subscenarios', [])
            ], key=lambda child: str(child['id'])),
        })
    return {'scopeRevision': cases.get('scopeRevision'),
            'requirementsRevision': cases.get('requirementsRevision'),
            'supportedProviders': cases.get('supportedProviders'),
            'cases': sorted(records, key=lambda record: str(record['id']))}


def requirements_hash(cases):
    encoded = json.dumps(inventory(cases), ensure_ascii=False, sort_keys=True,
                         separators=(',', ':')).encode('utf-8')
    return hashlib.sha256(encoded).hexdigest()


def required_rows(cases):
    return sorted({(case['id'], child['id'], layer)
                   for case in cases['cases'] if case.get('status') != 'REMOVED_FROM_SCOPE'
                   for child in case.get('subscenarios', []) for layer in child['layers']})
