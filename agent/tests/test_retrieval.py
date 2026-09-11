"""检索平面：版本基准、证据索引、上下文投影。

这三块是需求 4（换掉相似度召回）的核心，逻辑不简单，必须有测试。
"""

from __future__ import annotations

import json
import subprocess

import pytest

from retrieval.context_projection import (
    Priority,
    Segment,
    TaskContract,
    compile_projection,
)
from retrieval.git_epoch import snapshot
from retrieval.search_trace import EvidenceLedger, SearchResult, SearchTraceLog


# ═══════════════════════════ Git 基准 ═══════════════════════════

def _init_repo(path):
    def run(*args):
        subprocess.run(["git", *args], cwd=path, capture_output=True, check=True,
                       encoding="utf-8", errors="replace")
    run("init", "-q")
    run("config", "user.email", "t@example.com")
    run("config", "user.name", "t")


def test_untracked_workspace_is_not_an_error(tmp_path):
    """产物目录默认不是仓库 —— 这是常态，不该报错。"""
    epoch = snapshot(tmp_path)
    assert epoch.tracked is False
    assert epoch.label == "untracked"


def test_missing_workspace_reports_untracked(tmp_path):
    epoch = snapshot(tmp_path / "does-not-exist")
    assert epoch.tracked is False


def test_clean_repo_reports_sha_and_branch(tmp_path):
    _init_repo(tmp_path)
    (tmp_path / "a.txt").write_text("x", encoding="utf-8")
    subprocess.run(["git", "add", "."], cwd=tmp_path, capture_output=True, check=True)
    subprocess.run(["git", "commit", "-qm", "init"], cwd=tmp_path, capture_output=True, check=True)

    epoch = snapshot(tmp_path)
    assert epoch.tracked is True
    assert len(epoch.sha) == 40
    assert epoch.dirty is False
    assert epoch.changed_files == []
    assert "+dirty" not in epoch.label


def test_dirty_repo_lists_changed_files(tmp_path):
    _init_repo(tmp_path)
    (tmp_path / "a.txt").write_text("x", encoding="utf-8")
    subprocess.run(["git", "add", "."], cwd=tmp_path, capture_output=True, check=True)
    subprocess.run(["git", "commit", "-qm", "init"], cwd=tmp_path, capture_output=True, check=True)
    (tmp_path / "a.txt").write_text("changed", encoding="utf-8")

    epoch = snapshot(tmp_path)
    assert epoch.dirty is True
    assert "a.txt" in epoch.changed_files
    assert epoch.label.endswith("+dirty")


# ═══════════════════════════ 检索记录 ═══════════════════════════

def test_trace_records_query_results_and_sha():
    log = SearchTraceLog(project_id="html_1", git_sha="abc123def456")
    trace = log.record(
        kind="grep",
        query="loginUserStateVersion",
        scope="src/**",
        results=[SearchResult(path="/src/auth.py", start=41, end=67)],
        relations=["imports", "calls"],
        elapsed_ms=12,
    )

    payload = trace.to_dict()
    assert payload["projectId"] == "html_1"
    assert payload["gitSha"] == "abc123def456"
    assert payload["query"]["kind"] == "grep"
    assert payload["query"]["pattern"] == "loginUserStateVersion"
    assert payload["results"][0]["path"] == "/src/auth.py"
    assert payload["results"][0]["start"] == 41
    assert payload["relationsFollowed"] == ["imports", "calls"]
    assert payload["traceId"].startswith("st_")


def test_trace_id_is_unique_and_time_prefixed():
    log = SearchTraceLog(project_id="p")
    ids = [log.new_trace_id() for _ in range(50)]
    assert len(set(ids)) == 50

    # 毫秒前缀保证按时间有序；同一毫秒内的后缀是随机的，所以只断言前缀非递减
    prefixes = [int(i.split("_")[1]) for i in ids]
    assert prefixes == sorted(prefixes)


def test_trace_is_json_serializable():
    log = SearchTraceLog(project_id="p", git_sha="a" * 40)
    log.record(kind="glob", query="**/*.py", results=[SearchResult("/a.py", 1, 2)])
    assert json.loads(log.all()[0].to_json())["query"]["kind"] == "glob"


def test_negative_checks_are_recorded():
    """「没找到」是一条真结论，不记下来就没法判断当时查没查过。"""
    log = SearchTraceLog(project_id="p")
    trace = log.record(kind="grep", query="x", negative_checks=["src/** 下没有其他写入点"])
    assert trace.to_dict()["negativeChecks"] == ["src/** 下没有其他写入点"]


def test_summary_aggregates():
    log = SearchTraceLog(project_id="p", git_sha="deadbeef")
    log.record(kind="grep", query="a", results=[SearchResult("/a.py")])
    log.record(kind="grep", query="b", results=[SearchResult("/b.py"), SearchResult("/c.py")])
    log.record(kind="glob", query="**/*.py")

    summary = log.summary()
    assert summary["traceCount"] == 3
    assert summary["resultCount"] == 3
    assert summary["byKind"] == {"grep": 2, "glob": 1}
    assert summary["gitSha"] == "deadbeef"


# ═══════════════════════════ 证据台账 ═══════════════════════════

def test_evidence_records_confidence():
    ledger = EvidenceLedger()
    ledger.add("配置在 application.yml 里", "read /src/application.yml", "observed")
    ledger.add("应该还支持环境变量覆盖", "推测", "inferred")

    items = ledger.to_list()
    assert items[0]["confidence"] == "observed"
    assert items[1]["confidence"] == "inferred"


def test_evidence_rejects_unknown_confidence_value():
    """confidence 是受控枚举 —— 写错就报错，不要静默接受。"""
    ledger = EvidenceLedger()
    with pytest.raises(ValueError, match="observed"):
        ledger.add("x", "y", "pretty-sure")


# ═══════════════════════════ 上下文投影 ═══════════════════════════

def seg(path, start, end, text, priority=Priority.REFERENCE, query="q"):
    return Segment(path=path, start_line=start, end_line=end, text=text,
                   source_query=query, git_sha="a" * 40, priority=priority)


def test_projection_includes_contract_first():
    contract = TaskContract(
        objective="把登录状态改成版本号驱动",
        allowed_paths=("/src/**",),
        forbidden_paths=("/node_modules/**",),
        notes=("不要改公共 API",),
    )
    proj = compile_projection(contract, [], budget_chars=1000)
    text = proj.render()

    assert "任务合同" in text
    assert "把登录状态改成版本号驱动" in text
    assert "/src/**" in text
    assert "/node_modules/**" in text
    assert "不要改公共 API" in text


def test_segment_header_carries_path_line_sha_and_query():
    """规则 5：每段保留路径、行号、Git SHA 和来源查询。四样缺一不可。"""
    proj = compile_projection(
        TaskContract(objective="x"),
        [seg("/src/a.py", 10, 20, "code", query="grep:foo")],
        budget_chars=5000,
    )
    header = proj.segments[0].header()
    assert "/src/a.py" in header
    assert "10-20" in header
    assert "a" * 12 in header
    assert "grep:foo" in header


def test_priority_orders_what_survives_budget():
    """规则 6：定义 > 写入点 > 边界校验 > 测试 > 最近变更。"""
    candidates = [
        seg("/a.py", 1, 5, "y" * 400, Priority.REFERENCE),
        seg("/b.py", 1, 5, "z" * 400, Priority.TEST),
        seg("/c.py", 1, 5, "w" * 400, Priority.DEFINITION),
    ]
    proj = compile_projection(TaskContract(objective="x"), candidates, budget_chars=600)

    kept = {s.path for s in proj.segments}
    assert "/c.py" in kept, "定义必须优先保留"
    assert "/a.py" not in kept, "普通引用应最先被丢"


def test_dropped_segments_are_reported():
    """静默截断读起来像「已经覆盖全了」，必须写出来。"""
    candidates = [seg(f"/f{i}.py", 1, 5, "x" * 400) for i in range(10)]
    proj = compile_projection(TaskContract(objective="x"), candidates, budget_chars=800)

    assert proj.dropped, "超预算时应记录被丢的段"
    assert "省略了" in proj.render()
    assert proj.summary()["droppedCount"] == len(proj.dropped)


def test_overlapping_segments_in_same_file_merge():
    """规则 4：同一文件按符号合并，去重复行。"""
    candidates = [
        seg("/a.py", 1, 10, "line1\nline2\nline3"),
        seg("/a.py", 8, 20, "line3\nline4\nline5"),
    ]
    proj = compile_projection(TaskContract(objective="x"), candidates, budget_chars=5000)

    assert len(proj.segments) == 1, "重叠段应合并为一段"
    merged = proj.segments[0]
    assert merged.start_line == 1
    assert merged.end_line == 20
    # line3 在两段里都有，只应出现一次
    assert merged.text.count("line3") == 1


def test_distinct_files_are_not_merged():
    candidates = [seg("/a.py", 1, 5, "a"), seg("/b.py", 1, 5, "b")]
    proj = compile_projection(TaskContract(objective="x"), candidates, budget_chars=5000)
    assert len(proj.segments) == 2


def test_empty_text_segments_are_skipped():
    candidates = [seg("/a.py", 1, 5, "   "), seg("/b.py", 1, 5, "real")]
    proj = compile_projection(TaskContract(objective="x"), candidates, budget_chars=5000)
    assert [s.path for s in proj.segments] == ["/b.py"]


def test_projection_is_reproducible():
    """可重放是 SearchTrace 的要求，投影也应满足：同样的输入给同样的输出。"""
    candidates = [seg("/b.py", 1, 5, "b"), seg("/a.py", 1, 5, "a")]
    first = compile_projection(TaskContract(objective="x"), candidates, budget_chars=5000).render()
    second = compile_projection(TaskContract(objective="x"), candidates, budget_chars=5000).render()
    assert first == second
