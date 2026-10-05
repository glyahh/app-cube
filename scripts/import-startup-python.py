"""平台只读解析器：输入是封存文本，工程模块永不 import。"""
import ast
import json
import hashlib
import re
import sys
import tomllib
from pathlib import PurePosixPath

IGNORED = {"node_modules", "vendor", ".venv", "venv", "dist", "target", "build", ".git", ".glyahh", "examples", "example", "tests", "test", "fixtures", "__pycache__"}


def analyse(files, directory):
    prefix = "" if directory == "." else directory + "/"
    candidates = []
    scopes = []
    phase = "FIXED"
    def diagnostic(phase, status, code=None):
        scopes.append({"directory": directory, "phase": phase, "status": status, "diagnostic": code})
    def candidate(name, command, source, evidence):
        candidates.append({"id": prefix + "python:" + name + "@" + evidence.split("#", 1)[0], "name": name, "directory": directory, "command": command, "build": None, "source": source, "evidence": evidence})
    try:
        config = tomllib.loads(files[prefix + "pyproject.toml"]) if prefix + "pyproject.toml" in files else {}
        project = config.get("project", {})
        if not isinstance(project, dict): raise ValueError()
        scripts = project.get("scripts", {})
        if not isinstance(scripts, dict): raise ValueError()
        dependencies = project.get("dependencies", [])
        if not isinstance(dependencies, list) or any(not isinstance(item, str) for item in dependencies): raise ValueError()
        requirements = files.get(prefix + "requirements.txt", "")
        if any(line.strip().startswith(("-r", "--requirement", "-c", "--constraint", "-e", "--editable")) for line in requirements.splitlines()):
            diagnostic("FIXED", "INCOMPLETE", "PYTHON_DEPENDENCY_INDIRECTION")
            return {"candidates": [], "scopes": scopes}
        dependencies = dependencies + [line.strip() for line in requirements.splitlines() if line.strip() and not line.lstrip().startswith("#")]
        names = {re.split(r"[\s\[<>=!~;]", item, maxsplit=1)[0].lower().replace("_", "-") for item in dependencies}
        trees = {}; node_count = 0; phase = "ALGORITHM"
        for path, content in sorted(files.items()):
            if not path.startswith(prefix) or not path.endswith(".py"): continue
            relative = path[len(prefix):]
            if any(part.lower() in IGNORED for part in PurePosixPath(relative).parts): continue
            # 有独立配置的嵌套工程由其自身范围识别，不借父工程生成重复服务。
            parents = list(PurePosixPath(path).parents)
            if any(str(parent) != directory and str(parent) != "." and (str(parent) + "/pyproject.toml" in files or str(parent) + "/requirements.txt" in files) for parent in parents): continue
            if len(content.encode("utf-8")) > 262144: raise OverflowError()
            trees[path] = ast.parse(content, filename=path)
            node_count += sum(1 for _ in ast.walk(trees[path]))
            if node_count > 100000: raise OverflowError()
        # 1. 显式 console script 先验证模块与顶层函数，保存声明而不执行。
        for name, target in sorted(scripts.items()):
            if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,79}", name) or not isinstance(target, str): raise ValueError()
            if not re.fullmatch(r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*:[A-Za-z_]\w*", target): raise ValueError()
            module, function = target.split(":")
            locations = [prefix + module.replace(".", "/") + ".py", prefix + "src/" + module.replace(".", "/") + ".py", prefix + module.replace(".", "/") + "/__init__.py", prefix + "src/" + module.replace(".", "/") + "/__init__.py"]
            found = [path for path in locations if path in trees]
            if len(found) != 1 or not any(isinstance(node, ast.FunctionDef) and node.name == function for node in trees[found[0]].body): raise ValueError()
            candidate(name, name, "DECLARED", prefix + "pyproject.toml#project.scripts." + name + " + " + found[0])
        if candidates:
            diagnostic("FIXED", "AMBIGUOUS" if len(candidates) > 1 else "IDENTIFIED", "MULTIPLE_PYTHON_ENTRIES" if len(candidates) > 1 else None)
            return {"candidates": candidates, "scopes": scopes}
        diagnostic("FIXED", "UNIDENTIFIED", "NO_DECLARED_PYTHON_ENTRY")
        # 2. AST 识别框架导入与顶层实例；字符串、注释和函数内示例不冒充入口。
        for path, tree in trees.items():
            imports = {}
            for node in tree.body:
                if isinstance(node, ast.ImportFrom) and node.level == 0 and node.module in {"fastapi", "flask"}:
                    for alias in node.names: imports[alias.asname or alias.name] = (node.module, alias.name)
                elif isinstance(node, ast.Import):
                    for alias in node.names:
                        if alias.name in {"fastapi", "flask"}: imports[alias.asname or alias.name] = (alias.name, None)
            relative = path[len(prefix):]
            module = relative[:-3].replace("/", ".")
            if module.startswith("src."): module = module[4:]
            if not re.fullmatch(r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*", module): continue
            for node in tree.body:
                value = node.value if isinstance(node, (ast.Assign, ast.AnnAssign)) else None
                targets = node.targets if isinstance(node, ast.Assign) else [node.target] if isinstance(node, ast.AnnAssign) else []
                if not isinstance(value, ast.Call): continue
                symbol = imports.get(value.func.id) if isinstance(value.func, ast.Name) else (imports.get(value.func.value.id, (None, None))[0], value.func.attr) if isinstance(value.func, ast.Attribute) and isinstance(value.func.value, ast.Name) else None
                for target in targets:
                    if not isinstance(target, ast.Name): continue
                    if symbol == ("fastapi", "FastAPI") and {"fastapi", "uvicorn"} <= names:
                        option = " --app-dir src" if relative.startswith("src/") else ""
                        candidate(module + ":" + target.id, "python -m uvicorn " + module + ":" + target.id + option + " --host 0.0.0.0", "HEURISTIC", path + "#" + target.id + " + dependency declarations")
                    elif symbol == ("flask", "Flask") and "flask" in names and not relative.startswith("src/"):
                        candidate(module + ":" + target.id, "python -m flask --app " + module + ":" + target.id + " run --host 0.0.0.0", "HEURISTIC", path + "#" + target.id + " + dependency declarations")
        diagnostic("ALGORITHM", "AMBIGUOUS" if len(candidates) > 1 else "IDENTIFIED" if candidates else "UNIDENTIFIED", "MULTIPLE_PYTHON_ENTRIES" if len(candidates) > 1 else None if candidates else "NO_SUPPORTED_PYTHON_ENTRY")
    except OverflowError:
        candidates = []; scopes = []; diagnostic("ALGORITHM", "INCOMPLETE", "PYTHON_INPUT_LIMIT")
    except (ValueError, SyntaxError, TypeError, RecursionError, MemoryError):
        candidates = []; scopes = []; diagnostic(phase, "FAILED", "PYTHON_CONFIGURATION_OR_SOURCE_INVALID")
    return {"candidates": candidates, "scopes": scopes}


def main():
    # 仅打开平台传入的输入文件，不从文件内任何路径加载内容或模块。
    with open(sys.argv[1], "rb") as stream: payload = stream.read(4194305)
    if len(payload) > 4194304: raise ValueError("input limit")
    request = json.loads(payload)
    files = request["files"]
    if not isinstance(files, dict) or len(files) > 10000 or any(not isinstance(key, str) or not isinstance(value, str) for key, value in files.items()): raise ValueError("input type")
    result = {directory: analyse(files, directory) for directory in request["directories"]}
    print(json.dumps({"inputHash": hashlib.sha256(payload).hexdigest(), "results": result}, ensure_ascii=True))


if __name__ == "__main__":
    main()
