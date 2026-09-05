# AI Client Onboarding & Document Review

Standalone hardening of the existing service demo: **English agency intake forms only**, preserving the original eight fields and adding service-table rows (`description`, `quantity`, `unitPrice`). No SaaS, accounts, billing, or automatic communication.

Upload → page text → structured extraction → OCR fallback → field review → durable audit/result → optional Google Sheets outbox.

The existing `/api/onboarding` form now uses this local pipeline. The legacy n8n export is preserved and is not called. No live n8n workflow, Aarambh system, or shared Sheet was changed.

## What changed

- All PDF pages are processed, including mixed digital/scanned pages. Table rows retain order and duplicates; unreadable cells and ambiguous tables enter review.
- Local RapidOCR fallback runs on images, failed/weak page text, low-confidence fields and incomplete tables.
- Model timeout, rate-limit and malformed-response retries use bounded exponential backoff. SHA-256 deduplication and submission-ID aliases persist in H2 across restarts. Explicit reprocessing updates the same record and retains earlier attempts.
- Partial results survive page/field failures. Each field records value, confidence/source, status, evidence and page numbers. Conflicts or scores below `0.85` route to `MANUAL_REVIEW`; genuine absence routes to `MISSING_INFORMATION`.
- Extraction attempts and final results are audited. Optional Sheets delivery uses durable, fixed row assignments and `RAW` updates, never append. Failed delivery remains pending.
- The accuracy harness measures per-field, full-document and routing accuracy, plus accepted-field precision/coverage. Failed documents and extra table rows count against the score.

## Run locally

PowerShell, from this folder. Requires Java 21, Maven and Python 3.12. OCR installs inside this project on D:.

```powershell
python -m pip install --upgrade --target .runtime/python --cache-dir .runtime/pip-cache -r scripts/ocr-requirements.txt
& 'D:\Programs\Maven\apache-maven-3.9.16\bin\mvn.cmd' verify
python -m unittest discover -s scripts -p test_accuracy.py -v
java -jar target/ai-client-onboarding-review-0.0.1-SNAPSHOT.jar
```

Open `http://127.0.0.1:8091`. Default provider `local` is a **labelled-form rules baseline**, not Gemini and not an AI accuracy claim. It needs no credentials and sends no documents externally. Its scores are rule heuristics.

For AI extraction, set `EXTRACTION_PROVIDER=gemini`, `EXTRACTION_GEMINI_API_KEY` through your runtime secret store, and optionally `EXTRACTION_GEMINI_MODEL` (default `gemini-3.6-flash`) before starting. Only this explicitly selected mode sends page text/OCR text to Gemini. Model scores are self-reports, not calibrated probabilities. Gemini transport has local HTTP contract tests and was exercised against the live API with a demo key on the synthetic corpus (93.18% field accuracy, `output/accuracy/synthetic-gemini.json`); real-document accuracy is still unmeasured. The OCR fallback path calls the model twice per page, so real volume needs a paid Gemini quota tier — the free tier rate-limits under batch load. `.env.example` lists configuration names; Spring does not automatically load `.env` files.

## Accuracy test when you add real documents

1. Put your sample PDF/PNG/JPEG intake forms in `samples/private/documents/`.
2. Copy `samples/answer-key.example.json` to `samples/private/answer-key.json`. Independently label every document: all eight fields, `null` for absent values, all ordered line-item rows (or `[]`) and expected routing. Do not generate the key from model output.
3. Start the server in the provider mode being evaluated, then run:

```powershell
python scripts/accuracy.py --samples samples/private/documents --key samples/private/answer-key.json --expect-provider gemini/gemini-3.6-flash --output output/accuracy/real-intake.json
```

For a local baseline, replace the expected provider with `local-labelled-intake-v1`. The harness forces fresh extraction into the same canonical record/Sheet row. Use a standalone benchmark store, not one bound to a live Sheet. JSON contains each mismatch and confidence; the adjacent Markdown contains the summary. Empty datasets, unlabelled files and duplicate document bytes are rejected. Free-text scoring is normalized exact match, not semantic similarity; table row order matters.

Reproduce the fictional benchmark after `mvn verify`:

```powershell
python scripts/accuracy.py --samples target/accuracy-fixtures --key target/accuracy-answer-key.json --expect-provider local-labelled-intake-v1 --output output/accuracy/synthetic-local.json
```

**Real-document accuracy is not yet measured.** Synthetic fixtures establish mechanics; the local baseline and Sheets outbox are mock/rule-tested, and Gemini has one live run on synthetic data, not production accuracy or live-provider readiness. Use independently labelled real intake forms before making a paid accuracy claim.

## Review, audit and optional Sheets

- `POST /api/extractions`: multipart `document`, optional `submissionId`; `?reprocess=true` reruns extraction into the same record.
- `GET /api/extractions/{id}` and `/{id}/audit`: full structured result and attempt history.
- `GET /api/extractions/review-queue`: persisted documents with the exact field/cell paths needing review.
- `GET /api/extractions/pending-sheet`, `POST /api/extractions/sync-sheet`: inspect/drain the durable outbox. Sync is disabled until explicitly configured.

See [operating details](docs/extraction.md) for Sheet setup, confidence rules, limits, recovery and test evidence. Runtime data, OCR dependencies, private samples and accuracy reports are git-ignored. Keep the H2 store: deleting it loses deduplication history and Sheet row ownership.
