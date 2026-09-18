#!/bin/sh
# Drives one running service through the contract and checks what comes back.
#
# The point is that it takes a base URL and nothing else: the same script runs
# against the zero-dependency service and against the Spring Boot one, so CI
# proves the two behave alike on the wire rather than asserting it in a README.
#
# Only behaviour both implementations promise is asserted here. Where they
# deliberately differ — metric names, the exact Cache-Control value, the status
# of an OPTIONS response — each service's own test suite pins its own answer,
# and springboot-java-backend/README.md lists the differences.
#
# Usage: scripts/smoke.sh [base-url]        (default http://localhost:8080)

set -eu

BASE="${1:-http://localhost:8080}"
failures=0

# Unique per run: an idempotency key is remembered for a day, so a fixed one would
# make a second run against the same instance replay the first run's results.
RUN_ID="smoke-$$-$(date +%s)"


# ---------------------------------------------------------------- helpers

check() { # check <description> <expected> <actual>
    if [ "$2" = "$3" ]; then
        printf 'ok   %s\n' "$1"
    else
        printf 'FAIL %s: expected <%s> but was <%s>\n' "$1" "$2" "$3"
        failures=$((failures + 1))
    fi
}

contains() { # contains <description> <needle> <haystack>
    case "$3" in
        *"$2"*) printf 'ok   %s\n' "$1" ;;
        *) printf 'FAIL %s: <%s> does not contain <%s>\n' "$1" "$3" "$2"
           failures=$((failures + 1)) ;;
    esac
}

status() { # status <method> <path> [data] [header...]
    method="$1"; path="$2"; data="${3:-}"; shift 3 2>/dev/null || shift 2
    if [ -n "$data" ]; then
        curl -s -o /dev/null -w '%{http_code}' -X "$method" "$BASE$path" \
            -H 'Content-Type: application/json' -d "$data" "$@"
    else
        curl -s -o /dev/null -w '%{http_code}' -X "$method" "$BASE$path" "$@"
    fi
}

body() { # body <method> <path> [data] [header...]
    method="$1"; path="$2"; data="${3:-}"; shift 3 2>/dev/null || shift 2
    if [ -n "$data" ]; then
        curl -s -X "$method" "$BASE$path" -H 'Content-Type: application/json' -d "$data" "$@"
    else
        curl -s -X "$method" "$BASE$path" "$@"
    fi
}

id_of() { sed -n 's/.*"id":"\([^"]*\)".*/\1/p'; }

# The status line of a -i response, e.g. "HTTP/1.1 201 Created" -> 201.
status_line() { head -n 1 | tr -d '\r' | awk '{print $2}'; }

# ---------------------------------------------------------------- readiness

i=0
while [ "$i" -lt 60 ]; do
    if [ "$(status GET /health/ready)" = "200" ]; then break; fi
    i=$((i + 1))
    sleep 1
done
check 'service is ready' 200 "$(status GET /health/ready)"
check 'liveness answers' 200 "$(status GET /health/live)"
check 'metrics are scrapeable' 200 "$(status GET /metrics)"
contains 'the contract is served' 'openapi: 3.1.0' "$(body GET /openapi.yaml)"

# ---------------------------------------------------------------- tasks

CREATED=$(curl -si -X POST "$BASE/v1/tasks" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: \"$RUN_ID\"" -d '{"title":"smoke"}')
check 'create answers 201' 201 "$(printf '%s' "$CREATED" | status_line)"
contains 'create sets an ETag' 'etag: "1"' "$(printf '%s' "$CREATED" | tr 'A-Z' 'a-z')"
TASK_ID=$(printf '%s' "$CREATED" | id_of)
contains 'create sets Location' "/v1/tasks/$TASK_ID" "$(printf '%s' "$CREATED" | tr 'A-Z' 'a-z')"

check 'the same key replays the same task' "$TASK_ID" \
    "$(body POST /v1/tasks '{"title":"smoke"}' -H "Idempotency-Key: \"$RUN_ID\"" | id_of)"
check 'the same key with a different body is refused' 422 \
    "$(status POST /v1/tasks '{"title":"different"}' -H "Idempotency-Key: \"$RUN_ID\"")"

check 'a matching If-None-Match is not modified' 304 \
    "$(status GET "/v1/tasks/$TASK_ID" '' -H 'If-None-Match: "1"')"
check 'a write without If-Match is refused' 428 \
    "$(status PUT "/v1/tasks/$TASK_ID" '{"title":"x"}')"
check 'a write with a stale If-Match is refused' 412 \
    "$(status PUT "/v1/tasks/$TASK_ID" '{"title":"x"}' -H 'If-Match: "99"')"
check 'a write with a current If-Match succeeds' 200 \
    "$(status PUT "/v1/tasks/$TASK_ID" '{"title":"updated"}' -H 'If-Match: "1"')"
check 'delete needs the new version' 204 \
    "$(status DELETE "/v1/tasks/$TASK_ID" '' -H 'If-Match: "2"')"
check 'a deleted task is gone' 404 "$(status GET "/v1/tasks/$TASK_ID")"

# ---------------------------------------------------------------- rejections

check 'an unknown query parameter is refused' 400 "$(status GET '/v1/tasks?sort=title')"
check 'a bad limit is refused' 400 "$(status GET '/v1/tasks?limit=0')"
check 'a bad cursor is refused' 400 "$(status GET '/v1/tasks?cursor=***')"
check 'an unknown field is refused' 422 "$(status POST /v1/tasks '{"title":"x","nope":1}')"
check 'a blank title is refused' 422 "$(status POST /v1/tasks '{"title":" "}')"
check 'malformed JSON is refused' 400 "$(status POST /v1/tasks '{"title":')"
check 'a non-JSON content type is refused' 415 \
    "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/v1/tasks" -H 'Content-Type: text/plain' -d 'x')"
check 'an unacceptable Accept is refused' 406 "$(status GET /v1/tasks '' -H 'Accept: text/html')"
check 'an unknown path is not found' 404 "$(status GET /nope)"
check 'a non-canonical id is not found' 404 "$(status GET /v1/tasks/1-1-1-1-1)"
check 'an unsupported method is refused' 405 "$(status PATCH /v1/tasks '{}')"

PROBLEM=$(body GET /v1/tasks/00000000-0000-7000-8000-000000000000 '' -H 'X-Request-Id: smoke-trace')
contains 'a problem document carries the request id' '"requestId":"smoke-trace"' "$PROBLEM"
contains 'a problem document carries a status' '"status":404' "$PROBLEM"

# ---------------------------------------------------------------- cubes

CUBE=$(body POST /v1/cubes '{}')
contains 'an empty cube body gets the contract defaults' '"displayName":"Cube 100 x 100 x 100"' "$CUBE"
CUBE_ID=$(printf '%s' "$CUBE" | id_of)
check 'a cube renders as a PDF' 200 "$(status GET "/v1/cubes/$CUBE_ID/pdf")"
contains 'the PDF is really a PDF' '%PDF-1.7' "$(body GET "/v1/cubes/$CUBE_ID/pdf" | head -c 8)"
contains 'nested violations are reported with JSON Pointers' '/cube/colour/red' \
    "$(body POST /v1/cubes '{"cube":{"colour":{"red":300}}}')"

# ---------------------------------------------------------------- pagination

i=0
while [ "$i" -lt 3 ]; do
    status POST /v1/tasks "{\"title\":\"page $i\"}" > /dev/null
    i=$((i + 1))
done
PAGE=$(curl -si "$BASE/v1/tasks?limit=2")
contains 'a further page is advertised with a Link header' 'rel="next"' \
    "$(printf '%s' "$PAGE" | tr 'A-Z' 'a-z')"
contains 'the cursor is in the body too' '"nextCursor"' "$PAGE"

# ---------------------------------------------------------------- result

printf '\n%s\n' "-------------------------------------------"
if [ "$failures" -eq 0 ]; then
    printf 'all checks passed against %s\n' "$BASE"
    exit 0
fi
printf '%d check(s) failed against %s\n' "$failures" "$BASE"
exit 1
