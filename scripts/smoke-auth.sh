#!/bin/sh
# Checks that a service configured with an issuer actually enforces it.
#
# This is the cheapest guard that the "off unless configured" default cannot
# silently flip to permissive. It needs no identity provider: the issuer below
# does not have to exist for an anonymous call to be refused, which is the whole
# point — enforcement must not depend on the provider being reachable.
#
# The probes must still answer, or Kubernetes could never start the pod.
#
# Like scripts/smoke.sh it takes only a base URL, so the same script runs against
# either implementation.
#
# Usage: scripts/smoke-auth.sh [base-url]   (default http://localhost:8081)

set -eu

BASE="${1:-http://localhost:8081}"
failures=0

check() {
    if [ "$2" = "$3" ]; then
        printf 'ok   %s\n' "$1"
    else
        printf 'FAIL %s: expected <%s> but was <%s>\n' "$1" "$2" "$3"
        failures=$((failures + 1))
    fi
}

code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

i=0
while [ "$i" -lt 60 ]; do
    if [ "$(code "$BASE/health/ready")" = "200" ]; then break; fi
    i=$((i + 1))
    sleep 1
done

check 'readiness answers without a token' 200 "$(code "$BASE/health/ready")"
check 'liveness answers without a token' 200 "$(code "$BASE/health/live")"
check 'metrics answer without a token' 200 "$(code "$BASE/metrics")"
check 'the contract answers without a token' 200 "$(code "$BASE/openapi.yaml")"

check 'an anonymous read is refused' 401 "$(code "$BASE/v1/tasks")"
check 'an anonymous write is refused' 401 \
    "$(code -X POST "$BASE/v1/tasks" -H 'Content-Type: application/json' -d '{"title":"nope"}')"
check 'an anonymous cube read is refused' 401 "$(code "$BASE/v1/cubes")"
check 'a garbage token is refused' 401 "$(code "$BASE/v1/tasks" -H 'Authorization: Bearer not.a.token')"

CHALLENGE=$(curl -sI "$BASE/v1/tasks" | tr -d '\r' | grep -i '^www-authenticate:' | head -n 1)
case "$CHALLENGE" in
    *Bearer*) printf 'ok   %s\n' 'a challenge is offered' ;;
    *) printf 'FAIL %s: got <%s>\n' 'a challenge is offered' "$CHALLENGE"
       failures=$((failures + 1)) ;;
esac

printf '\n%s\n' "-------------------------------------------"
if [ "$failures" -eq 0 ]; then
    printf 'all checks passed against %s\n' "$BASE"
    exit 0
fi
printf '%d check(s) failed against %s\n' "$failures" "$BASE"
exit 1
