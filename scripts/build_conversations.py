"""Thread the Kaggle "Customer Support on Twitter" corpus (twcs.csv) into conversations.

Each conversation starts at a customer tweet that is not a reply to anything, and contains every
tweet in the reply tree under it, ordered by time. Output is one ConversationEvent JSON per line,
the exact format the replay producer sends to Kafka.

    python scripts/build_conversations.py ../data-raw/twcs/twcs.csv data/conversations.jsonl --limit 5000

Sampling is deterministic for a given --seed, and eventId is a content hash, so re-running the
script produces identical events (which is what makes replay-twice dedupe testable).
"""
import argparse
import csv
import hashlib
import json
import random
import sys
from collections import defaultdict
from datetime import datetime, timezone

TS_FORMAT = "%a %b %d %H:%M:%S %z %Y"


def parse_ts(s):
    return datetime.strptime(s, TS_FORMAT).astimezone(timezone.utc)


def load(path):
    tweets = {}
    children = defaultdict(list)
    roots = []
    csv.field_size_limit(10**8)
    with open(path, encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            tid = row["tweet_id"]
            inbound = row["inbound"] == "True"
            parent = row["in_response_to_tweet_id"]
            tweets[tid] = (row["author_id"], inbound, row["created_at"], row["text"])
            if parent:
                children[parent.split(".")[0]].append(tid)
            elif inbound:
                roots.append(tid)
    return tweets, children, roots


def thread(root, tweets, children, max_turns):
    seen, stack, ids = set(), [root], []
    while stack:
        tid = stack.pop()
        if tid in seen or tid not in tweets:
            continue
        seen.add(tid)
        ids.append(tid)
        stack.extend(children.get(tid, ()))
    ids.sort(key=lambda t: parse_ts(tweets[t][2]))
    return ids[:max_turns]


def build_event(root, ids, tweets):
    turns, brand = [], None
    for tid in ids:
        author, inbound, ts, text = tweets[tid]
        if not inbound and brand is None:
            brand = author
        turns.append({
            "speaker": "CUSTOMER" if inbound else "AGENT",
            "text": text.strip(),
            "ts": parse_ts(ts).isoformat().replace("+00:00", "Z"),
        })
    content = "\x1f".join(f"{t['speaker']}:{t['text']}" for t in turns)
    event_id = hashlib.sha256(f"twcs:{root}:{content}".encode("utf-8")).hexdigest()[:32]
    return {
        "eventId": event_id,
        "conversationId": f"twcs-{root}",
        "channel": "twitter",
        "customerId": f"twcs-user-{tweets[root][0]}",  # anonymised Twitter author id from the corpus
        "brand": brand,
        "source": "twcs",
        "startedAt": turns[0]["ts"] if turns else None,
        "turns": turns,
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("input")
    ap.add_argument("output")
    ap.add_argument("--limit", type=int, default=5000, help="number of conversations to emit")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--max-turns", type=int, default=12)
    ap.add_argument("--min-turns", type=int, default=2)
    ap.add_argument("--sort-by-time", action="store_true",
                    help="write events in conversation start order (for event-time analytics demos)")
    args = ap.parse_args()

    tweets, children, roots = load(args.input)
    print(f"loaded tweets={len(tweets)} root customer tweets={len(roots)}", file=sys.stderr)

    roots.sort(key=int)
    random.Random(args.seed).shuffle(roots)

    skipped = 0
    events = []
    for root in roots:
        if len(events) >= args.limit:
            break
        ids = thread(root, tweets, children, args.max_turns)
        event = build_event(root, ids, tweets)
        has_agent = any(t["speaker"] == "AGENT" for t in event["turns"])
        if len(event["turns"]) < args.min_turns or not has_agent or not all(t["text"] for t in event["turns"]):
            skipped += 1
            continue
        events.append(event)
    if args.sort_by_time:
        events.sort(key=lambda e: e["startedAt"])

    brands = defaultdict(int)
    with open(args.output, "w", encoding="utf-8", newline="\n") as out:
        for event in events:
            out.write(json.dumps(event, ensure_ascii=False) + "\n")
            brands[event["brand"]] += 1
    written = len(events)

    top = sorted(brands.items(), key=lambda kv: -kv[1])[:10]
    print(f"written={written} skipped={skipped} brands={len(brands)} top={top}", file=sys.stderr)


if __name__ == "__main__":
    main()
