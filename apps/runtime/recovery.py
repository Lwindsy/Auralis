"""Offline logical backups, isolated restores and monotonic deletion barrier replay."""

import argparse
import csv
import hashlib
import json
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

from host import Failure, Host, digest, exclusive, read_json, require, secret


def generation():
    return hashlib.sha256(secret("AURALIS_SUBJECT_SECRET").encode()).hexdigest()


def backup(host, target):
    host.stopped()
    host.owned()
    target.mkdir(parents=True, exist_ok=False)
    with (target / "database.dump").open("xb") as output:
        host.docker(
            "exec",
            host.name,
            "pg_dump",
            "-U",
            "postgres",
            "-Fc",
            "--exclude-table=public.auralis_restore_gate",
            "-d",
            host.config["database"],
            output=output,
            timeout=3600,
        )
    export_barriers(host, target / "barriers.csv")
    metadata = {
        "format": 1,
        "source": host.name,
        "database": host.config["database"],
        "created_at": datetime.now(timezone.utc).isoformat(),
        "dump_sha256": digest(target / "database.dump"),
        "subject_generation": generation(),
        "jar_sha256": digest(host.file("jar")),
    }
    (target / "backup.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")


def export_barriers(host, path):
    host.stopped()
    host.owned()
    with path.open("xb") as output:
        host.docker(
            "exec",
            host.name,
            "psql",
            "-X",
            "-v",
            "ON_ERROR_STOP=1",
            "-U",
            "postgres",
            "-d",
            host.config["database"],
            "-c",
            "COPY (SELECT credential_digest,revoked_at,expires_at FROM discovery.subject_revocation ORDER BY credential_digest) TO STDOUT WITH CSV HEADER",
            output=output,
        )


def replay(host, path):
    """CSV is data, never executable SQL; invalid rows abort the entire replay."""
    values = []
    with path.open(encoding="utf-8", newline="") as stream:
        rows = csv.DictReader(stream)
        require(
            rows.fieldnames == ["credential_digest", "revoked_at", "expires_at"], "INVALID_BARRIERS"
        )
        for row in rows:
            require(
                re.fullmatch("sha256:[a-f0-9]{64}", row["credential_digest"]),
                "INVALID_BARRIER_DIGEST",
            )
            dates = [
                datetime.fromisoformat(re.sub(r"([+-][0-9]{2})$", r"\1:00", row[key]))
                for key in ("revoked_at", "expires_at")
            ]
            require(all(date.tzinfo for date in dates), "INVALID_BARRIER_TIME")
            values.append(
                "('%s','%s','%s')" % (row["credential_digest"], *(d.isoformat() for d in dates))
            )
    # Single transaction: late expiry wins, earlier revocation cannot be erased by an older file.
    statements = ["BEGIN;"]
    for offset in range(0, len(values), 1000):
        statements.append(
            "INSERT INTO discovery.subject_revocation VALUES "
            + ",".join(values[offset : offset + 1000])
            + " ON CONFLICT (credential_digest) DO UPDATE SET "
            + "revoked_at=LEAST(subject_revocation.revoked_at,EXCLUDED.revoked_at),"
            + "expires_at=GREATEST(subject_revocation.expires_at,EXCLUDED.expires_at);"
        )
    statements.append("COMMIT;")
    host.sql("\n".join(statements))


def restore(host, source, barriers=None, complete=False):
    host.stopped()
    host.owned()
    metadata = read_json(source / "backup.json")
    require(metadata["format"] == 1 and metadata["source"] != host.name, "ISOLATED_TARGET_REQUIRED")
    require(metadata["database"] == host.config["database"], "DATABASE_MISMATCH")
    require(digest(source / "database.dump") == metadata["dump_sha256"], "BACKUP_DIGEST_MISMATCH")
    require(digest(host.file("jar")) == metadata["jar_sha256"], "RESTORE_SAME_RELEASE_FIRST")
    if generation() == metadata["subject_generation"]:
        require(
            complete and barriers is not None, "ROTATE_SUBJECT_SECRET_OR_PROVIDE_COMPLETE_BARRIERS"
        )
    # Refuse any populated destination. No --clean, overwrite, or source-container mutation.
    empty = host.sql(
        "SELECT count(*) FROM pg_tables WHERE schemaname NOT IN ('pg_catalog','information_schema');"
    )
    require(re.search(rb"\n\s*0\s*\n", empty), "RESTORE_TARGET_NOT_EMPTY")
    host.sql(
        "CREATE TABLE public.auralis_restore_gate(singleton boolean PRIMARY KEY CHECK(singleton),state jsonb NOT NULL);"
        "REVOKE ALL ON public.auralis_restore_gate FROM PUBLIC;"
        "INSERT INTO public.auralis_restore_gate VALUES(true,'{\"complete\":false}');"
    )
    with (source / "database.dump").open("rb") as stream:
        host.docker(
            "exec",
            "-i",
            host.name,
            "pg_restore",
            "-U",
            "postgres",
            "--role=auralis_migrator",
            "--no-owner",
            "--no-privileges",
            "--exit-on-error",
            "--single-transaction",
            "-d",
            host.config["database"],
            source=stream,
            timeout=3600,
        )
    if barriers:
        replay(host, barriers)
    # A restored WAITING row may have been paid/executed after the backup was taken.
    # Ordinary process restart keeps its separate existing queue recovery semantics.
    host.sql(
        "UPDATE discovery.discovery_intent SET status='INTERRUPTED',error_code='EXECUTION_GONE',revision=revision+1 WHERE status='WAITING';"
    )
    state = json.dumps({"complete": True, "released": False, "subject_generation": generation()})
    host.sql("UPDATE public.auralis_restore_gate SET state='" + state + "'::jsonb WHERE singleton;")
    # Still offline. Schema validation/migrations and grants are a separate explicit step.
    print("AURALIS_RESTORED_ISOLATED_RECONCILIATION_REQUIRED")


def release(host, ledger_reconciled, artifacts_verified):
    host.stopped()
    require(ledger_reconciled and artifacts_verified, "RECOVERY_REVIEW_REQUIRED")
    state = json.loads(
        host.scalar("SELECT state FROM public.auralis_restore_gate WHERE singleton;")
    )
    require(state.get("complete") is True, "RESTORE_INCOMPLETE")
    require(state["subject_generation"] == generation(), "RESTORE_SECRET_MISMATCH")
    state["released"] = True
    host.sql(
        "UPDATE public.auralis_restore_gate SET state='"
        + json.dumps(state)
        + "'::jsonb WHERE singleton;"
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "command", choices=("backup", "export-barriers", "restore", "replay", "release")
    )
    parser.add_argument("--config", required=True)
    parser.add_argument("--path")
    parser.add_argument("--barriers")
    parser.add_argument("--barriers-complete", action="store_true")
    parser.add_argument("--ledger-reconciled", action="store_true")
    parser.add_argument("--artifacts-verified", action="store_true")
    args = parser.parse_args()
    try:
        host = Host(args.config)
        with exclusive(host.name):
            if args.command == "release":
                release(host, args.ledger_reconciled, args.artifacts_verified)
                return 0
            require(args.path is not None, "PATH_REQUIRED")
            path = Path(args.path).resolve()
            if args.command == "backup":
                backup(host, path)
            elif args.command == "export-barriers":
                export_barriers(host, path)
            elif args.command == "replay":
                host.stopped()
                replay(host, path)
            else:
                restore(
                    host,
                    path,
                    Path(args.barriers) if args.barriers else None,
                    args.barriers_complete,
                )
        return 0
    except Failure as failure:
        print(str(failure), file=sys.stderr)
    except (OSError, ValueError, KeyError, subprocess.SubprocessError):
        print("AURALIS_RECOVERY_FAILED", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
