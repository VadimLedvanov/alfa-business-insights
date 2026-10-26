"""Shared pytest hooks for ML integration-test diagnostics."""

from __future__ import annotations

from pathlib import Path

import allure
import pytest


@pytest.hookimpl(hookwrapper=True)
def pytest_runtest_makereport(item: pytest.Item, call: pytest.CallInfo[None]):
    """Прикладывает хвост лога сервера к упавшему тесту."""
    outcome = yield
    report = outcome.get_result()
    if not report.failed:
        return

    log_path = getattr(item.config, "_ml_server_log", None)
    process = getattr(item.config, "_ml_server_process", None)
    log_tail = ""
    if log_path is not None and Path(log_path).exists():
        log_tail = Path(log_path).read_text(encoding="utf-8", errors="replace")[-8000:]
        log_tail = log_tail.replace(str(item.config.rootpath), "<ml-root>")
    exit_code = None if process is None else process.poll()
    allure.attach(
        f"Упал этап {report.when} теста {item.name}.\n"
        f"Код завершения uvicorn: {exit_code}\n\n{log_tail}",
        name="Диагностика локального ML-сервера",
        attachment_type=allure.attachment_type.TEXT,
    )
