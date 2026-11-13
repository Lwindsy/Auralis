"""Real aiohttp shutdown on stdin EOF, including pending resource cleanup."""

import os
import subprocess
import sys
from pathlib import Path


def test_managed_eof_runs_aiohttp_cleanup(tmp_path):
    cleaned = tmp_path / "cleaned"
    script = """
import sys
from pathlib import Path
from aiohttp import web
from auralisy_intelligence.agents.managed import manage_stdin
app = web.Application()
async def ready(app):
    print('READY', flush=True)
async def cleanup(app):
    Path(sys.argv[1]).write_text('cleaned')
app.on_startup.append(ready)
app.on_cleanup.append(cleanup)
manage_stdin(app)
web.run_app(app, host='127.0.0.1', port=0, print=None, access_log=None)
"""
    env = dict(os.environ, PYTHONPATH=str(Path(__file__).parents[1] / "src"))
    process = subprocess.Popen(
        [sys.executable, "-u", "-c", script, str(cleaned)],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
    )
    try:
        assert process.stdout.readline().strip() == b"READY"
        process.stdin.close()
        assert process.wait(timeout=10) == 0
        assert cleaned.read_text() == "cleaned"
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=5)
        process.stdout.close()
        process.stderr.close()


def test_startup_diagnostics_distinguish_failures_without_exposing_details(tmp_path):
    env = dict(os.environ, PYTHONPATH=str(Path(__file__).parents[1] / "src"))
    missing = subprocess.run(
        [
            sys.executable,
            "-m",
            "auralisy_intelligence.agents",
            "serve",
            "--config",
            str(tmp_path / "private-config.json"),
        ],
        capture_output=True,
        text=True,
        env=env,
        timeout=10,
    )
    assert missing.returncode == 1
    assert missing.stderr.strip() == "AURALIS_AGENT_STARTUP_FAILED FileNotFoundError"
    script = """
import logging, sys
from auralisy_intelligence.agents import __main__ as cli
sys.argv = ['auralis-agent', 'serve', '--config', 'unused']
def fail(args):
    logging.getLogger('httpx').error('private-provider-body')
    logging.getLogger('auralisy_intelligence.agents').warning('SERVICE_CONFIGURATION_FAILED')
    raise ValueError(sys.argv_saved)
cli.serve = fail
sys.argv_saved = sys.argv_original
raise SystemExit(cli.main())
"""
    for detail, expected in [
        ("MODEL_QUOTA_NOT_CONFIGURED", "MODEL_QUOTA_NOT_CONFIGURED"),
        ("private-secret", "ValueError"),
    ]:
        result = subprocess.run(
            [
                sys.executable,
                "-c",
                "import sys; sys.argv_original = " + repr(detail) + "\n" + script,
            ],
            capture_output=True,
            text=True,
            env=env,
            timeout=10,
        )
        assert result.returncode == 1
        assert "AURALIS_AGENT_STARTUP_FAILED " + expected in result.stderr
        assert "SERVICE_CONFIGURATION_FAILED" in result.stderr
        assert "private-" not in result.stderr
