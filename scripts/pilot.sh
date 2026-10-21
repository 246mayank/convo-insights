#!/usr/bin/env bash
# Throughput pilot: run the full pipeline with the real model on M events at consumer concurrency C,
# and report events/s plus model latency and fallback rate, measured from the stored rows.
#
#   OFFSET_LINE=15001 scripts/pilot.sh [M=120] [C=1] [MODEL=qwen2.5:3b]
set -euo pipefail
export MSYS_NO_PATHCONV=1

M=${1:-120}
C=${2:-1}
MODEL=${3:-qwen2.5:3b}
OFFSET_LINE=${OFFSET_LINE:-15001}
PORT=${PORT:-0}                      # 8080 lets Prometheus scrape the run
REPORT=${REPORT:-bench/pilot.md}
cd "$(dirname "$0")/.."
TS=$(date +%Y%m%d-%H%M%S)
WORK=target/pilot-$TS
JAVA=${JAVA:-java}
mkdir -p "$WORK" bench
psql() { docker exec ci-postgres psql -U insights -d insights -tAc "$1"; }

sed -n "${OFFSET_LINE},$((OFFSET_LINE + M - 1))p" data/conversations.jsonl > "$WORK/events.jsonl"
python -I -c "import json,sys;[print(json.loads(l)['eventId']) for l in open(sys.argv[1],encoding='utf-8')]" "$WORK/events.jsonl" | sort -u > "$WORK/ids.txt"
T="pilot_ids_${TS//-/_}"
docker cp "$WORK/ids.txt" ci-postgres:/tmp/$T.txt >/dev/null
psql "create table $T (event_id varchar(64) primary key)" >/dev/null
psql "copy $T from '/tmp/$T.txt'" >/dev/null
DISTINCT=$(wc -l < "$WORK/ids.txt")
[[ "$(psql "select count(*) from insights where event_id in (select event_id from $T)")" == "0" ]] \
    || { echo "abort: events already stored, pick another OFFSET_LINE" >&2; psql "drop table $T"; exit 1; }

"$JAVA" -jar target/convo-insights-0.0.1-SNAPSHOT.jar --spring.kafka.consumer.group-id="pilot-$TS" \
    --insights.llm.provider=ollama --insights.llm.ollama.model="$MODEL" \
    --spring.kafka.listener.concurrency="$C" --server.port="$PORT" > "$WORK/app.log" 2>&1 &
APP=$!
trap 'kill $APP 2>/dev/null || true' EXIT
until grep -q "partitions assigned" "$WORK/app.log"; do sleep 1; done
"$JAVA" -jar target/convo-insights-0.0.1-SNAPSHOT.jar --spring.profiles.active=replay \
    --replay.file="$WORK/events.jsonl" > "$WORK/replay.log" 2>&1

until [[ "$(psql "select count(*) from insights where event_id in (select event_id from $T)")" == "$DISTINCT" ]]; do sleep 2; done

Q="from insights where event_id in (select event_id from $T)"
SECS=$(psql "select round(extract(epoch from max(created_at) - min(created_at))::numeric, 1) $Q")
P50=$(psql "select percentile_disc(0.5) within group (order by llm_latency_ms) $Q")
P95=$(psql "select percentile_disc(0.95) within group (order by llm_latency_ms) $Q")
FALLBACK=$(psql "select round(100.0 * count(*) filter (where extractor = 'RULES') / count(*), 1) $Q")
REASONS=$(psql "select coalesce(string_agg(fallback_reason || '=' || n, ', '), 'none') from (select fallback_reason, count(*) n $Q and fallback_reason is not null group by 1) r")
if [[ "$PORT" != "0" ]]; then
  curl -s "localhost:$PORT/actuator/prometheus" > "${REPORT%.md}-prometheus.txt" || true
  curl -s "localhost:$PORT/api/insights/stats" > "${REPORT%.md}-stats.json" || true
fi
RATE=$(python -I -c "print(round(($DISTINCT - 1) / float('$SECS'), 3))")
psql "drop table $T" >/dev/null

LINE="| $MODEL | $C | $DISTINCT | $SECS | **$RATE** | $P50 | $P95 | $FALLBACK% ($REASONS) | \`$(git rev-parse --short HEAD)\` |"
echo "$LINE"
if [[ ! -f "$REPORT" ]]; then
  cat > "$REPORT" <<EOF
# Throughput pilot (full pipeline, real model)

Kafka -> consumer -> PII redaction -> Ollama -> validation -> Postgres + outbox, on real TWCS conversations.
Throughput = (events - 1) / (last insight created_at - first created_at). Latency = time waiting on the model,
which includes queueing inside Ollama when several consumer threads share it.
Machine: i7-12700H, 16 GB, CPU only, Ollama 0.40.7 (default parallelism). Command: \`scripts/pilot.sh M C MODEL\`.

| Model | Consumer threads | Events | Seconds | Events/s | p50 model ms | p95 model ms | Fallback | Commit |
|---|---:|---:|---:|---:|---:|---:|---|---|
EOF
fi
echo "$LINE" >> "$REPORT"
echo "Started $TS, finished $(date +%Y%m%d-%H%M%S), lines $OFFSET_LINE-$((OFFSET_LINE + M - 1)) of data/conversations.jsonl." >> "$REPORT"
