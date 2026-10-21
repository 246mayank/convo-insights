"""Summarise eval result files into a markdown table.

    python scripts/eval_report.py eval/results/*.json > eval/REPORT.md

Reads only the aggregate metrics, so the output is safe to commit even for TWCS runs.
"""
import json
import sys


def pct(v):
    return "" if v is None else f"{100 * v:.1f}%"


def main(paths):
    runs = []
    for p in paths:
        with open(p, encoding="utf-8") as f:
            r = json.load(f)
        m = r["metrics"]
        runs.append((r["dataset"], r["run"], m, r.get("wallSeconds")))
    runs.sort(key=lambda x: (x[0], x[1]))

    print("| Dataset | Extractor | n | Intent acc. | Intent macro-F1 | Sentiment acc. | p50 ms | p95 ms | Fallback |")
    print("|---|---|---:|---:|---:|---:|---:|---:|---:|")
    for dataset, run, m, _ in runs:
        print(f"| {dataset} | {run} | {m['n']} | {pct(m['intentAccuracy'])} | {m['intentMacroF1']:.3f} | "
              f"{pct(m.get('sentimentAccuracy'))} | {m.get('latencyMsP50', '')} | {m.get('latencyMsP95', '')} | "
              f"{pct(m['fallbackRate'])} |")


if __name__ == "__main__":
    main(sys.argv[1:])
