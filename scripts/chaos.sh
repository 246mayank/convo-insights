#!/usr/bin/env bash
# Chaos test: replay N events while hard-killing (SIGKILL / TerminateProcess) the consumer K times,
# then reconcile what was stored and published against what was sent.
#
#   scripts/chaos.sh [N=3000] [K=6] [RATE=25] [PROVIDER=rules]
#
# Needs: docker compose stack up, the jar built (./mvnw -DskipTests package), data/conversations.jsonl.
# Writes a report to bench/chaos-<timestamp>.md.
set -euo pipefail

N=${1:-3000}
K=${2:-6}
RATE=${3:-25}
PROVIDER=${4:-rules}
OFFSET_LINE=${OFFSET_LINE:-2001}   # take events from this line on, away from earlier smoke runs

cd "$(dirname "$0")/.."
TS=$(date +%Y%m%d-%H%M%S)
GROUP="chaos-$TS"
JAR=target/convo-insights-0.0.1-SNAPSHOT.jar
JAVA=${JAVA:-java}
WORK=target/chaos-$TS
mkdir -p "$WORK" bench
REPORT=bench/chaos-$TS.md

tail -n +"$OFFSET_LINE" data/conversations.jsonl | head -n "$N" > "$WORK/events.jsonl"
python -I -c "import json,sys;[print(json.loads(l)['eventId']) for l in open(sys.argv[1],encoding='utf-8')]" "$WORK/events.jsonl" | sort -u > "$WORK/ids.txt"
DISTINCT=$(wc -l < "$WORK/ids.txt")
echo "chaos run $TS: N=$N distinct=$DISTINCT K=$K rate=$RATE/s provider=$PROVIDER group=$GROUP"

APP_PID=""
start_app() {
  "$JAVA" -jar "$JAR" --spring.kafka.consumer.group-id="$GROUP" --insights.llm.provider="$PROVIDER" \
      --server.port=0 >> "$WORK/app.log" 2>&1 &
  APP_PID=$!
}
kill_app() {
  if [[ -n "$APP_PID" ]] && kill -0 "$APP_PID" 2>/dev/null; then
    kill -9 "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
  fi
}
trap kill_app EXIT

psql() { docker exec ci-postgres psql -U insights -d insights -tAc "$1"; }
# load the run's eventIds into Postgres once, so reconciliation queries can join on them
IDS_TABLE="chaos_ids_${TS//-/_}"
docker cp "$WORK/ids.txt" ci-postgres:/tmp/$IDS_TABLE.txt >/dev/null
psql "create table $IDS_TABLE (event_id varchar(64) primary key)" >/dev/null
psql "copy $IDS_TABLE from '/tmp/$IDS_TABLE.txt'" >/dev/null
IN_SET="event_id in (select event_id from $IDS_TABLE)"
lag() {
  docker exec ci-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
      --describe --group "$GROUP" 2>/dev/null | awk 'NR>1 && $6 ~ /^[0-9]+$/ {s+=$6} END {print s+0}'
}

START=$(date +%s)
start_app
sleep 12   # let the first instance join the group before traffic starts

"$JAVA" -jar "$JAR" --spring.profiles.active=replay --replay.file="$WORK/events.jsonl" \
    --replay.rate="$RATE" > "$WORK/replay.log" 2>&1 &
REPLAY_PID=$!

KILLS=0
while (( KILLS < K )); do
  sleep $(( 10 + RANDOM % 16 ))
  kill_app
  KILLS=$((KILLS + 1))
  echo "  kill #$KILLS at +$(( $(date +%s) - START ))s (stored so far: $(psql "select count(*) from insights where $IN_SET"))"
  sleep 2
  start_app
done

wait "$REPLAY_PID" || true
echo "  replay finished: $(grep -o 'replay done.*' "$WORK/replay.log" || echo 'no summary line')"

# wait for the consumer to drain and the outbox to empty
for _ in $(seq 1 240); do
  L=$(lag); P=$(psql "select count(*) from outbox where sent_at is null")
  [[ "$L" == "0" && "$P" == "0" ]] && break
  sleep 5
done
END=$(date +%s)

STORED=$(psql "select count(*) from insights where $IN_SET")
DUP_ROWS=$(psql "select count(*) from (select event_id from insights where $IN_SET group by event_id having count(*) > 1) d")
OUTBOX_ROWS=$(psql "select count(*) from outbox where record_key in (select event_id from $IDS_TABLE)")
PENDING=$(psql "select count(*) from outbox where sent_at is null")

docker exec ci-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
    --topic insights.enriched --from-beginning --property print.key=true --property print.value=false \
    --timeout-ms 20000 2>/dev/null > "$WORK/enriched-keys.txt" || true
grep -Fxf "$WORK/ids.txt" "$WORK/enriched-keys.txt" > "$WORK/enriched-in-set.txt" || true
PUB_TOTAL=$(wc -l < "$WORK/enriched-in-set.txt")
PUB_DISTINCT=$(sort -u "$WORK/enriched-in-set.txt" | wc -l)
MISSING=$(( DISTINCT - STORED ))
LOST_DOWNSTREAM=$(( DISTINCT - PUB_DISTINCT ))
FINAL_LAG=$(lag)
SHA=$(git rev-parse --short HEAD)
psql "drop table $IDS_TABLE" >/dev/null

cat > "$REPORT" <<EOF
# Chaos run $TS

| | |
|---|---|
| Commit | \`$SHA\` |
| Machine | $(uname -s), i7-12700H, 16 GB, CPU only |
| Extractor | $PROVIDER |
| Events sent | $N lines, **$DISTINCT distinct eventIds**, at $RATE events/s |
| Hard kills (SIGKILL) | **$KILLS**, every 10-25 s at random, restart after 2 s |
| Duration | $(( END - START )) s |

## Reconciliation

| Check | Result |
|---|---|
| Insights stored for the sent eventIds | **$STORED / $DISTINCT** (missing: $MISSING) |
| eventIds stored more than once | **$DUP_ROWS** |
| Outbox rows for the set | $OUTBOX_ROWS (one per stored insight) |
| Outbox rows still pending | $PENDING |
| Consumer lag at the end | $FINAL_LAG |
| Records on insights.enriched for the set | $PUB_TOTAL (distinct keys: **$PUB_DISTINCT**, missing: $LOST_DOWNSTREAM) |
| Downstream duplicates (at-least-once, expected >= 0) | $(( PUB_TOTAL - PUB_DISTINCT )) |

Command: \`scripts/chaos.sh $N $K $RATE $PROVIDER\`
EOF
echo "report: $REPORT"
cat "$REPORT"
