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
from auralis_intelligence.agents.managed import manage_stdin
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
