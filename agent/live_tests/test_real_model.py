"""仅由 .github/workflows/real-ai.yml 手动触发；普通 PR CI 不会收集本目录。"""
from __future__ import annotations

from langchain_core.messages import HumanMessage

from core.model import make_main_model
from core.settings import load_settings


def test_configured_model_returns_non_empty_text() -> None:
    settings = load_settings()
    assert settings.model.api_key, "AGENT_MODEL_API_KEY is required for the manual real-AI job"
    response = make_main_model(settings.model).invoke(
        [HumanMessage(content="只回复 OK，不要执行任何工具。")]
    )
    content = response.content
    assert isinstance(content, str) and content.strip(), "real model returned empty content"
