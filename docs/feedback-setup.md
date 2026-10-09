# Feedback delivery

Feedback is anonymous and delivered as one plain-text email per submission. The APK contains only the public HTTPS endpoint, never Google credentials. The Google web app runs as its owner with the send-only `script.send_mail` scope. No Gmail-reading scope is used.

## Google setup

1. Sign in to Google Apps Script with the mailbox owner's account and create a standalone project.
2. Replace `Code.gs` with `backend/feedback/Code.gs`. In Project Settings, enable the manifest editor and replace `appsscript.json` with the checked-in manifest.
3. Add the script property `FEEDBACK_RECIPIENT` with the agreed owner's mailbox, **dmeloper@gmail.com**. This server property is the sole recipient; requests cannot override it.
4. Run `authorizeFeedback` and grant the send-email permission. This does not send mail. Do not authorize Gmail reading or add another scope.
5. Deploy a new **Web app**, execute as **Me**, access **Anyone** (anonymous). Copy its deployed `/exec` URL, not the editor `/dev` URL.
6. In the Git-ignored root `local.properties`, add `feedback.endpoint=https://script.google.com/macros/s/DEPLOYMENT_ID/exec`. Rebuild the app. An unconfigured build keeps Submit disabled and explains why.
7. Submit a manual test from the emulator and confirm the email arrives. Disable emulator networking, submit another test, then restore networking and confirm automatic delivery.

For updates, create a new script version and edit the existing deployment to retain its URL. Local endpoint configuration must be present on every machine building a connected APK. Neither this feature nor its setup publishes a repository release.

## Delivery guarantees and limits

- Pending messages are stored atomically in `noBackupFilesDir`, excluding them from backup/device-transfer replay. Ordinary app closure and reboot preserve the queue; uninstall clears it. Force-stop delays work until reopening. Android schedules network-constrained jobs; network restoration is not an immediate-delivery guarantee.
- The editor accepts 1–10,000 UTF-16 code units of nonblank text. The durable queue holds at most 100 messages and never drops older ones to make room. Unsubmitted drafts exist only in memory.
- App/version code and creation time accompany the text; no account identity, device ID, macro coordinates, or logs are collected. Do not add message-body logging or error reporting.
- Only a valid acknowledgement matching the UUID removes a pending message. Errors, invalid JSON, unexpected redirects, lost responses, and mail limits retain it. Requests follow only Google's ContentService HTTPS response redirects; the text POST is never forwarded.
- A scheduling failure retains the saved message, makes the editor read-only, and allows retry with the same ID. Closing that screen does not discard an already submitted record; startup repairs scheduling.
- Script properties contain only UUID/hash/status/time and rate counters, never feedback bodies. Successful IDs are retained for 30 days, up to 3,000 records. MailApp delivery success means Google accepted the mail, not that the recipient read it.
- A crash after mail sending but before writing its acknowledgement can cause a duplicate; the UUID is in the subject. Retrying favors no silent loss. Deduplication is bounded by its retention period.
- Basic limits allow 10 attempts/minute and 100/day in Asia/Seoul; Google's actual remaining quota takes precedence and may reset on a different schedule. The anonymous endpoint is public. An attacker can consume quota and delay legitimate mail; no embedded APK secret can prevent this. If abuse becomes material, replace this accepted simple setup with authenticated abuse protection.

## Validation

Run `node --test backend/feedback/feedback.test.cjs`, the Android unit tests, `assembleDebug`, and `lintDebug`. Backend tests use a fake MailApp and never send real mail. Real mailbox confirmation requires a manual emulator submission after deployment. Inspect the merged manifest: WorkManager's standard wake/reboot scheduling permissions are expected; no notification, foreground-service, or additional identity permission is needed.

References: [Apps Script web apps](https://developers.google.com/apps-script/guides/web), [MailApp](https://developers.google.com/apps-script/reference/mail/mail-app), [Google quotas](https://developers.google.com/apps-script/guides/services/quotas), [WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).
