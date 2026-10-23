# ADR 0005: Redact PII before any model sees the text

**Status:** accepted · 21 Oct 2026

## Context

Support conversations contain emails, phone numbers, card numbers, order and account references, and user handles.
Sending them to a model, local or hosted, widens who can see them: model logs, provider retention, prompt-injection
leakage into summaries. Privacy should not depend on which provider is configured.

## Decision

- `PiiRedactor` runs in `InsightService` before extraction, on every turn. It applies, in this order: email, URL, card
  number (13–19 digits that **pass the Luhn check**), phone, reference id (8+ characters of capitals, digits and
  dashes, containing a digit), handle (`@name`). Matches become typed tags (`[EMAIL]`, `[CARD]`, …), and the prompt
  tells the model what they mean.
- The order matters: an email is redacted before its `@` can be taken for a handle.
- Each insight stores how many redactions were made. Metrics count them by type.
- The stored insight keeps the original event metadata. Only the text sent to the extractor is redacted.
- Webhook customers are identified by a SHA-256 hash of their address (`tw-…`), never the phone number.

## Consequences

- No extractor ever receives raw personal data; an integration test asserts the scripted model never sees the email
  or phone that was sent. Property tests (jqwik) check thousands of generated emails and Luhn-valid card numbers, and
  that redaction is idempotent.
- A hosted model (Gemini) can be used without sending personal data.
- Some meaning is lost: the model sees `[ID]`, not which order. Regexes miss free-form PII (names, street addresses);
  production would add an NER (named-entity recognition) model.
- In the official run, all 4,000 conversations had at least one redaction (mostly handles), 18,479 in total.
