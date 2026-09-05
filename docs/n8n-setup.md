# n8n setup

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
