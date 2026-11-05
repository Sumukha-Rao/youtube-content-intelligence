# Email Notifications Implementation Guide

Email digests are already implemented in YouTube Content Intelligence. Enable delivery by configuring SMTP on the backend, then turning on Notifications for each account. This guide explains the existing code, a Gmail setup example, a controlled verification process, and the current scheduling limits.

## What the feature does

- Generates a fresh recommendation run for an opted-in creator when their daily or weekly interval is due.

- Reuses the existing YouTube ingestion, audience analysis and LLM configuration for that account.

- Sends a plain text email containing the generated ideas, outlines, model information and an application link.

- Stores notification preferences and the last attempt time in the existing user_settings table. Marks a delivered run as notified in idea_runs.

- Does not attach videos or include the transcript summaries requested later from idea cards.

## The delivery flow

The scheduler finds opted-in accounts and checks their cadence. It creates an idea run, waits for generation to finish, passes the completed run to NotificationService, and sends through JavaMailSender. Successful delivery marks the run as notified. The scheduler records an attempt time after the completed-run delivery path.

## Where the implementation lives

| Existing component | Responsibility |
| --- | --- |
| DigestScheduler.java | Find due accounts, create runs, invoke delivery and maintain cadence. |
| NotificationService.java | Check SMTP availability, compose SimpleMailMessage and send it. |
| UserSettingsService.java | Validate and persist notification preferences. |
| application.yml and docker-compose.yml | Resolve SMTP and scheduling values from the server environment. |
| UserSettings and IdeaRun | Reuse stored preferences, last_notified_at and the notified flag. |


## Configure SMTP delivery

The following example uses Gmail with STARTTLS on port 587, matching the application defaults. Use an account and sender address you control. The SMTP credentials are server configuration; they do not belong in the frontend or the per-user YouTube API settings.

### Set up the sender

1. For Gmail, enable Google Account 2-Step Verification and create an app password if your account permits it. Use that app password rather than your normal account password.

2. Open the project’s existing .env file privately. Replace the placeholders below with your own sender credentials. Do not paste the password into chat or commit it to Git.

3. Set NOTIFY_FROM to the authenticated sender address or an address your provider explicitly allows.

4. Set APP_URL to the URL recipients can actually open. localhost works only on the machine running the application.

### Environment example

```
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USERNAME=youraddress@gmail.com
SMTP_PASSWORD=YOUR_APP_PASSWORD
NOTIFY_FROM=youraddress@gmail.com
NOTIFY_CRON=0 0 * * * *
APP_URL=http://localhost:8081
```

### Apply the configuration

Wait for active idea generation to finish, then recreate only the backend container. A simple container restart does not reload changed Compose environment variables.

```
docker compose up -d --no-deps --force-recreate backend
```

### Provider notes

Authentication and STARTTLS default to true. Port 465 uses a different SSL mode and needs additional mail configuration; changing only the port is insufficient. Some work or school accounts restrict app passwords. Ask the account administrator or use an SMTP provider that supports the application’s username and password flow.

[Google app password requirements](https://support.google.com/accounts/answer/185833?hl=en)

[Google Gmail SMTP settings](https://support.google.com/mail/answer/7104828?hl=en)


## Enable and verify delivery

Server SMTP setup and per-account opt-in are separate. Keep notifications off until the backend reports that mail is configured. The guide does not send messages or change your account settings automatically.

### Enable it for an account

1. Sign in to the app and add a creator channel or at least one competitor. Configure valid YouTube and LLM access under Settings then API access.

2. Open Settings then Notifications. Enable Email me fresh ideas automatically.

3. Choose Every day or Every week, and enter an inbox you own for the first test.

4. Save settings. Keep the backend, database and Python service running while scheduled delivery is needed.

### Check readiness

The health response should report emailConfigured as true. This confirms that the application has an SMTP host and mail sender configured; it does not prove the credentials are valid or that a message will reach the inbox.

```
docker compose ps
curl http://localhost:8081/api/health
docker compose logs --tail=80 backend
```

### Run a controlled first delivery

For an account whose last_notified_at is still null, the first scheduler tick treats it as due. Normally the tick runs at the start of each hour. To shorten a local test, temporarily use the six-field Spring cron below and recreate the backend again.

```
NOTIFY_CRON=0 * * * * *
```

- A shorter cron changes how often due accounts are checked. It does not override the daily or weekly interval for an account that already has an attempt time.

- Look for a completed idea run, followed by the backend log message Digest emailed to and its run ID. Check your inbox and spam folder.

- If the account already has an attempt time, wait until it is due or use a dedicated test account. Do not reset another user’s timestamps to force delivery.

- Restore the hourly cron after the test. Scheduled runs consume the account’s normal YouTube quota and LLM requests.


## Understand scheduling and failures

This implementation checks a rolling interval rather than a user-selected clock time. The process must be running to perform checks; it does not send a message while the laptop or server is off.

### Cadence rules

| Setting or state | Current behavior |
| --- | --- |
| notify_enabled is false | The account is not selected for scheduled digests. |
| last_notified_at is null | The account is due at the next scheduler tick. |
| DAILY | Due after the recorded attempt time plus one day. |
| WEEKLY | Due after the recorded attempt time plus one week. |
| Default NOTIFY_CRON | 0 0 * * * * checks at the start of each hour using the server JVM time zone. |

### What happens on failure

- SMTP disabled or a send failure after a completed run returns false from NotificationService. The run is not marked notified, but the scheduler still updates last_notified_at on this path.

- A failed generation reaches a continue branch before that timestamp update, so it can be tried at a later scheduler tick.

- Exceptions caught around setup, run creation or delivery also update the attempt timestamp in the catch block.

- SMTP acceptance does not guarantee final inbox placement. Provider rejection, bounce handling and spam filtering occur outside this application.

### Important current limits

Leaving SMTP_HOST blank prevents sending, but the scheduler can still generate ideas for opted-in accounts before delivery is skipped. Keep the notification toggle off until SMTP is ready.

last_notified_at is an attempt marker in this version, not a guarantee of successful delivery. Changing SMTP credentials does not automatically retry an already-stamped attempt. There is no send-now endpoint or independent delivery retry queue.

Email is plain text and may show Markdown markers from the generated result. On-demand transcript summaries remain in the app. There is no per-user time-zone or exact-send-time field, and multiple backend replicas need coordination to avoid duplicate scheduled delivery.


## Troubleshoot and maintain the feature

Use backend logs and the recipient’s inbox together to diagnose delivery. Keep passwords and API keys out of screenshots and shared log files. The application can continue generating ideas when SMTP is not configured.

### Common symptoms

| Symptom | Check and action |
| --- | --- |
| SMTP warning in Settings | Confirm SMTP_HOST is set and recreate the backend so it receives the changed environment. |
| Authentication failed | Check SMTP_USERNAME, the provider’s app password policy, and the generated app password. Do not disable account security to work around a policy. |
| Connection or TLS failure | Check host, port, outbound network access and STARTTLS requirements. The provided setup uses port 587. |
| No scheduled run | Confirm notification opt-in, a valid recipient, configured sources and APIs, server uptime, and whether the account is actually due. |
| Run completed but no email | Check emailConfigured, mail-send logs, allowed sender address, spam folder, and the recorded attempt cadence. |
| Email link opens the wrong app | Set APP_URL to a reachable deployment URL, then recreate the backend. |

### If you use a local mail relay

application.yml reads SMTP_AUTH and SMTP_STARTTLS, but the current Compose backend environment does not forward those optional variables. If your development relay needs false values, explicitly add the following mappings under backend then environment before setting them in .env. Keep real external mail transport authenticated and encrypted.

```
SMTP_AUTH: ${SMTP_AUTH:-true}
SMTP_STARTTLS: ${SMTP_STARTTLS:-true}
```

### Tests and practical next improvements

Add isolated tests using a mocked JavaMailSender for the recipient, sender, subject, body, successful handoff and send failures. Test cadence with an injected clock, and exercise missing SMTP and failed-generation paths without sending real messages.

For production, consider explicit SMTP connection and write timeouts, an early SMTP readiness check before expensive scheduled generation, separate delivery-attempt and success timestamps, and delivery retries that reuse a completed run. These are improvements to the existing services; another ML model or messaging platform is not required.

### Code references

Backend classes are in backend/src/main/java/com/yci/service and backend/src/main/java/com/yci/scheduler. SMTP bindings are in backend/src/main/resources/application.yml; container variables are in docker-compose.yml. The notification controls are in frontend/app.html and frontend/js/app.js.

## Related configuration and documentation

SMTP credentials authorize mail delivery. YouTube and LLM keys authorize content
collection and generation. Neither is a GitHub PAT. The current Terraform deployment
uses Azure authentication and a public repository clone, so it does not need a GitHub
PAT. See [deployment authentication](../AZURE_TERRAFORM.md#21-authentication-and-github-tokens).

The refreshed interface keeps the same **Settings → Notifications** controls. SMTP
values remain backend environment settings; they are not entered into the per-user
API panel. For Azure, use the deployment Compose file when recreating the backend,
rather than the root local Compose file used in this guide's examples.

- [Download the Word implementation guide](Email_Notifications_Implementation_Guide.docx)
- [Local startup](windows-local.md)
- [Recommendation and transcript behavior](recommendation-features.md)
- [Application architecture](../PROJECT_GUIDE.md)

No live SMTP delivery was performed during the documentation or UI work.
