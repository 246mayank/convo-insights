#!/usr/bin/env bash
# Official run v2: same 4,000 conversations as run 1, current code (redaction v2), fresh DB and topics.
set -euo pipefail
export MSYS_NO_PATHCONV=1 PATH="$PATH:/c/Program Files/Docker/Docker/resources/bin"
cd "/d/twilio project/convo-insights"
export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.4.7-hotspot"
docker exec ci-postgres psql -U insights -d insights -tAc "create database insights_v2" || true
./mvnw -q -DskipTests package
ARGS="--spring.datasource.url=jdbc:postgresql://localhost:5432/insights_v2 --insights.topics.raw=conversations.raw.v2 --insights.topics.enriched=insights.enriched.v2 --insights.topics.segment=insights.segment.v2 --insights.topics.dead-letter=conversations.raw.v2.DLT --insights.analytics.enabled=false --insights.live-feed.enabled=false"
# Flyway runs on the app's first start; the pilot script creates its id table before that, so migrate first
"$JAVA_HOME/bin/java" -jar target/convo-insights-0.0.1-SNAPSHOT.jar --spring.profiles.active=republish $ARGS >/dev/null 2>&1 || true
DB=insights_v2 APP_ARGS="$ARGS" OFFSET_LINE=15201 PORT=8080 REPORT=bench/run-official-v2.md JAVA="$JAVA_HOME/bin/java" \
  bash scripts/pilot.sh 4000 1 qwen2.5:3b
