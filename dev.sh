#!/bin/sh
# Local Compose lifecycle; privileged database work runs only in temporary operator processes.
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$PROJECT_DIR"

init_env() {
    (
        umask 077
        source_file=.env.example
        [ ! -e .env ] || source_file=.env
        temp_file=$(mktemp "$PROJECT_DIR/.env.tmp.XXXXXX")
        trap 'rm -f "$temp_file"' 0 HUP INT TERM
        # Generate independent local credentials without placing values in command arguments.
        EAAP_ADMIN_PASSWORD=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
        EAAP_MIGRATION_PASSWORD=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
        EAAP_RUNTIME_PASSWORD=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
        EAAP_OPERATOR_PASSWORD=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
        for value in "$EAAP_ADMIN_PASSWORD" "$EAAP_MIGRATION_PASSWORD" "$EAAP_RUNTIME_PASSWORD" "$EAAP_OPERATOR_PASSWORD"; do
            [ "${#value}" -eq 64 ] || { echo 'Credential generation failed.' >&2; exit 1; }
        done
        export EAAP_ADMIN_PASSWORD EAAP_MIGRATION_PASSWORD EAAP_RUNTIME_PASSWORD EAAP_OPERATOR_PASSWORD
        awk '
            BEGIN {
                defaults["POSTGRES_PASSWORD"] = ENVIRON["EAAP_ADMIN_PASSWORD"]
                defaults["MIGRATION_DB_PASSWORD"] = ENVIRON["EAAP_MIGRATION_PASSWORD"]
                defaults["DB_PASSWORD"] = ENVIRON["EAAP_RUNTIME_PASSWORD"]
                defaults["IDENTITY_OPERATOR_DB_PASSWORD"] = ENVIRON["EAAP_OPERATOR_PASSWORD"]
                defaults["DB_HOST"] = "postgres"
                defaults["DB_PORT"] = "5432"
            }
            {
                key = $0; sub(/=.*/, "", key)
                if (key in defaults) {
                    seen[key] = 1
                    value = $0; sub(/^[^=]*=/, "", value)
                    if (value ~ /^[[:space:]]*$/) { print key "=" defaults[key]; next }
                }
                print
            }
            END { for (key in defaults) if (!(key in seen)) print key "=" defaults[key] }
        ' "$source_file" > "$temp_file"
        if [ -e .env ] && cmp -s .env "$temp_file"; then
            chmod 600 .env
        else
            mv "$temp_file" .env
        fi
    )
    echo 'Local .env ready; nonempty settings preserved, missing passwords generated (owner access only).'
}

compose() {
    docker compose --env-file "$PROJECT_DIR/.env" -f "$PROJECT_DIR/compose.yaml" "$@"
}

case "${1:-up}" in
    init)
        init_env
        ;;
    up)
        init_env
        compose config --quiet
        compose up -d --wait postgres
        compose build control-plane
        # Existing volumes are preserved; synchronize dedicated role credentials with .env.
        compose exec -T postgres sh /docker-entrypoint-initdb.d/10-platform-roles.sh
        docker compose --env-file "$PROJECT_DIR/.env" \
            -f "$PROJECT_DIR/compose.yaml" -f "$PROJECT_DIR/compose.migration.yaml" \
            run --rm --no-deps control-plane
        compose up -d --build --wait
        compose ps
        ;;
    down)
        compose down
        ;;
    logs)
        compose logs --tail=100
        ;;
    *)
        echo 'Usage: ./dev.sh [up|init|down|logs]' >&2
        exit 2
        ;;
esac
