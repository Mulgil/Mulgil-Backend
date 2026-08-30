# Android demo runbook

This is a manual device checklist. It must not be executed by automated tests. Obtain credentials from the approved secret manager, keep them out of shell history and logs, and revoke temporary credentials after the demo.

## Environment names

Backend operators provide these names without committing their values:

- `BACKEND_BASE_URL`
- `DATABASE_URL`
- `DATABASE_USERNAME`
- `DATABASE_PASSWORD`
- `JWT_HS256_SECRET_BASE64`
- `JWT_ISSUER`
- `JWT_AUDIENCE`
- `JWT_KEY_ID`
- `GOOGLE_OAUTH_CLIENT_ID`
- `GOOGLE_CLOUD_PROJECT`
- `GOOGLE_CLOUD_LOCATION`
- `GOOGLE_APPLICATION_CREDENTIALS`
- `GCS_BUCKET`
- `SPEECH_LOCATION`
- `SPEECH_API_ENDPOINT`
- `SPEECH_MODEL`
- `VERTEX_GENERATION_MODEL`
- `VERTEX_EMBEDDING_MODEL`
- `FCM_ENABLED`
- `MAX_AI_JOBS_PER_DAY`
- `MAX_JOB_RETRY`

Never paste values into screenshots, tickets, chat, the repository, or the Android build log. Confirm the selected cloud project is the demo project before starting.

## Tunnel and backend health

1. Start the approved tunnel outside this repository and record only its public hostname.
2. Set the Android debug backend URL from `BACKEND_BASE_URL`.
3. Sign in on the device and request the course list. A successful authenticated response is the health check.
4. Confirm an unauthenticated request is rejected and that a second demo identity cannot read the first identity's session.

## FCM verification

1. Grant notification permission on the device.
2. Sign in and verify the device registers its current FCM token without printing it.
3. Complete one background generation job.
4. Confirm one notification arrives, opens a `mulgil://` deep link, and navigates to the expected owner-scoped result.
5. Confirm lock-screen text contains no transcript, note body, filename, access token, or credential.

## Device flow

1. Create a course, session, and exam.
2. Upload a text PDF and a scanned PDF. Confirm preview extraction, OCR review, and source navigation.
3. Draw an annotation, leave the page, review the low-confidence handwriting result, correct it, and add a note.
4. Upload a recording, map it to the session, wait for STT, and open the transcript source.
5. Open summary and mind map results. Follow at least one source reference back to its PDF, note, handwriting block, or transcript segment.
6. Open the quiz, submit both supported answer shapes, and confirm progress updates only after submission.
7. Upload a past exam, wait for indexing, request a predicted quiz, and follow a source reference.
8. Repeat the same generation request and confirm it returns the existing job without a second provider operation.

## Failure checks

- Exceed the configured audio duration and confirm validation rejects the upload.
- Submit a stale annotation or note version and confirm conflict handling preserves the newer content.
- With a controlled fake or staging fault, verify provider timeout and malformed output fail safely without raw content in logs.
- Reach `MAX_AI_JOBS_PER_DAY` in the controlled demo account and confirm the next generation request is rejected.
- Inspect structured logs for event names and identifiers only; search for tokens, credentials, filenames, note bodies, transcripts, and provider raw output before sharing logs.
