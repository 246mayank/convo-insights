# PII redaction audit over 20,000 real conversations

`PiiRedactor` was run over every turn of `data/conversations.jsonl` (20,000 TWCS conversations), and every
replacement was inspected: what was redacted, as which type, and what survived. Triggered by inconsistencies
noticed while hand-labelling the first 50 conversations. Tool: a throwaway audit main over the compiled classes.

## What was wrong (redactor v1)

| Problem | Example | Effect |
|---|---|---|
| Numeric user handles caught by the phone rule (it ran before the handle rule) | `@8005551234` → `@[PHONE]` | Wrong type; handle broken |
| Amazon order numbers pass the Luhn check by chance | `402-8073552-0393941` → `[CARD]` (12 cases) | Orders reported as card numbers |
| Phone rule knew only the US 3-3-4 shape | `0345 493 0787` → `[PHONE] 493 0787` | **Digits left behind** |
| Country code + 1-digit area code not matched | `+66 2 515 9999` | **Not redacted** |
| Numbers glued to letters skipped by the word-boundary rules | `Rep ID: i1281873914`, `number4026785388-1769969` | **Not redacted** |
| Hashtags with a digit taken for reference ids | `#CSWEEK2017` → `[ID]` | Over-redaction |
| Company toll-free numbers redacted | `1-800-869-3557` → `[PHONE]` | Lost useful, non-personal context |
| Names never redacted | `Hi Becca, please DM us` | **Personal data left in** |

## What changed (redactor v2)

- Order: email → URL → **handle** → card → phone → reference id → **long-number safety net** → **name**.
- Card: only 13–19 digits in a row or groups of four, and Luhn-valid.
- Phone: any shape with 9–15 digits (country code, area code in brackets, groups of 1–5), except toll-free
  business numbers (North American 8xx, UK 0800/0808).
- Reference id: as before, but not ISO dates, not hashtags that contain letters, not toll-free numbers.
- Safety net: any run of 9+ digits that survived the rules above becomes `[ID]`, even when glued to letters.
- Name: a capitalised first name right after a greeting or thanks ("Hi", "Hello", "Hey", "Dear", "Thanks",
  "Sorry", …), with a stop list ("there", "all", "team", brand names, …).

## Before and after, same 20,000 conversations

| | v1 | v2 |
|---|---:|---:|
| Handles | 75,087 | 75,090 |
| URLs | 15,865 | 15,865 |
| Phones | 617 | 482 |
| Cards | 12 | **0** (all were order numbers) |
| Reference ids | 337 | 323 |
| Names | 0 | **3,826** |
| Runs of 10+ digits left in the redacted text (excluding toll-free) | many, incl. partial phones | **0** |

Each problem above has a test in `PiiRedactorTest.auditFindings`. 127 tests pass (118 unit, 9 integration).

## Known limits

- Names are only caught after a greeting; a name elsewhere in a sentence, or a street address, still needs
  NER (named-entity recognition).
- Vanity numbers such as `1-800-JETBLUE` are redacted as reference ids. Harmless, but not needed.
- The official run (`bench/run-official.md`) and the bake-off (`eval/BAKEOFF.md`) used v1. The gate baseline was
  re-measured after this change.
