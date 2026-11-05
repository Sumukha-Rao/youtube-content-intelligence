# Windows and Linux local startup

This repository contains the restored competitor-discovery/transcript features, the
2026-11-01 review fixes and the refreshed UI. The latest local startup was verified on
Linux Mint at **http://localhost:8081**. Earlier Windows testing did not complete a full
Docker build; the Linux checks do not establish Windows runtime behavior.

The 2026-10-30 feature backup was restored on 2026-11-01. The private `.env` and local
outputs were preserved. Use this active checkout rather than an older backup copy.

## Local configuration

Copy `.env.example` only when creating a new local configuration; do not overwrite an
existing `.env`. Configure the existing YouTube and OpenAI-compatible LLM settings, or
set account overrides under **Settings → API access**. Keep `.env`, `*.tfvars`, state
files and provider keys out of commits. Do not copy secrets between environments unless
that is intentional. No Terraform resources were provisioned during local review.

The public-repository Azure deployment needs no GitHub PAT. Local SSH Git access and
Azure login are separate; see [Terraform authentication](../AZURE_TERRAFORM.md#21-authentication-and-github-tokens).
SMTP is another separate credential: follow the [email setup guide](email-notifications-implementation.md)
only if notifications are needed. Leave account opt-in off until SMTP is ready.

## Start and stop

Start Docker Desktop on Windows, or the Docker service on Linux. From the repository root:

```bash
docker compose up -d --build
docker compose ps
```

Open **http://localhost:8081**. Services use ports 3306 (MySQL), 8000 (Python), 8080
(backend), 8081 (frontend) and 8888 (SearXNG). The frontend is mounted from this checkout;
refresh the page to see UI changes. Rebuild backend and ML service when their code or
dependencies change. The first full Python image build can take substantial time.

After changing backend environment values, recreate it:

```bash
docker compose up -d --no-deps --force-recreate backend
```

Stop while retaining database/model volumes:

```bash
docker compose stop
```

## Verification

```bash
curl http://localhost:8081/api/health
curl http://localhost:8000/health
```

The backend should report `status: "UP"`. `emailConfigured` is false without SMTP and
does not prove delivery when true. Python reports its available embedding backend.
On Windows PowerShell, use `curl.exe` if `curl` resolves to a PowerShell alias.

The 2026-11-01 review recorded **47 backend, 35 Python and 8 frontend DOM tests passing**,
an executable JAR package and real Python startup using the cached existing
sentence-transformer model. After the UI refresh, all eight DOM tests and live frontend
page/health checks passed again. A live visual browser review was unavailable; tests do
not prove live YouTube subtitle access or SMTP delivery. See [the review](review-2026-11-01.md)
and [feature behavior and tests](recommendation-features.md).

For native Java development, use Java 17 and set `JAVA_HOME` to an installation that
actually exists on that operating system. Docker builds use their own runtime. A Linux
`ml-service/.venv` cannot be activated on Windows: create a separate Windows virtual
environment and install the chosen requirements file. Test-only packages are separate.

## Linux Mint shared-drive write access

The earlier read-only mount was caused by Windows leaving NTFS in a hibernated state.
Hibernation/Fast Startup were disabled in the earlier Windows session. Linux write
access was subsequently confirmed and changes were applied to the active repository.
If the issue returns, save work and fully shut down Windows before booting Linux:

```powershell
shutdown /s /t 0
```

On Mint, mount the drive using the file manager, then inspect the mount and check writing:

```bash
cd "/media/sumukha/New Volume/youtube-content-intelligence"
findmnt -T . -o TARGET,FSTYPE,OPTIONS
probe=$(mktemp ./.write-check.XXXXXX) && rm -- "$probe"
```

Expect `rw` and a successful temporary-file check. If it is still `ro`, inspect mount
and kernel errors rather than forcing writes to a hibernated partition. A writable
Linux-folder copy can be used while resolving the drive issue; keep one active copy so
changes do not diverge.
