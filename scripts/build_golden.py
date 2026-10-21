"""Build the synthetic golden set from the Bitext customer-support dataset.

Takes a stratified sample (same number of rows per Bitext category, fixed seed), fills the
{{placeholders}} Bitext leaves in its text with realistic values, and writes ConversationEvents
with the Bitext category as the expected intent.

Only the customer's message is used. Bitext's agent replies usually restate the request
("I understand you want to cancel your order"), which would leak the label to the model.

    python scripts/build_golden.py ../data-raw/bitext/bitext.csv eval/golden.jsonl --per-category 18 --total 200
"""
import argparse
import csv
import hashlib
import json
import random
import re
import sys
from collections import defaultdict

# Bitext category -> our Intent. They are the same names by design.
CATEGORIES = ["ACCOUNT", "CANCEL", "CONTACT", "DELIVERY", "FEEDBACK", "INVOICE",
              "ORDER", "PAYMENT", "REFUND", "SHIPPING", "SUBSCRIPTION"]

FILL = {
    "order number": ["4471-2290", "A10293384", "ORD-558120"],
    "invoice number": ["INV-20931", "77120-B"],
    "account type": ["premium", "business", "family"],
    "account category": ["gold", "standard"],
    "person name": ["Priya", "Daniel", "Ana"],
    "customer support phone number": ["1-800-555-0199"],
    "customer support hours": ["9am to 6pm"],
    "website url": ["www.example-shop.com"],
    "delivery city": ["Pune", "Austin"],
    "delivery country": ["India", "Canada"],
    "refund amount": ["$45", "Rs 1,200"],
    "currency symbol": ["$"],
    "money amount": ["$30", "120 euros"],
    "online order interaction": ["the order page"],
    "online company portal info": ["the customer portal"],
    "online customer support channel": ["live chat"],
    "settings": ["account settings"],
    "profile": ["profile page"],
    "client first name": ["Sam"],
    "client last name": ["Lee"],
}
PLACEHOLDER = re.compile(r"\{\{\s*([^}]+?)\s*\}\}")


def fill(text, rng):
    def sub(m):
        key = m.group(1).strip().lower()
        options = FILL.get(key)
        return rng.choice(options) if options else "it"
    return PLACEHOLDER.sub(sub, text)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("input")
    ap.add_argument("output")
    ap.add_argument("--per-category", type=int, default=18)
    ap.add_argument("--total", type=int, default=200)
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    rng = random.Random(args.seed)
    by_cat = defaultdict(list)
    with open(args.input, encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            if row["category"] in CATEGORIES:
                by_cat[row["category"]].append(row)

    picked = []
    leftovers = []
    for cat in CATEGORIES:
        rows = sorted(by_cat[cat], key=lambda r: r["instruction"])
        rng.shuffle(rows)
        picked += rows[:args.per_category]
        leftovers += rows[args.per_category:]
    rng.shuffle(leftovers)
    picked += leftovers[:max(0, args.total - len(picked))]

    with open(args.output, "w", encoding="utf-8", newline="\n") as out:
        for i, row in enumerate(picked):
            text = fill(row["instruction"], rng)
            event_id = hashlib.sha256(f"bitext:{i}:{text}".encode("utf-8")).hexdigest()[:32]
            out.write(json.dumps({
                "event": {
                    "eventId": event_id,
                    "conversationId": f"bitext-{i:03d}",
                    "brand": None,
                    "source": "bitext",
                    "turns": [{"speaker": "CUSTOMER", "text": text, "ts": None}],
                },
                "expected": {"intent": row["category"], "bitextIntent": row["intent"]},
            }, ensure_ascii=False) + "\n")

    counts = defaultdict(int)
    for r in picked:
        counts[r["category"]] += 1
    print(f"wrote {len(picked)} rows: {dict(sorted(counts.items()))}", file=sys.stderr)


if __name__ == "__main__":
    main()
