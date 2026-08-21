# AI Client Onboarding & Document Review

Self-initiated service demonstration:

onboarding form + one PDF/image -> Spring Boot validation -> secured n8n webhook -> AI extraction -> review status -> Google Sheets -> human-approved follow-up draft

## Locked MVP

- One onboarding form and one PDF/JPEG/PNG upload.
- Required-field, URL, date, type and size validation.
- Spring Boot owns the public intake boundary.
- n8n receives the validated multipart request through a server-only webhook.
- AI returns structured extracted fields, missing fields and a reason.
- Review status is COMPLETE, MISSING_INFORMATION or MANUAL_REVIEW.
- Any follow-up is a draft with approval status PENDING; nothing is automatically sent.
- Google Sheets receives the operational review record.

No accounts, multi-tenancy, billing, document-management suite, legal/compliance claims or automatic communication.

## API contract

`POST /api/onboarding` accepts `multipart/form-data` with these names:

- `companyName`
- `website`
- `contactName`
- `contactEmail`
- `serviceRequested`
- `desiredStartDate`
- `notes` (optional)
- `document`

A successful review returns `success`, `submissionId`, `reviewStatus`, `extractedFields`, `missingFields`, `reviewReason`, `followUpDraft` and `approvalStatus`. Validation failures use HTTP 400 and `VALIDATION_ERROR`; unavailable automation uses HTTP 502 and `AUTOMATION_UNAVAILABLE`.

The importable workflow is at `.n8n/ai-client-onboarding-document-review.json`. Follow `docs/n8n-setup.md` and use `docs/google-sheets-schema.csv` for the `Reviews` header row.

## Local configuration

Copy .env.example values into runtime environment variables. Never place real values in source files:

- N8N_ONBOARDING_WEBHOOK_URL
- N8N_ONBOARDING_WEBHOOK_AUTH_TOKEN
- ONBOARDING_MAX_FILE_SIZE_BYTES (optional; defaults to 5 MiB)

## Verify locally

```powershell
D:\Programs\Maven\apache-maven-3.9.16\bin\mvn.cmd test
D:\Programs\Maven\apache-maven-3.9.16\bin\mvn.cmd package
```
