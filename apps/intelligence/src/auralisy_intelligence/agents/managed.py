"""Graceful Windows shutdown through the supervisor's inherited stdin pipe."""

import asyncio
import sys
import threading

from aiohttp import web


def manage_stdin(app: web.Application) -> None:
    async def started(app):
        loop = asyncio.get_running_loop()

        def stop():
            raise web.GracefulExit()

        def watch():
            # EOF also closes the service if its supervisor crashes.
            sys.stdin.readline()
            if not loop.is_closed():
                loop.call_soon_threadsafe(stop)

        threading.Thread(target=watch, name="auralis-process-owner", daemon=True).start()

    app.on_startup.append(started)
