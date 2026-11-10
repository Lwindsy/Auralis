#!/usr/bin/env bash
set -Eeuo pipefail
release=${1:?release required}
[[ "$release" =~ ^[a-f0-9]{40}$ ]] || exit 2
root=/opt/auralis
exec 9>"$root/deploy.lock"
flock -n 9 || { echo 'Another deployment is running'; exit 1; }
cd "$root/releases/$release"
export AURALIS_RELEASE="$release"
compose=(docker compose -f apps/runtime/compose.yml)
docker build -f apps/runtime/java.Dockerfile -t "auralis-java:$release" .
docker build -f apps/runtime/python.Dockerfile -t "auralis-python:$release" .
docker build -f apps/runtime/web.Dockerfile -t "auralis-web:$release" .
"${compose[@]}" up -d --wait database
sql() { "${compose[@]}" exec -T database psql -X -At -v ON_ERROR_STOP=1 -U postgres -d auralis_core -c "$1"; }
# Stop new work and let already accepted operations settle before snapshotting.
"${compose[@]}" stop web
sql 'UPDATE discovery.runtime_control SET maintenance=true,revision=revision+1 WHERE singleton;'
for i in $(seq 1 150); do
    active=$(sql "SELECT (SELECT count(*) FROM discovery.execution_slot WHERE state<>'FREE')+(SELECT count(*) FROM discovery.discovery_intent WHERE status='WAITING');")
    [[ "$active" == 0 ]] && break
    sleep 1
done
[[ "$active" == 0 ]] || { echo 'Active executions did not drain; deployment stopped'; exit 1; }
"${compose[@]}" stop web java python
mkdir -p "$root/backups"
backup="$root/backups/$(date -u +%Y%m%dT%H%M%SZ)-$release.dump"
umask 077
"${compose[@]}" exec -T database pg_dump -U postgres -d auralis_core -Fc > "$backup"
"${compose[@]}" run --rm migrate
sql 'GRANT USAGE ON SCHEMA catalog,ingestion,statistics,discovery TO auralis_online; GRANT SELECT ON ALL TABLES IN SCHEMA catalog,ingestion,statistics TO auralis_online; GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA discovery TO auralis_online; GRANT SELECT ON public.flyway_schema_history TO auralis_online; REVOKE INSERT,UPDATE,DELETE ON discovery.audience_snapshot,discovery.audience_count FROM auralis_online;'
"${compose[@]}" up -d python java
ready=false
for i in $(seq 1 120); do
    if curl -fsS http://127.0.0.1:18080/actuator/health/readiness | grep -q '"status":"UP"'; then
        ready=true
        break
    fi
    sleep 2
done
if [[ "$ready" != true ]]; then
    "${compose[@]}" stop java python
    echo "Readiness failed; web remains stopped. Backup: $backup"
    exit 1
fi
"${compose[@]}" up -d web
ln -sfn "$root/releases/$release" "$root/current"
echo "AURALIS_DEPLOYED $release"
