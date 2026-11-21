#!/bin/sh
set -eu

: "${POSTGRES_USER:?Bootstrap administrator is required}"
: "${POSTGRES_DB:?Database name is required}"
: "${MIGRATION_DB_PASSWORD:?Set a unique local migration password}"
: "${DB_PASSWORD:?Set a different unique local runtime password}"

: "${IDENTITY_OPERATOR_DB_PASSWORD:?Set a distinct operator password}"

if [ "$IDENTITY_OPERATOR_DB_PASSWORD" = "$DB_PASSWORD" ] \
  || [ "$IDENTITY_OPERATOR_DB_PASSWORD" = "$MIGRATION_DB_PASSWORD" ] \
  || [ "$IDENTITY_OPERATOR_DB_PASSWORD" = "${POSTGRES_PASSWORD:-}" ] \
  || [ "$DB_PASSWORD" = "$MIGRATION_DB_PASSWORD" ] \
  || [ "$DB_PASSWORD" = "${POSTGRES_PASSWORD:-}" ] \
  || [ "$MIGRATION_DB_PASSWORD" = "${POSTGRES_PASSWORD:-}" ]; then
  echo 'Bootstrap, migration, runtime, and identity operator passwords must differ' >&2
  exit 1
fi

# Passwords travel through environment/psql variables, never command arguments or echo.
exec psql -X --set ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --file /opt/eaap/bootstrap.sql
