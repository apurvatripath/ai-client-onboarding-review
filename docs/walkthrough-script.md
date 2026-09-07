# 60-second review-queue walkthrough script

For `/review.html`, the human review queue (static screenshots of this exact flow are already in the README — this script is for an actual narrated recording when one gets made). Narrate only what is on screen. Do not claim a client; this is a self-initiated demo.

1. **0:00-0:10** — "This is the human review queue for a document-extraction pipeline built for automation agencies."
2. **0:10-0:25** — Open a flagged submission. Document on the left, extracted fields on the right, one flagged (low-confidence or conflicting).
3. **0:25-0:40** — Correct the flagged field, click Save.
4. **0:40-0:55** — Show it flip to Complete and drop off the queue. "Every correction is audited automatically — nothing silently overrides a bad extraction."
5. **0:55-1:00** — "That's the safety net that makes this hold up past a prototype."

A ready-made example: submit `samples/private/documents/brightleaf-intake.pdf` or any two-page intake form repeating a field with two different values (e.g. two different Budget lines) — the pipeline flags the disagreement as a `CONFLICT` instead of silently picking one, which is exactly the moment worth narrating.

Legacy note: the old n8n-era script (submit a form, show COMPLETE/MISSING_INFORMATION/VALIDATION_ERROR on `/api/onboarding`) still applies to that separate legacy flow if ever needed, but the review queue above is the current, sharper demo. Keep any Drive/public link on Viewer, same rule as the Aarambh walkthrough.
