import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("platform_parser", Path(__file__).with_name("import-startup-python.py"))
parser = importlib.util.module_from_spec(spec)
spec.loader.exec_module(parser)


class PythonStartupTests(unittest.TestCase):
    def test_fastapi_alias_and_src_layout(self):
        result = parser.analyse({"requirements.txt": "fastapi\nuvicorn", "src/api.py": "from fastapi import FastAPI as F\napp = F()"}, ".")
        self.assertEqual("python -m uvicorn api:app --app-dir src --host 0.0.0.0", result["candidates"][0]["command"])
        self.assertEqual("HEURISTIC", result["candidates"][0]["source"])

    def test_comments_strings_and_nested_functions_are_not_services(self):
        result = parser.analyse({"requirements.txt": "flask", "app.py": 'from flask import Flask\ntext="app = Flask()"\ndef example():\n app = Flask(__name__)'}, ".")
        self.assertFalse(result["candidates"])

    def test_declared_entry_needs_real_function(self):
        files = {"pyproject.toml": '[project.scripts]\nworker="pkg.main:run"', "pkg/main.py": "def run():\n pass"}
        result = parser.analyse(files, ".")
        self.assertEqual("DECLARED", result["candidates"][0]["source"])
        files["pkg/main.py"] = "run = 1"
        self.assertEqual("FAILED", parser.analyse(files, ".")["scopes"][0]["status"])

    def test_malformed_toml_and_python_fail_without_fallback(self):
        for files in ({"pyproject.toml": "[bad"}, {"requirements.txt": "flask", "app.py": "def :"}):
            result = parser.analyse(files, ".")
            self.assertEqual("FAILED", result["scopes"][0]["status"])
            self.assertFalse(result["candidates"])

    def test_multiple_apps_are_ambiguous(self):
        result = parser.analyse({"requirements.txt": "flask", "app.py": "import flask as f\na=f.Flask('a')\nb=f.Flask('b')"}, ".")
        self.assertEqual(2, len(result["candidates"]))
        self.assertEqual("AMBIGUOUS", result["scopes"][-1]["status"])

    def test_library_and_missing_runner_dependency_do_not_guess_main(self):
        for source in ("print('library')", "from fastapi import FastAPI\napp=FastAPI()"):
            result = parser.analyse({"requirements.txt": "fastapi", "main.py": source}, ".")
            self.assertFalse(result["candidates"])

    def test_dependency_sample_and_child_project_are_not_parent_entries(self):
        source = "from flask import Flask\napp=Flask(__name__)"
        result = parser.analyse({"requirements.txt": "flask", "tests/app.py": source, "sub/requirements.txt": "flask", "sub/app.py": source}, ".")
        self.assertFalse(result["candidates"])
        self.assertEqual(1, len(parser.analyse({"sub/requirements.txt": "flask", "sub/app.py": source}, "sub")["candidates"]))

    def test_project_side_effect_is_never_executed(self):
        source = "raise RuntimeError('would execute')\nfrom flask import Flask\napp=Flask(__name__)"
        self.assertEqual(1, len(parser.analyse({"requirements.txt": "flask", "app.py": source}, ".")["candidates"]))

    def test_invalid_declared_module_does_not_generate_command(self):
        result = parser.analyse({"pyproject.toml": '[project.scripts]\nworker="../outside:run"'}, ".")
        self.assertEqual("FAILED", result["scopes"][0]["status"])

    def test_dependency_indirection_is_incomplete_not_unidentified(self):
        result = parser.analyse({"requirements.txt": "-r ../outside.txt", "app.py": "print('fixture')"}, ".")
        self.assertEqual("INCOMPLETE", result["scopes"][0]["status"])
        self.assertFalse(result["candidates"])

    def test_source_limit_is_not_truncated_success(self):
        result = parser.analyse({"requirements.txt": "flask", "app.py": "#" + "x" * 262144}, ".")
        self.assertEqual("INCOMPLETE", result["scopes"][0]["status"])

    def test_same_module_name_in_root_and_src_has_distinct_candidate_identity(self):
        source = "from fastapi import FastAPI\napp=FastAPI()"
        result = parser.analyse({"requirements.txt": "fastapi\nuvicorn", "app.py": source, "src/app.py": source}, ".")
        self.assertEqual(2, len({candidate["id"] for candidate in result["candidates"]}))
        self.assertEqual("AMBIGUOUS", result["scopes"][-1]["status"])


if __name__ == "__main__":
    unittest.main()
