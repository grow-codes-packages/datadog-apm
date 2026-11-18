#!/bin/sh
# One-shot bootstrap for the Datadog APM Docker suite (sdk-kit SPEC §8). POSIX sh (BusyBox), idempotent.
#
# Nothing to provision (the Agent and fakeintake have no users or tokens). The bootstrap:
#   1. waits for fakeintake /fakeintake/health, the Agent's trace intake /info (:8126) and trace-reader,
#   2. proves the whole pipeline once: sends one OTLP/HTTP JSON probe span (service
#      `grow-bootstrap-probe`, fresh random trace id) to the Agent's :4318 and waits until the Agent has
#      forwarded it to fakeintake (/api/v0.2/traces) and trace-reader can decode it,
#   3. writes endpoints, the Agent version and placeholder credentials to /bootstrap/env.
# It never flushes fakeintake, so re-running it during a test cannot delete captured data; each run adds
# exactly one probe span (filter by trace id or service in tests).
set -eu

: "${AGENT_HOST:?AGENT_HOST is not set}" "${FAKEINTAKE_URL:?FAKEINTAKE_URL is not set}" "${DD_API_KEY:?DD_API_KEY is not set}"
TRACE_READER_URL=${TRACE_READER_URL:-http://trace-reader:8080}
BOOTSTRAP_ENV=${BOOTSTRAP_ENV:-/bootstrap/env}
AGENT_URL="http://$AGENT_HOST:8126"
OTLP_HTTP="http://$AGENT_HOST:4318"
OTLP_GRPC="http://$AGENT_HOST:4317"
log() { printf 'bootstrap: %s\n' "$*" >&2; }
fail() { log "error: $*"; exit 1; }

wait_ok() { # URL NAME
  i=0
  until curl -fsS -o /dev/null "$1"; do
    i=$((i + 1)); [ "$i" -lt 90 ] || fail "$2 not ready after 90s ($1)"; sleep 1
  done
}

# 1. readiness (compose already gates on healthchecks; this covers manual runs)
wait_ok "$FAKEINTAKE_URL/fakeintake/health" fakeintake
wait_ok "$AGENT_URL/info" "datadog-apm /info"
wait_ok "$TRACE_READER_URL/health" trace-reader

info=$(curl -fsS "$AGENT_URL/info") || fail "GET /info failed"
agent_version=$(printf '%s' "$info" | tr -d '\n\t' | sed -n 's/.*"version": *"\([^"]*\)".*/\1/p' | head -n1)
[ -n "$agent_version" ] || fail "could not read the Agent version from /info"

# 2. pipeline probe: OTLP/HTTP JSON -> Agent -> fakeintake -> trace-reader
hex() { od -An -N"$1" -tx1 /dev/urandom | tr -d ' \n'; }
trace_id="$(hex 16)"
span_id="$(hex 8)"
now_s=$(date +%s)
start="${now_s}000000000"
end="${now_s}001000000"
probe=$(cat <<JSON
{"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"grow-bootstrap-probe"}}]},"scopeSpans":[{"scope":{"name":"bootstrap"},"spans":[{"traceId":"$trace_id","spanId":"$span_id","name":"bootstrap.probe","kind":1,"startTimeUnixNano":"$start","endTimeUnixNano":"$end"}]}]}]}
JSON
)
code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$probe" "$OTLP_HTTP/v1/traces")
[ "$code" = 200 ] || fail "OTLP/HTTP probe returned HTTP $code"

i=0
until curl -fsS "$TRACE_READER_URL/spans?trace_id=$trace_id" | grep -q '"service":"grow-bootstrap-probe"'; do
  i=$((i + 1)); [ "$i" -lt 60 ] || fail "probe span did not reach fakeintake within 30s (Agent -> fakeintake forwarding broken)"
  sleep 0.5
done

# 3. env file, written atomically (KEY=value, values taken literally). Test-only placeholders.
tmp="$BOOTSTRAP_ENV.tmp.$$"
mkdir -p "$(dirname "$BOOTSTRAP_ENV")"
cat > "$tmp" <<ENV
# written by datadog-apm bootstrap; test-only placeholders, nothing here reaches Datadog
GROW_DATADOG_APM_URL=$AGENT_URL
GROW_DATADOG_APM_OTLP_ENDPOINT=$OTLP_HTTP
GROW_DATADOG_APM_OTLP_HTTP_ENDPOINT=$OTLP_HTTP
GROW_DATADOG_APM_OTLP_GRPC_ENDPOINT=$OTLP_GRPC
DD_AGENT_HOST=$AGENT_HOST
DD_TRACE_AGENT_PORT=8126
DD_TRACE_AGENT_URL=$AGENT_URL
DD_AGENT_VERSION=$agent_version
DD_API_KEY=$DD_API_KEY
DD_APP_KEY=grow-test-only-dd-app-key
DD_SITE=datadoghq.com
FAKEINTAKE_URL=$FAKEINTAKE_URL
FAKEINTAKE_TRACES_ENDPOINT=/api/v0.2/traces
TRACE_READER_URL=$TRACE_READER_URL
BOOTSTRAP_PROBE_TRACE_ID=$trace_id
ENV
chmod 0644 "$tmp"
mv "$tmp" "$BOOTSTRAP_ENV"
log "ok: agent $agent_version, OTLP probe forwarded in ~$((i / 2))s, $(grep -c '=' "$BOOTSTRAP_ENV") keys in $BOOTSTRAP_ENV"
