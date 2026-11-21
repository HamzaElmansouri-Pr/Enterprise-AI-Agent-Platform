#!/usr/bin/env bash
# Interactive local operator entry point. Credentials stay out of command arguments and files.
set -euo pipefail

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$PROJECT_DIR"

if [[ ! -t 0 ]]; then
    echo 'Run ./dev.sh bootstrap directly in an interactive terminal.' >&2
    exit 1
fi

read -r -p 'Owner email: ' BOOTSTRAP_EMAIL
read -r -p 'Tenant name: ' BOOTSTRAP_TENANT_NAME
[[ -n "$BOOTSTRAP_EMAIL" && -n "$BOOTSTRAP_TENANT_NAME" ]] || {
    echo 'Email and tenant name are required.' >&2
    exit 1
}

# Preserve optional stable IDs supplied by an operator; otherwise create the initial IDs.
BOOTSTRAP_TENANT_ID=${BOOTSTRAP_TENANT_ID:-$(cat /proc/sys/kernel/random/uuid)}
BOOTSTRAP_USER_ID=${BOOTSTRAP_USER_ID:-$(cat /proc/sys/kernel/random/uuid)}
trap 'unset BOOTSTRAP_PASSWORD confirmation' EXIT
while true; do
    read -r -s -p 'Password (16–128 characters): ' BOOTSTRAP_PASSWORD
    printf '\n'
    if [[ ${#BOOTSTRAP_PASSWORD} -lt 16 || ${#BOOTSTRAP_PASSWORD} -gt 128 || -z "${BOOTSTRAP_PASSWORD//[[:space:]]/}" ]]; then
        echo 'Password must contain 16–128 characters and cannot be blank.' >&2
        continue
    fi
    read -r -s -p 'Confirm password: ' confirmation
    printf '\n'
    if [[ "$BOOTSTRAP_PASSWORD" == "$confirmation" ]]; then break; fi
    echo 'Passwords do not match. Try again.' >&2
done
unset confirmation
export BOOTSTRAP_TENANT_ID BOOTSTRAP_USER_ID BOOTSTRAP_TENANT_NAME BOOTSTRAP_EMAIL BOOTSTRAP_PASSWORD

docker compose --env-file "$PROJECT_DIR/.env" \
    -f "$PROJECT_DIR/compose.yaml" -f "$PROJECT_DIR/compose.bootstrap.yaml" \
    run --rm --no-deps \
    -e BOOTSTRAP_TENANT_ID -e BOOTSTRAP_USER_ID \
    -e BOOTSTRAP_TENANT_NAME -e BOOTSTRAP_EMAIL -e BOOTSTRAP_PASSWORD \
    control-plane

printf 'Owner bootstrap completed. Save these IDs for any repeat operation:\nTenant ID: %s\nUser ID: %s\n' \
    "$BOOTSTRAP_TENANT_ID" "$BOOTSTRAP_USER_ID"
