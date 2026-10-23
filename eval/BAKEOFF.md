# Extractor bake-off

**Current numbers (redaction v2, commit `5c0279a` and later):**

| Dataset | Extractor | n | Intent acc. | Intent macro-F1 | Sentiment acc. | p50 ms | p95 ms | Fallback |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| golden | qwen2.5:0.5b@v1 | 200 | 41.0% | 0.398 |  | 867 | 1015 | 0.0% |
| golden | qwen2.5:1.5b@v1 | 200 | 75.0% | 0.763 |  | 1791 | 2064 | 0.0% |
| golden | qwen2.5:3b@v1 | 200 | 85.0% | 0.864 |  | 3133 | 3570 | 0.0% |
| golden | rules | 200 | 62.0% | 0.720 |  |  |  | 0.0% |
| twcs | qwen2.5:0.5b@v1 | 48 | 10.4% | 0.199 | 56.2% | 1154 | 1563 | 0.0% |
| twcs | qwen2.5:1.5b@v1 | 48 | 27.1% | 0.163 | 79.2% | 2421 | 2953 | 0.0% |
| twcs | qwen2.5:3b@v1 | 48 | 39.6% | 0.271 | 70.8% | 4017 | 5502 | 0.0% |
| twcs | rules | 48 | 33.3% | 0.331 | 56.2% |  |  | 0.0% |

These replace the first run below. Redaction v2 (`bench/redaction-audit-20261023.md`) changes the text the model sees
(names become `[NAME]`, phones and ids are tagged differently), and a small model's borderline answers move with it:
for qwen2.5:3b, 7 of 48 real-tweet predictions changed (2 went from right to wrong, 0 the other way; 5 were wrong
both times) and 5 of 200 golden predictions changed. On a 48-row set, 2 rows is within noise. The ranking and the
decision are unchanged. Results: `eval/results/v2/`.

## First run (redaction v1, 21 Oct)

Every extractor was run through the same harness (`EvalMain`) on two labelled sets:

- **golden**: 200 synthetic customer messages from the Bitext dataset, stratified across its 11 categories (`eval/golden.jsonl`). Bitext's labels are its own categories; no sentiment labels.
- **twcs**: 48 real Twitter support conversations hand-labelled by Mayank (`eval/twcs-labels.jsonl`, 50 labelled, 2 marked "not sure" and excluded). Intent *and* sentiment labels.

Conditions: 21 Oct 2026, i7-12700H, 16 GB RAM, CPU only (no GPU), Ollama 0.40.7, temperature 0, one request at a time.
Latency is the time spent waiting on the model per conversation.

| Dataset | Extractor | n | Intent acc. | Intent macro-F1 | Sentiment acc. | p50 ms | p95 ms | Fallback |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| golden | qwen2.5:0.5b@v1 | 200 | 35.5% | 0.359 |  | 743 | 835 | 0.0% |
| golden | qwen2.5:1.5b@v1 | 200 | 72.5% | 0.735 |  | 1747 | 2003 | 0.0% |
| golden | qwen2.5:1.5b@v2 | 200 | 50.0% | 0.508 |  | 1220 | 1682 | 0.0% |
| golden | qwen2.5:3b@v1 | 200 | 86.0% | 0.871 |  | 2835 | 3224 | 0.0% |
| golden | rules | 200 | 62.0% | 0.720 |  |  |  | 0.0% |
| twcs | qwen2.5:0.5b@v1 | 48 | 8.3% | 0.126 | 56.2% | 1056 | 1391 | 0.0% |
| twcs | qwen2.5:1.5b@v1 | 48 | 25.0% | 0.163 | 79.2% | 2339 | 2984 | 0.0% |
| twcs | qwen2.5:1.5b@v2 | 48 | 14.6% | 0.153 | 70.8% | 2131 | 2708 | 0.0% |
| twcs | qwen2.5:3b@v1 | 48 | 43.8% | 0.307 | 70.8% | 4163 | 5165 | 0.0% |
| twcs | rules | 48 | 33.3% | 0.331 | 56.2% |  |  | 0.0% |

## Decision

**Default: `qwen2.5:3b` with prompt v1.** (Figures in this section are from the first run; v2 confirms the choice: 85.0% golden and 39.6% real vs 62.0% and 33.3% for the rules.)

- 3B is the only model that beats the keyword rules on *both* sets: +24 points on golden (86.0% vs 62.0%) and +10.5 points on real tweets (43.8% vs 33.3%).
- 1.5B wins on the synthetic set but loses to the rules on real intent (25.0%). It has the best sentiment accuracy (79.2%), which shows that sentiment and intent difficulty don't move together.
- 0.5B is fast (p95 under 1.4 s) but below the rules everywhere, so it isn't useful here.
- Cost of 3B: p95 latency of 3.2 s (golden) to 5.2 s (real, longer threads), about 1.7x the 1.5B model.

## Prompt v2: a measured regression

Prompt v2 added intent definitions that de-emphasise the agent's replies, plus three synthetic few-shot examples. With 1.5B it **dropped** accuracy to 50.0% (golden) and 14.6% (twcs), with 13 of 16 technical-support threads labelled ACCOUNT. Probes ruled out JSON field order and enum order as causes. The likely cause is the example text ("DM us your account number") being copied by a small model. v1 stays the production prompt. This is the kind of change the CI regression gate exists to block.

## Caveats

- The twcs set is small (48 rows), so one conversation is about 2 points.
- v2 was written after looking at v1's aggregate confusion on the twcs set, so twcs is a dev set, not a clean held-out test set.
- Real-tweet intent accuracy is far below synthetic accuracy. Bitext messages are short and single-purpose; real threads mix venting, multiple issues and agent boilerplate.
- Per-row TWCS results are not committed, because they contain tweet-derived text (CC BY-NC-SA). Only the aggregates above are.
