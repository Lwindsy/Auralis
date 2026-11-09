"""Explicit single-host Windows lifecycle. No shell, PID reuse, or implicit data source."""

import argparse
import contextlib
import hashlib
import json
import os
import re
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

IMAGE = "postgres:18.6-bookworm"
FLAGS = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
BASE_ENV = ("PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "USERPROFILE", "COMSPEC")
SECRETS = ("AURALIS_DATABASE_PASSWORD", "AURALIS_SERVICE_TOKEN", "AURALIS_SUBJECT_SECRET")


class Failure(Exception):
    pass


def require(condition, code):
    if not condition:
        raise Failure(code)


def secret(name):
    value = os.environ.get(name, "")
    require(bool(value) and "\x00" not in value, "MISSING_ENVIRONMENT_SECRET")
    return value


def environment(names=()):
    result = {key: os.environ[key] for key in BASE_ENV if key in os.environ}
    result.update({key: secret(key) for key in names})
    return result


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def digest(path):
    value = hashlib.sha256()
    with open(path, "rb") as stream:
        while chunk := stream.read(1024 * 1024):
            value.update(chunk)
    return value.hexdigest()


class Host:
    def __init__(self, path):
        self.path = Path(path).resolve()
        self.config = read_json(self.path)
        c = self.config
        require(re.fullmatch(r"auralis-[a-z0-9-]{1,40}", c["name"]), "INVALID_INSTANCE_NAME")
        require(c["database"] in {"auralis_core", "auralis_core_verify"}, "INVALID_DATABASE")
        ports = [c[k] for k in ("database_port", "java_port", "python_port")]
        require(all(type(p) is int and 1024 <= p <= 65535 for p in ports), "INVALID_PORT")
        require(len(set(ports)) == 3, "PORT_COLLISION")
        require(256 <= c["java_heap_mb"] <= 8192, "INVALID_HEAP")
        self.name = c["name"]

    def file(self, key):
        path = (self.path.parent / self.config[key]).resolve()
        require(path.is_file(), "MISSING_" + key.upper())
        return str(path)

    @property
    def jdbc(self):
        return (
            f"jdbc:postgresql://127.0.0.1:{self.config['database_port']}/{self.config['database']}"
        )

    def call(self, args, *, data=None, env=None, timeout=120, output=None, source=None):
        result = subprocess.run(
            args,
            input=data,
            stdin=source,
            stdout=output or subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=env or environment(),
            timeout=timeout,
            creationflags=FLAGS,
        )
        require(result.returncode == 0, "COMMAND_FAILED")
        return result.stdout

    def docker(self, *args, **kwargs):
        return self.call(["docker", *args], **kwargs)

    def owned(self):
        info = json.loads(self.docker("inspect", self.name))[0]
        require(
            (info["Config"].get("Labels") or {}).get("io.auralis.host") == self.name,
            "FOREIGN_CONTAINER",
        )
        require(info["Config"]["Image"] == IMAGE, "DATABASE_VERSION_MISMATCH")
        require(
            info["HostConfig"]["PortBindings"].get("5432/tcp")
            == [{"HostIp": "127.0.0.1", "HostPort": str(self.config["database_port"])}],
            "DATABASE_PORT_BINDING_MISMATCH",
        )
        require(
            any(
                mount.get("Type") == "volume"
                and mount.get("Name") == self.name + "-data"
                and mount.get("Destination") == "/var/lib/postgresql"
                for mount in info["Mounts"]
            ),
            "DATABASE_VOLUME_MISMATCH",
        )
        return info

    def sql(self, sql, database=None):
        self.owned()
        return self.docker(
            "exec",
            "-i",
            self.name,
            "psql",
            "-X",
            "-v",
            "ON_ERROR_STOP=1",
            "-U",
            "postgres",
            "-d",
            database or self.config["database"],
            data=sql.encode(),
        )

    def scalar(self, sql):
        self.owned()
        return (
            self.docker(
                "exec",
                "-i",
                self.name,
                "psql",
                "-X",
                "-At",
                "-v",
                "ON_ERROR_STOP=1",
                "-U",
                "postgres",
                "-d",
                self.config["database"],
                data=sql.encode(),
            )
            .decode()
            .strip()
        )

    def stopped(self):
        for key in ("java_port", "python_port"):
            with socket.socket() as probe:
                probe.settimeout(0.2)
                require(
                    probe.connect_ex(("127.0.0.1", self.config[key])) != 0,
                    "STOP_SERVICES_FOR_MAINTENANCE",
                )

    def database_up(self):
        # Reuse only an explicitly labelled container; never adopt a pre-existing volume.
        found = self.docker("ps", "-a", "--filter", f"name=^/{self.name}$", "--format", "{{.ID}}")
        if found.strip():
            self.owned()
            self.docker("start", self.name)
        else:
            volumes = self.docker(
                "volume", "ls", "--filter", f"name=^{self.name}-data$", "--format", "{{.Name}}"
            )
            require(not volumes.strip(), "EXISTING_VOLUME_REQUIRES_EXPLICIT_RECOVERY")
            env = environment(("AURALIS_POSTGRES_PASSWORD",))
            env["POSTGRES_PASSWORD"] = env.pop("AURALIS_POSTGRES_PASSWORD")
            self.docker(
                "run",
                "-d",
                "--name",
                self.name,
                "--label",
                f"io.auralis.host={self.name}",
                "--publish",
                f"127.0.0.1:{self.config['database_port']}:5432",
                "--mount",
                f"type=volume,src={self.name}-data,dst=/var/lib/postgresql",
                "--memory",
                "1g",
                "--cpus",
                "2",
                "--pids-limit",
                "128",
                "--log-driver",
                "local",
                "--log-opt",
                "max-size=10m",
                "--log-opt",
                "max-file=2",
                "--env",
                "POSTGRES_PASSWORD",
                IMAGE,
                "-c",
                "log_statement=none",
                "-c",
                "log_min_error_statement=panic",
                "-c",
                "log_parameter_max_length_on_error=0",
                env=env,
            )
        for _ in range(60):
            try:
                self.docker("exec", self.name, "pg_isready", "-U", "postgres", timeout=3)
                return
            except Failure:
                time.sleep(0.5)
        raise Failure("DATABASE_NOT_READY")

    def initialize(self):
        self.stopped()

        # psql input goes over stdin, never a command-line argument or a credential file.
        def quoted(value):
            return "'" + value.replace("'", "''") + "'"

        self.sql(
            "CREATE ROLE auralis_migrator LOGIN PASSWORD "
            + quoted(secret("AURALIS_MIGRATOR_PASSWORD"))
            + ";\n"
            + "CREATE ROLE auralis_online LOGIN PASSWORD "
            + quoted(secret("AURALIS_DATABASE_PASSWORD"))
            + ";\n"
            + f"CREATE DATABASE {self.config['database']} OWNER auralis_migrator;\n"
            + f"REVOKE ALL ON DATABASE {self.config['database']} FROM PUBLIC;\n"
            + f"GRANT CONNECT ON DATABASE {self.config['database']} TO auralis_online;\n",
            "postgres",
        )
        self.sql("REVOKE CREATE ON SCHEMA public FROM PUBLIC;")

    def migrate(self):
        self.stopped()
        env = environment(("AURALIS_MIGRATOR_PASSWORD",))
        env.update(AURALIS_JDBC_URL=self.jdbc, AURALIS_MIGRATOR_USER="auralis_migrator")
        self.call([self.file("java"), "-jar", self.file("jar"), "--offline-migrate"], env=env)
        self.sql(
            "GRANT USAGE ON SCHEMA catalog,ingestion,statistics,discovery TO auralis_online;"
            "GRANT SELECT ON ALL TABLES IN SCHEMA catalog,ingestion,statistics TO auralis_online;"
            "GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA discovery TO auralis_online;"
            "GRANT SELECT ON public.flyway_schema_history TO auralis_online;"
            "REVOKE INSERT,UPDATE,DELETE ON discovery.audience_snapshot,discovery.audience_count FROM auralis_online;"
        )

    def preflight(self):
        c = self.config
        for key in ("java", "python", "jar", "agent_config", "recommendation_config"):
            self.file(key)
        require(self.search_index().is_dir(), "MISSING_SEARCH_INDEX")
        require(re.fullmatch("[a-f0-9]{64}", c["search_manifest_sha256"]), "SEARCH_DIGEST_REQUIRED")
        version = subprocess.run(
            [self.file("java"), "-version"],
            capture_output=True,
            env=environment(),
            timeout=10,
            creationflags=FLAGS,
        )
        require(
            version.returncode == 0 and re.search(rb'version "21\.', version.stderr),
            "JAVA_21_REQUIRED",
        )
        self.call(
            [
                self.file("python"),
                "-c",
                "import sys; assert sys.version_info[:3] == (3, 10, 8); import auralis_intelligence, aiohttp",
            ],
            env=self.agent_environment(),
        )
        self.stopped()
        agent = read_json(self.file("agent_config"))
        for key in ("reference", "recall_snapshot", "model_bindings", "audience_snapshot"):
            p = (Path(self.file("agent_config")).parent / agent[key]).resolve()
            require(p.exists(), "MISSING_AGENT_ARTIFACT")
        require(
            agent["control_url"] == f"http://127.0.0.1:{c['java_port']}", "CONTROL_URL_MISMATCH"
        )
        require(
            agent["service_token_env"] == "AURALIS_SERVICE_TOKEN"
            and agent["control_token_env"] == "AURALIS_SERVICE_TOKEN"
            and agent["provider_key_env"] == "AURALIS_PRODUCT_TEST_API_KEY",
            "SECRET_SCOPE_MISMATCH",
        )
        require(agent.get("evidence_mode") == "live", "LIVE_CONFIGURATION_REQUIRED")
        require(
            "audience_binding" in agent and "service_resources" in agent, "MISSING_AGENT_BINDING"
        )
        backend = read_json(self.file("recommendation_config"))
        audience = backend["audience_binding"]
        require(
            {
                "dataset_id": audience["dataset"],
                "window": audience["window"],
                "sample_listeners": audience["sampleListeners"],
                "snapshot_digest": audience["snapshotDigest"],
            }
            == agent["audience_binding"],
            "AUDIENCE_BINDING_MISMATCH",
        )
        require(
            re.fullmatch(r"sha256:[a-f0-9]{64}", audience["snapshotDigest"]),
            "INVALID_AUDIENCE_DIGEST",
        )
        present = self.sql(
            "SELECT EXISTS(SELECT 1 FROM discovery.audience_snapshot WHERE active AND id='"
            + audience["snapshotDigest"]
            + "') AND EXISTS(SELECT 1 FROM ingestion.active_dataset);"
        )
        require(re.search(rb"\n\s*t\s*\n", present), "CATALOG_OR_AUDIENCE_NOT_LOADED")
        require("budget_limits" in backend and "prices" in backend, "EXPLICIT_BUDGET_REQUIRED")
        for key in (*SECRETS, "AURALIS_PRODUCT_TEST_API_KEY"):
            secret(key)
        self.owned()
        if self.scalar("SELECT to_regclass('public.auralis_restore_gate') IS NOT NULL;") == "t":
            recovery = json.loads(
                self.scalar("SELECT state FROM public.auralis_restore_gate WHERE singleton;")
            )
            require(recovery.get("complete") is True, "RESTORE_INCOMPLETE")
            require(recovery.get("released") is True, "RESTORE_RECONCILIATION_REQUIRED")
            require(
                recovery["subject_generation"]
                == hashlib.sha256(secret("AURALIS_SUBJECT_SECRET").encode()).hexdigest(),
                "RESTORE_SECRET_MISMATCH",
            )

    def agent_environment(self):
        env = environment(("AURALIS_SERVICE_TOKEN", "AURALIS_PRODUCT_TEST_API_KEY"))
        env["PYTHONPATH"] = str(Path(__file__).resolve().parents[1] / "intelligence" / "src")
        env.update(OPENBLAS_NUM_THREADS="1", OMP_NUM_THREADS="1", MKL_NUM_THREADS="1")
        return env

    def status(self):
        # Fixed, aggregate-only diagnostics: no request texts, credentials or evidence bodies.
        print(
            self.sql(
                "SELECT maintenance FROM discovery.runtime_control;"
                "SELECT status,count(*) FROM discovery.discovery_intent GROUP BY status;"
                "SELECT state,count(*) FROM discovery.execution_slot GROUP BY state;"
                "SELECT outcome,count(*),sum(reserved_microusd),sum(settled_microusd) FROM discovery.invocation GROUP BY outcome;"
            ).decode()
        )

    def search_index(self):
        return (self.path.parent / self.config["search_index"]).resolve()

    def run(self, managed_stdin=False):
        self.preflight()
        c = self.config
        backend = environment(SECRETS)
        backend.update(
            AURALIS_JDBC_URL=self.jdbc,
            AURALIS_DATABASE_USER="auralis_online",
            AURALIS_PRODUCT_ORIGIN=f"http://127.0.0.1:{c['java_port']}",
            AURALIS_PYTHON_URL=f"http://127.0.0.1:{c['python_port']}",
            AURALIS_CONTRACT_REVISION="0.31",
            AURALIS_ALLOW_INSECURE_COOKIE="true",
            AURALIS_MANAGED_STDIN="true",
            AURALIS_DISPATCH_ENABLED="true",
            AURALIS_RECOMMENDATION_CONFIG=self.file("recommendation_config"),
            AURALIS_CATALOG_SEARCH_INDEX=str(self.search_index()),
            AURALIS_CATALOG_SEARCH_MANIFEST_SHA256=c["search_manifest_sha256"],
            SERVER_PORT=str(c["java_port"]),
        )
        if c.get("curated_content_directory"):
            prepared = (self.path.parent / c["curated_content_directory"]).resolve()
            require(prepared.is_dir(), "MISSING_CURATED_CONTENT")
            backend["AURALIS_CURATED_CONTENT_DIRECTORY"] = str(prepared)
        agent_env = self.agent_environment()
        children = []
        stopping = threading.Event()
        if managed_stdin:

            def watch_owner():
                sys.stdin.readline()
                stopping.set()

            threading.Thread(target=watch_owner, daemon=True).start()
        try:
            agent = Child(
                [
                    self.file("python"),
                    "-m",
                    "auralis_intelligence.agents",
                    "serve",
                    "--config",
                    self.file("agent_config"),
                    "--port",
                    str(c["python_port"]),
                    "--managed-stdin",
                ],
                agent_env,
            )
            children.append(agent)
            self.wait_ready(c["python_port"], "/internal/v1/runtime", children, authenticated=True)
            java = Child(
                [self.file("java"), f"-Xmx{c['java_heap_mb']}m", "-jar", self.file("jar")], backend
            )
            children.append(java)
            self.wait_ready(c["java_port"], "/actuator/health/readiness", children)
            print("AURALIS_HOST_READY", flush=True)
            while not stopping.is_set() and all(child.process.poll() is None for child in children):
                time.sleep(0.5)
            if not stopping.is_set():
                raise Failure("SERVICE_EXITED")
        except KeyboardInterrupt:
            pass
        finally:
            if len(children) == 2 and children[1].process.poll() is None:
                children[1].send("MAINTENANCE\n")
                children[1].maintenance.wait(12)
                # If maintenance cannot be acknowledged, stop Java before cancelling Python.
                if not children[1].maintenance.is_set():
                    children[1].close()
            for child in children:  # Python cleans up while Java still accepts known receipts.
                child.close()

    def wait_ready(self, port, route, children, authenticated=False):
        deadline = time.monotonic() + 180
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        while time.monotonic() < deadline:
            require(all(c.process.poll() is None for c in children), "SERVICE_START_FAILED")
            headers = {}
            if authenticated:
                headers = {
                    "Authorization": "Bearer " + secret("AURALIS_SERVICE_TOKEN"),
                    "X-Auralis-Audience": "intelligence",
                }
            request = urllib.request.Request(f"http://127.0.0.1:{port}{route}", headers=headers)
            try:
                with opener.open(request, timeout=2) as response:
                    body = json.loads(response.read(65537))
                    if authenticated:
                        if (
                            body.get("contract_revision") == "0.31"
                            and body["payload"]["status"] == "READY"
                        ):
                            return
                    elif body.get("status") == "UP":
                        return
            except (OSError, ValueError, KeyError):
                pass
            time.sleep(0.5)
        raise Failure("READINESS_TIMEOUT")


class Child:
    def __init__(self, args, env):
        self.maintenance = threading.Event()
        self.process = subprocess.Popen(
            args,
            env=env,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            creationflags=FLAGS,
        )
        self.reader = threading.Thread(target=self.read, daemon=True)
        self.reader.start()

    def read(self):
        # Forward only application diagnostic codes and a bounded category, never arbitrary output.
        with self.process.stdout as stream:
            while line := stream.readline(4096):
                if line.strip() == b"AURALIS_MAINTENANCE":
                    self.maintenance.set()
                diagnostic = re.search(
                    rb"(?:^| )(AURALIS_(?:AGENT_STARTUP_FAILED|STARTUP_FAILED|DISPATCH_FAILED|DISPATCH_LIFECYCLE_FAILED|DISPATCH_UNAVAILABLE_UPDATE_FAILED|AGENT_REQUEST_FAILED) [A-Za-z][A-Za-z0-9_]{0,79})$",
                    line.strip(),
                )
                if diagnostic:
                    print(diagnostic.group(1).decode("ascii"), file=sys.stderr, flush=True)

    def send(self, command):
        with contextlib.suppress(OSError, ValueError):
            self.process.stdin.write(command.encode())
            self.process.stdin.flush()

    def close(self):
        with contextlib.suppress(OSError):
            self.process.stdin.close()
        try:
            self.process.wait(timeout=45)
        except subprocess.TimeoutExpired:
            print("AURALIS_FORCED_STOP_RECOVERY_REQUIRED", flush=True)
            self.process.kill()  # Only the process handle created by this supervisor.
            self.process.wait(timeout=10)
        self.reader.join(timeout=2)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "command", choices=("db-up", "db-stop", "initialize", "migrate", "check", "run", "status")
    )
    parser.add_argument("--config", required=True)
    parser.add_argument("--managed-stdin", action="store_true")
    args = parser.parse_args()
    try:
        host = Host(args.config)
        if args.command == "status":
            host.status()
            return 0
        with exclusive(host.name):
            if args.command == "db-stop":
                host.stopped()
                host.owned()
                host.docker("stop", "--time", "30", host.name)
            elif args.command == "run":
                host.run(args.managed_stdin)
            else:
                getattr(
                    host,
                    {"db-up": "database_up", "check": "preflight"}.get(args.command, args.command),
                )()
        print("AURALIS_OPERATION_OK")
        return 0
    except Failure as failure:
        print(str(failure), file=sys.stderr)
    except (OSError, ValueError, KeyError, subprocess.SubprocessError):
        print("AURALIS_OPERATION_FAILED", file=sys.stderr)
    return 1


@contextlib.contextmanager
def exclusive(name):
    """OS lock releases on abnormal exit; the file's existence is not ownership."""
    path = Path(tempfile.gettempdir()) / (name + ".host.lock")
    with path.open("a+b") as stream:
        stream.seek(0, 2)
        if stream.tell() == 0:
            stream.write(b"0")
            stream.flush()
        stream.seek(0)
        try:
            if os.name == "nt":
                import msvcrt

                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl

                fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as failure:
            raise Failure("HOST_ALREADY_MANAGED") from failure
        try:
            yield
        finally:
            stream.seek(0)
            if os.name == "nt":
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(stream, fcntl.LOCK_UN)


if __name__ == "__main__":
    raise SystemExit(main())
