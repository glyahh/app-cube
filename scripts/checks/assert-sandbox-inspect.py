"""校验容器 inspect 的隔离字段；只做判定，不创建或清理容器。"""
import argparse
import json
from pathlib import Path


def evaluate(data, mode):
    """返回逐项检查结果。data 为 docker inspect 的单条记录。"""
    host = data.get('HostConfig') or {}
    config = data.get('Config') or {}
    checks = []

    def add(name, ok, actual, expected):
        checks.append({'name': name, 'ok': bool(ok), 'actual': actual, 'expected': expected})

    add('privileged', host.get('Privileged') is False, host.get('Privileged'), False)

    cap_drop = host.get('CapDrop') or []
    add('cap-drop-all', 'ALL' in cap_drop, cap_drop, ['ALL'])

    security_opt = host.get('SecurityOpt') or []
    add('no-new-privileges',
        any('no-new-privileges' in str(option) for option in security_opt),
        security_opt, 'no-new-privileges')

    add('pids-limit', isinstance(host.get('PidsLimit'), int) and host.get('PidsLimit') > 0,
        host.get('PidsLimit'), '> 0')
    add('memory', isinstance(host.get('Memory'), int) and host.get('Memory') > 0,
        host.get('Memory'), '> 0')

    network = host.get('NetworkMode')
    expected_network = 'none' if mode == 'import' else 'bridge'
    add('network', network == expected_network, network, expected_network)

    if mode == 'import':
        add('readonly-root', host.get('ReadonlyRootfs') is True, host.get('ReadonlyRootfs'), True)
        add('user', config.get('User') in ('65534:65534', '65534'), config.get('User'), '65534:65534')
    else:
        # 生成沙箱的用户来自镜像 USER 指令，Config.User 为空时由 docker exec id -u 单独核对。
        add('user-field', True, config.get('User'), '由容器内 uid 核对')

    mounts = data.get('Mounts') or []
    targets = sorted(mount.get('Destination') for mount in mounts if mount.get('Destination'))
    if mode == 'import':
        add('mounts', set(targets) <= {'/work', '/input', '/glyahh/guard.sh'}, targets,
            '仅工作区、输入与守卫脚本')
    else:
        add('mounts', targets == ['/workspace'], targets, ['/workspace'])

    return checks


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--inspect', type=Path, required=True)
    parser.add_argument('--mode', choices=('generate', 'import'), required=True)
    parser.add_argument('--uid', type=int)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()

    data = json.loads(args.inspect.read_text(encoding='utf-8'))
    if isinstance(data, list):
        data = data[0]
    checks = evaluate(data, args.mode)

    # 生成模式必须取得实际 uid；拿不到就不能声称运行用户已核对。
    if args.mode == 'generate':
        checks.append({'name': 'runtime-uid', 'ok': args.uid is not None and args.uid != 0,
                       'actual': args.uid, 'expected': '!= 0'})
    elif args.uid is not None:
        checks.append({'name': 'runtime-uid', 'ok': args.uid != 0, 'actual': args.uid,
                       'expected': '!= 0'})

    failed = [check for check in checks if not check['ok']]
    report = {'mode': args.mode, 'ok': not failed, 'checks': checks}
    text = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding='utf-8')
    print(text)
    return 1 if failed else 0


if __name__ == '__main__':
    raise SystemExit(main())
