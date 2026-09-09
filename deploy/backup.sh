#!/bin/sh
# Snapshot only this Compose project's database and upload volume. Never print secrets.
set -eu
umask 077
cd "$(dirname "$0")/.."
compose() { docker compose -f compose.prod.yaml "$@"; }
backup_dir=".local/backups/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p .local/backups
mkdir "$backup_dir"
running=$(compose ps --status running --services)
resume_services=""
for service in backend web; do
  if printf '%s\n' "$running" | grep -qx "$service"; then resume_services="$resume_services $service"; fi
done
resume() {
  if [ -n "$resume_services" ]; then
    # The names come only from the fixed backend/web list above.
    compose up -d --wait $resume_services
  fi
}
trap resume EXIT
trap 'exit 130' INT TERM
compose stop web backend
compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysqldump -u "$MYSQL_USER" --single-transaction --no-tablespaces --set-gtid-purged=OFF "$MYSQL_DATABASE"' > "$backup_dir/database.sql"
mkdir "$backup_dir/files"
compose cp backend:/data/. "$backup_dir/files/"
git rev-parse HEAD > "$backup_dir/revision.txt"
compose images --format json > "$backup_dir/images.json"
test -s "$backup_dir/database.sql"
touch "$backup_dir/COMPLETE"
printf 'Backup complete: %s\n' "$backup_dir"
