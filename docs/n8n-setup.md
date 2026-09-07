# n8n setup

## Current pipeline: extraction + review queue template

`.n8n/extraction-pipeline-with-review-queue.json` wraps this repo's actual REST API (`POST /api/extractions`, the review queue, `/review.html`) as an importable n8n workflow — this is the shape an agency would actually drop into a client's automation, not a from-scratch rebuild in n8n itself.

Flow: a webhook receives the document → an HTTP Request node calls this pipeline's `POST /api/extractions` (multipart, same as any direct upload) → branches on the returned `reviewStatus` → `COMPLETE` continues straight into whatever the rest of the client's workflow already does; anything else builds a direct link to `/review.html?id=<submissionId>` (using the deep-link support added for this) and posts it to a notification step, so a human lands on the exact flagged record with one click, not a general queue page. Either path responds to the original webhook with the full extraction result.

To import:

1. Import the JSON into a self-hosted n8n instance.
2. Set the `EXTRACTION_SERVICE_URL` environment variable on the n8n instance to wherever this Spring Boot service is actually reachable (defaults to `http://localhost:8091` if unset).
3. Replace `CONFIGURE_AFTER_IMPORT` on the **Notify Reviewer** node's URL with a real Slack/Discord/email webhook — this is intentionally left generic since that choice is per-agency, per-client.
4. Point whatever currently sends documents into the client's workflow at this workflow's webhook path (`/webhook/document-review`) instead of directly at `/api/extractions`, if a client's workflow needs to sit in front of the pipeline rather than call it directly.

Not import-tested against a live n8n instance as of writing — validated by hand against the current n8n node schemas (webhook, HTTP Request v4.2, IF, Set, Respond to Webhook) and the JSON parses and connects correctly, but treat the exact `httpRequest` multipart parameter shape as worth a real import check before relying on it.

## Legacy demo (do not use)

**Legacy path only.** The current local hardening build uses Spring Boot extraction and the durable Sheet outbox in `extraction.md`. Do not import or activate this workflow for the hardening build. This retained guide documents the earlier demo, not the current idempotent path.

Import `.n8n/ai-client-onboarding-document-review.json` into the self-hosted n8n instance, then configure it without placing secrets in this repository.

1. Assign a Header Auth credential to the `Validated Onboarding Webhook` node. The header name must be `X-Webhook-Token` and its value must match `N8N_ONBOARDING_WEBHOOK_AUTH_TOKEN` in the Spring Boot runtime.
2. Assign the existing Google Gemini credential to `Analyze Fictional Client Brief`.
3. Create a Google Sheet with a tab named `Reviews` and paste the header row from `docs/google-sheets-schema.csv`.
4. In `Append Review to Google Sheets`, select that spreadsheet and the `Reviews` tab, then assign the Google Sheets OAuth credential.
5. Activate the workflow and copy its production webhook URL into `N8N_ONBOARDING_WEBHOOK_URL` in the Spring Boot runtime.

Do not commit the webhook URL, auth-token value, OAuth credentials, API keys, spreadsheet ID or n8n credential IDs. The workflow creates a follow-up **draft** only; it never sends a message.

## Review contract

The fictional brief is checked for:

- companyName
- contactName
- contactEmail
- serviceRequested
- budget
- targetLaunchDate
- projectSummary
- dependencies

The result is `COMPLETE` when all fields are present and no conflicts are found, `MISSING_INFORMATION` when fields are absent, and `MANUAL_REVIEW` when the document is unreadable or conflicts with the submitted form.
