# Where naive document extraction breaks at volume

Every "AI reads your documents" demo looks the same: upload a PDF, get back JSON, ship it. It works great for the first ten documents in a sales call. It breaks in a specific, predictable way once real volume hits it — and the failure is almost never the model call itself. It's everything around that call the demo skipped.

I built a document-extraction pipeline for agency intake forms, then ran the same approach against a real, independently-labelled dataset (60 SROIE receipts) to get an honest accuracy number instead of a demo-only one: **81.67% field accuracy, no cherry-picking** ([full methodology](../README.md#real-receipts--a-different-schema-run-as-isolated-proof)). Getting from "works on my test PDF" to "I'd trust this in front of a client" meant closing four gaps that every naive version of this has. Here's where each one actually bites.

## 1. No retry semantics

LLM APIs fail transiently — timeouts, rate limits, 5xx, an occasional malformed JSON response. A demo's single try/catch either crashes the request or silently returns nothing. Neither is acceptable once you're processing more than a handful of documents a day.

The real fix isn't "retry everything" either — that just burns quota on failures that will never succeed twice. A malformed-schema response from the model is a different kind of failure than a 429; retrying the first with the same input is pointless, retrying the second is exactly right. This pipeline classifies every failure as retryable or permanent, retries up to three times per page with exponential backoff plus jitter, and honors the provider's own `Retry-After` header when it sends one.

This distinction mattered in practice, not just in theory: Gemini's free tier is capped at exactly 20 requests/day per project per model. Retrying a hard failure three times for nothing would burn a sixth of that budget on a single bad page. Getting the retryable/permanent classification right is the difference between a pipeline that degrades gracefully under quota pressure and one that quietly runs out of requests by lunch.

## 2. No idempotency — reprocessing double-writes

A webhook retries. A user double-clicks upload. An agency's own workflow re-fires on a document after timing out on a response that actually succeeded. A naive pipeline processes the same document twice and produces two output rows, two downstream writes, two CRM records for one real document — and nobody notices until someone's reconciling numbers.

The fix is treating the document's own content as its identity, not whatever ID the caller happens to send. This pipeline hashes the raw bytes (SHA-256) and uses that hash as the real key: identical bytes arriving under a brand-new ID resolve to the same canonical record instead of creating a duplicate, and a genuine cache hit returns instantly with its own audit entry rather than re-running the model. Reusing an ID for genuinely different bytes is rejected outright (HTTP 409), not silently overwritten. The same discipline extends downstream — the optional spreadsheet delivery writes to one fixed row per canonical record with an idempotent update, never an append, so a retried sync can't create a second row for the same document either.

## 3. No confidence gating — bad extractions flow silently downstream

This is the failure mode that actually costs money. A model will return a wrong value in exactly the same JSON shape as a right one — nothing in the response format tells you which. A pipeline that forwards `response.json()` straight to a CRM or a client-facing dashboard ships wrong data with the identical confidence as right data. It's invisible until someone downstream catches it, by which point it's already been acted on.

Every field in this pipeline carries a value, a confidence score, and an evidence string — the literal quote the model claims it's reading the value from. That evidence has to actually appear in the source text; a value with confident-sounding evidence that isn't a real substring of the page gets its score zeroed regardless of what the model self-reported. Below a threshold, or ungrounded, or failing basic format validation (a real email pattern, a parseable date, a positive quantity), the field is routed to review instead of accepted. Multi-page documents get one more layer: the same field showing up with two different values across pages isn't "last write wins," it's flagged as a genuine conflict for a person to resolve.

That routing is the entire reason a review queue exists — it's the release valve for everything gating catches, so a wrong value gets a chance to be caught before a client sees it instead of after.

## 4. No audit trail — when a client disputes a figure

Eventually someone asks: "your system told us the budget was $4,200 — the document actually says $6,500, why did we get the wrong number?" A pipeline with nothing logged has no answer beyond "the model said so," which isn't an answer an agency can give a client and expect to keep the account.

Every stage here — the page text extraction, the OCR fallback when text extraction fails, every model attempt (including the raw response when it's malformed), and every human correction — writes an append-only, timestamped event against the document's canonical ID. A disputed figure has a real, reconstructable history: what the model actually returned, why it was accepted or flagged, and if a person corrected it, exactly who changed what and when. Corrections made through the review UI write their own distinct audit event, separate from the model's own attempts, so "a human overrode this" is never confused with "the model got it right."

## The number, stated honestly

81.67% field accuracy is the real one — 60 real receipts, independently labelled ground truth, no cherry-picking (company 81.67%, date 91.67%, address 55.0%, total 98.33%). Separately, 93.18% is what the same approach scores on synthetic intake-form fixtures — mechanics-only proof, not a real-document number for that schema. Those two are never blended into one claim. If a pipeline's accuracy story doesn't survive being broken into "measured on what, exactly" — it isn't a number yet.

## If you're already selling automation to clients

None of the four gaps above are exotic engineering. They're the parts that are easy to skip in a two-week prototype and expensive to add after a client's first billing dispute or duplicate CRM record. If you're an agency that already sells automation and the document-handling piece of a client project is the part you're least sure holds up past the demo, that's the specific gap this closes. Code, real accuracy numbers, and the review queue in action: [github.com/apurvatripath/ai-client-onboarding-review](https://github.com/apurvatripath/ai-client-onboarding-review).
