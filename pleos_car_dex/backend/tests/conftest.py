import os
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))


@pytest.fixture()
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("DATA_DIR", str(tmp_path))
    monkeypatch.setenv("DETECTOR_MODE", "mock")
    monkeypatch.setenv("IDENTIFIER_MODE", "mock")
    monkeypatch.setenv("USE_MOCK_LLM", "true")

    from app.config import get_settings
    from app.services.pipeline import get_pipeline

    get_settings.cache_clear()
    get_pipeline.cache_clear()

    import importlib

    import app.main

    importlib.reload(app.main)
    from fastapi.testclient import TestClient

    return TestClient(app.main.app)
