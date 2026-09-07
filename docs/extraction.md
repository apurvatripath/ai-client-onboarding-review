# Extraction operation and verification

## Scope and limits

One English agency intake form per upload, PDF/PNG/JPEG, maximum 5 MiB; PDF maximum 20 pages; rendered/image page maximum 12 megapixels; extracted text maximum 40,000 characters per page; model output maximum 200,000 characters and 100 table rows per page. Password-protected, damaged and unsupported documents are rejected. Failed pages retain other pages and force review. One process owns the embedded H2 store and serializes extraction to bound memory/model concurrency. The service binds to loopback by default; no account system or distributed workers were added.

The local baseline recognizes the existing English field labels and labelled service tables. Gemini provides flexible structured extraction; local mode is an offline baseline, not a silent replacement for a failed AI call. Wrapped/merged cells, handwriting, unexpected layouts and bad scans require inspection and real-corpus evaluation. Content spanning pages is not silently concatenated into a confident answer: contradictory field values remain conflicts. Table rows continue in document order; repeated headers are excluded and repeated actual rows retained.

## Fields and routing

Original fields: `companyName`, `contactName`, `contactEmail`, `serviceRequested`, `budget`, `targetLaunchDate`, `projectSummary`, `dependencies`. Unambiguous dates normalize to ISO; email case normalizes. Budget remains a string preserving currency/scope wording. Service table cells: `description`, `quantity`, `unitPrice`.

Each field/cell has `value`, `confidence`, `confidenceSource`, `status`, `pages`, `evidence`. Status is `ACCEPTED`, `MISSING`, `LOW_CONFIDENCE`, `FAILED`, `INVALID`, `UNSUPPORTED`, or `CONFLICT`. Non-null values require evidence present in page text; evidence/format checks can reject a high model score. OCR-derived confidence is capped by the lowest recognized text-box score on that page, a conservative floor rather than a calibrated per-cell probability.

Low confidence (below 0.85), malformed fields or incomplete tables first trigger OCR. Good fields survive; unresolved fields enter the queue. Genuine absence yields `MISSING_INFORMATION`; any failed/low-confidence/conflicting field or page/table problem yields `MANUAL_REVIEW`, even if other fields are missing. Otherwise the result is `COMPLETE`. An optional table with no rows is valid only when none is detected. Follow-up drafts retain approval `PENDING`; nothing sends a message.

The compatibility onboarding endpoint additionally compares submitted form values against extracted fields. Conflicts are persisted and audited. Reprocessing the document alone resets extraction; resubmit the form to reapply its comparison. Its original multipart fields and success response remain compatible with the existing UI.

## Human review UI

`/review.html` lists every submission whose `reviewQueue` is non-empty and not yet `APPROVED`, and shows the source document beside the editable extracted fields/line items for the selected one. `GET /api/extractions/{id}/document` serves the original bytes (content-type sniffed from the file, not stored separately); documents processed before this feature was added have no stored bytes and the page says so instead of showing a blank frame. `POST /api/extractions/{id}/review` applies corrections (each corrected field/cell is set to `ACCEPTED`, confidence `1.0`, source `human-review`) and, when `approve:true`, sets `approvalStatus` to `APPROVED`; approval clears the queue item even if permanent page-level issues remain on the record, since those are no longer something a person still needs to act on. Every correction is audited as its own `HUMAN_REVIEW` event alongside the existing pipeline stages.

## Durability and recovery

`EXTRACTION_DATA_DIR` defaults to `data/extraction/`. H2 persists hashes, ID aliases, results, append-only application audit events and Sheet row assignments with synchronous writes. H2 prevents a second process using the same database. Repeat bytes return the canonical result even under a new ID. Reusing an ID for different bytes returns HTTP 409 on the extraction API. An interrupted reservation can resume with the same ID; an interrupted attempt remains visible as a start without a finish.

Normal repeats use the cache. To retry a partial result after repairing a provider/OCR issue, upload the same bytes/ID with `?reprocess=true`; it replaces the result, marks the same Sheet row pending, and retains previous events. Harness runs always reprocess. Back up the entire data directory with the process stopped. Do not delete the store while keeping its Sheet: that loses ownership/deduplication history.

Model attempts: maximum three per page/path, 45-second HTTP timeout, 10-second connect timeout, exponential backoff starting at 500 ms plus jitter; Retry-After seconds are honored up to 30 seconds. Timeout/429/408/5xx and malformed JSON retry. Permanent HTTP failures do not repeat on that path. OCR is one local subprocess per fallback page with a 90-second limit; temporary images/logs are deleted afterward. Problematic documents can take several minutes; harness timeout is configurable with `--timeout`.

Audit records include UTC timestamps, SHA-256 input hash, page/path, attempt ID, provider, raw structured output (including malformed output), validated field/cell confidence, retry decision and final routing. Provider error responses use safe error codes. Audit/benchmark output can contain document data: these are private working files, excluded from Git, not portfolio assets. This is an application audit history, not a tamper-proof compliance ledger.

## Optional isolated Google Sheets delivery

No live Sheet was used in this pass. The new path never calls the old n8n append node or uses `N8N_*` settings.

Create a separate demo spreadsheet and blank `ExtractionDemo` tab with enough rows (for example 10,000). Use a demo-only service account with Sheets API enabled and access only to that spreadsheet. Keep its credential JSON outside this repository. Set:

- `EXTRACTION_SHEETS_ENABLED=true`
- `EXTRACTION_DEMO_SPREADSHEET_ID`: separate demo spreadsheet ID
- `EXTRACTION_SHEETS_SERVICE_ACCOUNT_FILE`: absolute credential-file path
- Optional `EXTRACTION_DEMO_SHEET_TAB` (default `ExtractionDemo`)

Service-account tokens refresh against the fixed Google OAuth endpoint. A short manual session can use `EXTRACTION_SHEETS_ACCESS_TOKEN` instead; refresh that token yourself if it expires. Never save credential/token values in notes or tracked files.

Call `POST /api/extractions/sync-sheet` to drain up to 100 pending results. An operator/scheduler can repeat this after uploads; extraction never depends on Sheet availability. Sync validates/creates headers and writes `A:R` at each reserved row using `values.update` with `RAW`. Uncertain outcomes retry the same row. A later re-extraction cannot be marked delivered by an older in-flight write. A changed Sheet binding, unexpected header, or row owned by another ID fails with records pending.

Keep the tab machine-owned: no sorting, inserted/deleted rows or concurrent external writers. Sort in another view/tab. A single durable writer plus persistent row ownership is the idempotency boundary, not an exactly-once guarantee across unrelated writers or a lost database. Sheets holds the eight values/confidences, status, review paths and line-item count; full cell data/attempts remain in the audit store, keyed by submission ID.

## Verification and remaining evidence

Local tests cover multi-page fields/tables, missing fields, cross-page conflicts, timeout/malformed-response retries, permanent/rate-limit HTTP classification, partial pages/cells, evidence grounding, low-confidence OCR routing, actual image OCR, concurrent duplicates, restart/alias deduplication, explicit reruns and lost Sheet acknowledgements. Python tests cover denominators, extra rows, nulls, confidence coverage and routing scores. `SyntheticCorpusTest` creates the fictional corpus for the real HTTP harness.

The executable and existing onboarding endpoint were exercised locally. A scanned PNG and mixed PDF used actual RapidOCR. ONNX Runtime 1.22.1 failed DLL initialization on this Windows host; pinned 1.20.1 passed. Use the README's direct `python -m pip` command if PowerShell blocks `.ps1` scripts; no system execution-policy change is needed.

No real documents were supplied. Sheets transport still has mock-server coverage only; service-account OAuth/Sheet permissions still need a separate demo credential. Gemini has one live-API run with a demo key against the synthetic corpus (93.18% field accuracy; two misses traced to a real free-tier 429 during the OCR fallback path, and to the live model omitting required schema keys for genuinely-absent fields instead of returning null, which the pipeline correctly treats as `FAILED` rather than assumed `MISSING`). Real-document accuracy still needs independently labelled real intake forms. Do not describe the synthetic score as real-client evidence or the deployment as production-validated.

References: [PDFBox](https://pdfbox.apache.org/3.0/migration.html), [Gemini structured output](https://ai.google.dev/gemini-api/docs/generate-content/structured-output), [Sheets fixed-range updates](https://developers.google.com/workspace/sheets/api/reference/rest/v4/spreadsheets.values/update), [ONNX Runtime installation](https://onnxruntime.ai/docs/install/).
