"""导入POSIX策略与Windows兼容边界。"""
import shlex
import pytest

from backends.permissions import ImportCommandRejected, CommandRejected, prepare_import_command, ensure_safe_command
from prompt import get_system_prompt


@pytest.mark.parametrize('argument', [
    "from pathlib import Path; print('中文 type 保留')",
    'print("嵌套引号与 空格")',
    'print("a|b>c;d")',
    'print("$HOME")',
])
def test_quoted_python_argument_is_preserved(tmp_path, argument):
    command = shlex.join(['python3', '-c', argument])
    assert shlex.split(prepare_import_command(command, tmp_path)) == ['python3', '-c', argument]


@pytest.mark.parametrize('command,reason', [
    ('echo ok; echo bad', 'OPERATORS'), ('echo ok&&echo bad', 'OPERATORS'),
    ('echo ok | python3', 'OPERATORS'), ('echo ok > x', 'OPERATORS'),
    ('echo ok < x', 'OPERATORS'), ('echo ok &', 'OPERATORS'),
    ('echo ok\necho bad', 'OPERATORS'), ('echo $(echo bad)', 'SUBSTITUTION'),
    ('echo "$(echo bad)"', 'SUBSTITUTION'), ('echo `echo bad`', 'SUBSTITUTION'),
    ('python3 -c "unterminated', 'QUOTING'), ('echo trailing\\', 'QUOTING'),
    ('python3 ../other.py', 'PATH'), ('python3 /outside.py', 'PATH'),
    ('bash script.sh', 'EXECUTABLE'), ('python3 "rm -rf"', 'DANGEROUS'),
    ('', 'EMPTY'), ('""', 'EXECUTABLE'),
])
def test_shell_operators_and_bad_paths_are_refused(tmp_path, command, reason):
    with pytest.raises(ImportCommandRejected) as error:
        prepare_import_command(command, tmp_path)
    assert error.value.reason == reason
    assert str(error.value).startswith('IMPORT_COMMAND_REJECTED:' + reason)
    assert command not in str(error.value) or not command


def test_only_executable_alias_is_changed(tmp_path):
    assert shlex.split(prepare_import_command('type "中文 空格.txt"', tmp_path)) == ['cat', '中文 空格.txt']
    argument = "print('type text; dir text')"
    assert shlex.split(prepare_import_command(shlex.join(['python3', '-c', argument]), tmp_path))[-1] == argument


def test_escaped_operator_is_literal_and_legacy_policy_is_unchanged(tmp_path):
    assert shlex.split(prepare_import_command(r'echo literal\;value', tmp_path)) == ['echo', 'literal;value']
    with pytest.raises(CommandRejected):
        ensure_safe_command('python3 -c "print(1); print(2)"', tmp_path)


def test_only_import_prompt_uses_linux():
    assert 'shell 是 cmd.exe' in get_system_prompt()
    prompt = get_system_prompt(import_edit=True)
    assert '运行环境是Linux容器' in prompt and '使用python3' in prompt
    assert 'shell 是 cmd.exe' not in prompt and '不要用 Unix 命令' not in prompt
