"""Failure boundaries with real child pipes and isolated files; no provider calls."""

import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stderr
from pathlib import Path
from unittest.mock import patch

from host import Child, Failure, Host, environment, exclusive
from recovery import replay


class HostTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.config = json.loads(Path(__file__).with_name("host.example.json").read_text())

    def host(self):
        path = self.root / "host.json"
        path.write_text(json.dumps(self.config))
        return Host(path)

    def test_invalid_names_and_research_database_rejected(self):
        for field, value in (
            ("name", "other-task"),
            ("database", "resonance_research"),
            ("java_port", 543),
            ("java_port", 18765),
        ):
            with self.subTest(field=field, value=value):
                old = self.config[field]
                self.config[field] = value
                with self.assertRaises(Failure):
                    self.host()
                self.config[field] = old

    def test_environment_does_not_pass_database_or_unrelated_secrets_to_agent(self):
        with patch.dict(
            os.environ,
            {
                "AURALIS_DATABASE_PASSWORD": "private",
                "UNRELATED_KEY": "private",
                "AURALIS_SERVICE_TOKEN": "test",
            },
        ):
            env = environment(("AURALIS_SERVICE_TOKEN",))
            self.assertNotIn("AURALIS_DATABASE_PASSWORD", env)
            self.assertNotIn("UNRELATED_KEY", env)
            self.assertEqual("test", env["AURALIS_SERVICE_TOKEN"])

    def test_owned_rejects_foreign_container(self):
        host = self.host()
        with patch.object(host, "docker", return_value=b'[{"Config":{"Labels":{}}}]'):
            with self.assertRaisesRegex(Failure, "FOREIGN_CONTAINER"):
                host.owned()

    def test_os_lock_is_exclusive_and_released(self):
        name = "auralis-test-" + str(os.getpid())
        with exclusive(name):
            with self.assertRaisesRegex(Failure, "HOST_ALREADY_MANAGED"):
                with exclusive(name):
                    pass
        with exclusive(name):
            pass

    def test_child_eof_is_graceful_and_unrelated_output_is_discarded(self):
        child = Child(
            [
                sys.executable,
                "-u",
                "-c",
                "import sys; print('secret-output'); "
                "assert sys.stdin.readline() == 'MAINTENANCE\\n'; "
                "print('AURALIS_MAINTENANCE', flush=True); "
                "assert sys.stdin.read() == ''; print('secret-output')",
            ],
            environment(),
        )
        try:
            child.send("MAINTENANCE\n")
            self.assertTrue(child.maintenance.wait(5))
        finally:
            child.close()
        self.assertEqual(0, child.process.returncode)

    def test_child_forwards_only_safe_diagnostic_categories(self):
        output = io.StringIO()
        with redirect_stderr(output):
            child = Child(
                [
                    sys.executable,
                    "-u",
                    "-c",
                    "print('secret-output'); print('AURALIS_AGENT_STARTUP_FAILED FileNotFoundError'); "
                    "print('logger WARN AURALIS_DISPATCH_FAILED Unavailable'); "
                    "print('AURALIS_STARTUP_FAILED ValueError private-secret')",
                ],
                environment(),
            )
            child.close()
        self.assertEqual(
            output.getvalue().splitlines(),
            [
                "AURALIS_AGENT_STARTUP_FAILED FileNotFoundError",
                "AURALIS_DISPATCH_FAILED Unavailable",
            ],
        )

    def test_replay_validates_entire_csv_before_writing(self):
        host = self.host()
        path = self.root / "barriers.csv"
        path.write_text(
            "credential_digest,revoked_at,expires_at\n"
            + "sha256:"
            + "a" * 64
            + ",2026-01-01T00:00:00+00:00,2027-01-01T00:00:00+00:00\n"
            + "bad',2026-01-01T00:00:00+00:00,2027-01-01T00:00:00+00:00\n"
        )
        with patch.object(host, "sql") as sql:
            with self.assertRaises(Failure):
                replay(host, path)
            sql.assert_not_called()

    def test_cli_does_not_echo_invalid_configuration(self):
        path = self.root / "private.json"
        path.write_text('{"private-secret":')
        result = subprocess.run(
            [
                sys.executable,
                str(Path(__file__).with_name("host.py")),
                "check",
                "--config",
                str(path),
            ],
            capture_output=True,
        )
        self.assertEqual(1, result.returncode)
        self.assertEqual(b"AURALIS_OPERATION_FAILED", result.stderr.strip())

    def test_replay_accepts_postgresql_timezone_and_is_monotonic(self):
        host = self.host()
        path = self.root / "barriers.csv"
        path.write_text(
            "credential_digest,revoked_at,expires_at\nsha256:"
            + "a" * 64
            + ",2026-11-09 03:52:42.214435+00,2026-12-09 03:52:42.214435+00\n"
        )
        with patch.object(host, "sql") as sql:
            replay(host, path)
            statement = sql.call_args.args[0]
            self.assertIn("GREATEST(subject_revocation.expires_at", statement)
            self.assertIn("2026-11-09T03:52:42.214435+00:00", statement)
            self.assertTrue(statement.startswith("BEGIN;"))


if __name__ == "__main__":
    unittest.main()
