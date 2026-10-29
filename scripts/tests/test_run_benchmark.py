"""基准评分：受保护文件被改动时必须判失败。"""
import importlib.util
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    'run_benchmark', Path(__file__).parents[1] / 'checks' / 'run-benchmark.py')
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


def test_unchanged_protected_file_passes(tmp_path):
    seed = tmp_path / 'seed'
    seed.mkdir()
    (seed / 'test.mjs').write_text('same', encoding='utf-8')
    workspace = tmp_path / 'work'
    workspace.mkdir()
    (workspace / 'test.mjs').write_text('same', encoding='utf-8')

    assert runner.changed_protected_files({'protected': ['test.mjs']}, seed, workspace) == []


def test_modified_protected_file_is_reported(tmp_path):
    seed = tmp_path / 'seed'
    seed.mkdir()
    (seed / 'test.mjs').write_text('same', encoding='utf-8')
    workspace = tmp_path / 'work'
    workspace.mkdir()
    (workspace / 'test.mjs').write_text('emptied', encoding='utf-8')

    assert runner.changed_protected_files({'protected': ['test.mjs']}, seed, workspace) == ['test.mjs']


def test_deleted_protected_file_is_reported(tmp_path):
    seed = tmp_path / 'seed'
    seed.mkdir()
    (seed / 'test.mjs').write_text('same', encoding='utf-8')
    workspace = tmp_path / 'work'
    workspace.mkdir()

    assert runner.changed_protected_files({'protected': ['test.mjs']}, seed, workspace) == ['test.mjs']


def test_missing_seed_protected_file_is_configuration_error(tmp_path):
    (tmp_path / 'seed').mkdir()
    (tmp_path / 'work').mkdir()

    try:
        runner.changed_protected_files({'protected': ['absent.mjs']}, tmp_path / 'seed', tmp_path / 'work')
    except SystemExit as error:
        assert 'absent.mjs' in str(error)
    else:
        raise AssertionError('缺少 seed 文件时应报配置错误')
