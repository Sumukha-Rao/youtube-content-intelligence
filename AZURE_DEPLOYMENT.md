# Deploying to Azure on a Free Account

Step-by-step deployment of this project to Azure using only what the free account
actually covers. Companion to [`README.md`](README.md) and
[`PROJECT_GUIDE.md`](PROJECT_GUIDE.md).

This is the by-hand version, with `az` commands you run and read one at a time.
For the same deployment as code — one `terraform apply`, and a teardown that is
one command rather than a checklist — see [`AZURE_TERRAFORM.md`](AZURE_TERRAFORM.md).

The LLM provider is out of scope here — you will supply `LLM_API_KEY` and `LLM_BASE_URL`
pointing at your own compute instance. Every other part of the stack is covered.

---

## 1. The target architecture

Two Azure resources. That is the whole deployment.

```
                    Internet
                       │  HTTPS :443
                       ▼
        ┌──────────────────────────────────┐
        │  Azure VM  (B2ats_v2, 1 GiB)     │
        │  ┌────────────────────────────┐  │
        │  │ caddy    TLS + routing     │  │
        │  │   /api/* → backend :8080   │  │
        │  │   /*     → frontend :80    │  │
        │  ├────────────────────────────┤  │
        │  │ backend    Spring Boot     │  │
        │  │ ml-service FastAPI (lite)  │  │
        │  │ frontend   nginx (static)  │  │
        │  └────────────────────────────┘  │
        └───────────────┬──────────────────┘
                        │ TLS :3306
                        ▼
        ┌──────────────────────────────────┐
        │  Azure Database for MySQL        │
        │  Flexible Server (B1ms)          │
        └──────────────────────────────────┘
```

### Why this shape

The free account gives you **750 hours/month of a B1s, B2ats_v2 or B2pts_v2 VM** and
**750 hours/month of a MySQL Flexible Server B1ms** for 12 months. 750 hours is slightly
more than a calendar month, so one of each can run continuously.

| Alternative | Why not |
|---|---|
| **App Service** | The free F1 tier does not run custom containers, and this backend is a container. B1 (the cheapest tier that does) is a paid tier. |
| **Container Apps** | The monthly free grant (180,000 vCPU-seconds) covers roughly 8 days of a single always-on 0.25-vCPU replica. Two always-on services run past it and start billing. It is a good fit only if you accept scale-to-zero and cold starts. |
| **AKS** | The control plane is free; the node pool is not, and the smallest usable node is outside the free VM sizes. |
| **Container Registry** | Not in the free tier (Basic is roughly $5/month). Avoided entirely by building images on the VM. |
| **Static Web Apps** | Genuinely free and a fine home for `frontend/`, but it adds a second origin, CORS, and a service you do not need — Caddy already serves the static files. |
| **MySQL in a container on the VM** | It would eat ~440 MB of the VM's 1 GiB. The managed server is free for 12 months and takes that load off the box entirely. |

So: **one VM, one managed database.** Everything else would add cost, cold starts, or
moving parts without buying anything.

### What this costs

Free for 12 months: the VM compute (750 h), two 64 GB P6 managed disks, and the MySQL
B1ms instance with its included storage.

Not free, and small: a **Standard public IPv4 address** (~$3–4/month — Basic SKU IPs were
retired, so an internet-facing VM must have one) and **outbound data transfer** past the
monthly free allowance. Both come out of the $200 credit while it lasts, then bill
normally. Set a budget alert in §9 and treat "free account" as "a few dollars a month",
not zero.

> Free-tier quantities change. Confirm current limits on the portal's **Free services**
> page before you provision — and create resources *from that page* where possible, since
> free tiers are not always the default selection elsewhere.

---

## 2. Before you start

- An Azure free account (`https://azure.microsoft.com/free`).
- The Azure CLI on your machine: `az version`. Install docs:
  `https://learn.microsoft.com/cli/azure/install-azure-cli`.
- An SSH key pair (`ssh-keygen -t ed25519` if you do not have one).
- This repository, pushed somewhere the VM can clone it, **or** ready to copy with `scp`.

Sign in and pin the subscription:

```bash
az login
az account show --output table
az account set --subscription "<subscription name or id>"
```

Set the variables used throughout. Pick a region near you — `centralindia` and
`southindia` are the closest options from India.

```bash
export RG=yci-rg
export LOC=centralindia
export VM_NAME=yci-vm
export DNS_LABEL=yci-$RANDOM             # must be globally unique within the region
export MYSQL_NAME=yci-mysql-$RANDOM      # must be globally unique
export MYSQL_ADMIN=yciadmin
export MYSQL_PASS='<a strong password>'  # 8-128 chars, 3 of: upper/lower/digit/symbol
```

Keep this shell open; later steps reuse the variables.

---

## 3. Step 1 — Resource group

One group holds everything, which also makes teardown a single command.

```bash
az group create --name $RG --location $LOC --output table
```

---

## 4. Step 2 — MySQL Flexible Server

Create the smallest burstable server, which is the size the free tier covers:

```bash
az mysql flexible-server create \
  --resource-group $RG \
  --name $MYSQL_NAME \
  --location $LOC \
  --admin-user $MYSQL_ADMIN \
  --admin-password "$MYSQL_PASS" \
  --sku-name Standard_B1ms \
  --tier Burstable \
  --version 8.0.21 \
  --storage-size 20 \
  --storage-auto-grow Disabled \
  --high-availability Disabled \
  --public-access None \
  --database-name yci \
  --yes
```

Notes on the flags that matter for staying free:

- `--tier Burstable --sku-name Standard_B1ms` — the free-tier size. Anything larger bills.
- `--high-availability Disabled` — HA doubles the instance.
- `--storage-auto-grow Disabled` — prevents silently growing past the free storage
  allowance. Watch usage instead; the schema is tiny.
- `--public-access None` — no firewall openings yet. You will add exactly one in §5.

Record the hostname:

```bash
export MYSQL_HOST=$(az mysql flexible-server show -g $RG -n $MYSQL_NAME --query fullyQualifiedDomainName -o tsv)
echo $MYSQL_HOST      # <name>.mysql.database.azure.com
```

The server requires TLS (`require_secure_transport = ON`). Leave it on — MySQL
Connector/J with `sslMode=REQUIRED` handles it with no truststore setup.

---

## 5. Step 3 — The virtual machine

`B2ats_v2` gives 2 vCPUs at the same 1 GiB of RAM as `B1s` and is inside the same free
allowance, so builds on the box finish roughly twice as fast. Check availability first:

```bash
az vm list-skus --location $LOC --size Standard_B2ats --all --output table
```

If it is not offered in your region, substitute `Standard_B1s` below (everything else is
identical; only the build takes longer).

```bash
az vm create \
  --resource-group $RG \
  --name $VM_NAME \
  --image Ubuntu2404 \
  --size Standard_B2ats_v2 \
  --admin-username azureuser \
  --generate-ssh-keys \
  --public-ip-sku Standard \
  --public-ip-address-dns-name $DNS_LABEL \
  --os-disk-size-gb 64 \
  --storage-sku Premium_LRS \
  --output table
```

- `--os-disk-size-gb 64 --storage-sku Premium_LRS` matches the free P6 disk allowance.
  The default 30 GB disk is a *different* (P4) size that the allowance does not cover.
- `--public-ip-address-dns-name` gives you `‹label›.‹region›.cloudapp.azure.com`, a real
  DNS name — which is what lets Caddy issue a Let's Encrypt certificate without you
  buying a domain.

Capture the address and the FQDN:

```bash
export VM_IP=$(az vm show -d -g $RG -n $VM_NAME --query publicIps -o tsv)
export SITE=$(az network public-ip show -g $RG -n ${VM_NAME}PublicIP --query dnsSettings.fqdn -o tsv)
echo "$VM_IP  $SITE"
```

Open only 80 and 443 (22 is already open from `az vm create`):

```bash
az vm open-port --resource-group $RG --name $VM_NAME --port 80  --priority 1010
az vm open-port --resource-group $RG --name $VM_NAME --port 443 --priority 1020
```

Now let the database accept connections from this VM, and nothing else:

```bash
az mysql flexible-server firewall-rule create \
  --resource-group $RG --name $MYSQL_NAME \
  --rule-name allow-vm --start-ip-address $VM_IP --end-ip-address $VM_IP
```

Add your own IP temporarily so you can load the schema from your laptop (optional — §6
loads it from the VM instead):

```bash
# az mysql flexible-server firewall-rule create -g $RG -n $MYSQL_NAME \
#   --rule-name my-laptop --start-ip-address <your ip> --end-ip-address <your ip>
```

---

## 6. Step 4 — Prepare the VM

```bash
ssh azureuser@$SITE
```

### 6.1 Swap

1 GiB with no swap will OOM during the Maven build. Add 4 GB permanently:

```bash
sudo fallocate -l 4G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
sudo sysctl -w vm.swappiness=10
echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-swap.conf
free -h
```

### 6.2 Docker

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl git
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker $USER
newgrp docker
docker compose version
```

### 6.3 The code

```bash
git clone <your repository url> ~/youtube-content-intelligence
cd ~/youtube-content-intelligence
```

No repository? Copy it from your machine instead, skipping the local build artifacts:

```bash
# run this on your laptop, from the project's parent directory
rsync -av --exclude '.venv' --exclude '.git' --exclude 'target' --exclude '__pycache__' \
  --exclude '.pytest_cache' --exclude '.idea' \
  youtube-content-intelligence/ azureuser@$SITE:~/youtube-content-intelligence/
```

### 6.4 Load the schema

**Do not skip this.** In local Docker, `database/schema.sql` is run automatically by the
MySQL container's entrypoint. A managed server has no such hook.

Hibernate's `ddl-auto=update` would create most tables on its own, but not correctly:
several entities use plain `Long userId` columns rather than JPA associations, so Hibernate
emits **no foreign keys** for `user_settings`, `idea_runs`, `auth_tokens` or
`competitor_comments`. Deleting an account would then orphan its rows. Load the file.

```bash
sudo apt-get install -y mysql-client
mysql -h <MYSQL_HOST> -u yciadmin -p --ssl-mode=REQUIRED < database/schema.sql

# Expect 10 tables and, at this point, zero users — accounts are created by signing up.
mysql -h <MYSQL_HOST> -u yciadmin -p --ssl-mode=REQUIRED \
  -e "SHOW TABLES FROM yci; SELECT COUNT(*) AS users FROM yci.users;"
```

**Upgrading a database loaded before per-user API keys existed:** `schema.sql` is only
applied to an empty server, so add the new `user_settings` columns once:

```bash
mysql -h <MYSQL_HOST> -u yciadmin -p --ssl-mode=REQUIRED \
  < database/migrations/001_user_api_config.sql
```

("Duplicate column name" means it already ran — `ddl-auto=update` may have got there first.)

### 6.5 Same-origin frontend — nothing to do

Earlier versions needed an edit here: the frontend had `http://localhost:8080` baked in as
its API base and the VM's copy had to be patched. That is gone. The page now calls
`/api/...` relative to whatever origin served it, and Caddy routes `/api/*` to the backend
and everything else to nginx — so the deployed app works on your domain with no edit, and
`frontend/js/config.js` stays as shipped.

Confirm after §8 that both of these answer the same thing:

```bash
curl -s https://$SITE/api/health     # {"emailConfigured":false,"status":"UP"}
docker exec yci-backend curl -s localhost:8080/api/health
```

### 6.6 Environment file

```bash
cat > ~/youtube-content-intelligence/.env <<'EOF'
SITE_ADDRESS=<paste $SITE here>

MYSQL_URL=jdbc:mysql://<MYSQL_HOST>:3306/yci?sslMode=REQUIRED&serverTimezone=UTC&allowPublicKeyRetrieval=true
MYSQL_USERNAME=yciadmin
MYSQL_PASSWORD=<your database password>

# Encrypts the API keys your users save in the app. Generate one now:
#   openssl rand -hex 32
# Without it those keys sit in the managed database as plain text.
APP_SECRET_KEY=<paste 64 hex characters>

# The default key, used by any account that has not saved its own under
# Settings -> API access. Blank is workable if every user brings their own.
YOUTUBE_API_KEY=<your key>

# The default model — any OpenAI-compatible /chat/completions endpoint. Also
# overridable per account. Prefer a non-reasoning model; see PROJECT_GUIDE.md §5.5.
LLM_BASE_URL=
LLM_API_KEY=
LLM_MODEL=
LLM_TIMEOUT_SECONDS=900

# duckduckgo needs no container but throttles; "none" disables it. For "searxng",
# either start the opt-in container (see §7) or point at an instance you already run.
WEB_SEARCH_PROVIDER=duckduckgo
WEB_SEARCH_BASE_URL=
WEB_SEARCH_FALLBACK=true

AUTH_TOKEN_TTL_DAYS=30

# Email digests — optional. Blank SMTP_HOST saves the preference but sends nothing.
SMTP_HOST=
SMTP_PORT=587
SMTP_USERNAME=
SMTP_PASSWORD=
NOTIFY_FROM=yci@localhost
NOTIFY_CRON=0 0 * * * *
EOF
chmod 600 ~/youtube-content-intelligence/.env
```

Three differences from the local `.env` matter:

- `sslMode=REQUIRED` instead of `useSSL=false` — the managed server rejects plaintext.
- `SITE_ADDRESS` is new; Caddy uses it as the certificate subject and the site name.
- `APP_URL` is derived from `SITE_ADDRESS` by the compose file, so digest emails link to
  the deployed site rather than `localhost`.

Per-channel volumes (last N videos vs days, comment budgets, web search on/off) are **not**
environment variables — they are per-user settings stored in the database and edited in
the app.

---

## 7. Step 5 — Fitting in 1 GiB

Measured on the local stack, the full images use: backend 435 MB, **ml-service 1.26 GB**,
MySQL 439 MB, nginx 17 MB. That does not fit in 1 GiB. Two changes make it fit, and both
are already in the repository:

### `ml-service/Dockerfile.lite` + `requirements-lite.txt`

The semantic stack (`sentence-transformers`, `bertopic`, `hdbscan`, and the `torch` they
pull in) is imported lazily by `app/embeddings.py` and `app/topics.py`, which fall back to
a TF-IDF backend when it is absent. Dropping it from the requirements is therefore a
supported configuration, not a hack:

| | Full image | Lite image |
|---|---|---|
| Image size | 10.4 GB | **711 MB** |
| Memory in use | 1.26 GB | **108 MB** |
| `/health` reports | `sentence-transformers` | `tfidf-fallback` |
| Request-pattern matching | identical (pure regex) | identical |
| Clustering of paraphrased requests | semantic embeddings | TF-IDF vectors |

What survives intact is the part that matters most: explicit asks ("make a video on X")
are found by regex in `demand.py`, which needs no model at all, and labels are filtered by
the same stopword list either way.

What degrades is the *grouping*. TF-IDF vectors cluster by shared vocabulary rather than
meaning, so "kafka consumer groups" and "consumer group rebalancing" may stay separate
topics instead of merging into one. Acceptable; if you want full semantic quality you need
a VM with more RAM than the free tier offers.

### `deploy/azure/docker-compose.azure.yml`

Drops the MySQL container, builds the ML service from `Dockerfile.lite`, adds Caddy, and
caps every service:

| Service | Limit | Notes |
|---|---|---|
| `backend` | 640 MB | `JAVA_TOOL_OPTIONS=-Xmx320m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC` |
| `ml-service` | 320 MB | measured ~110 MB in use |
| `frontend` | 64 MB | nginx |
| `caddy` | 96 MB | TLS termination |
| `searxng` | 256 MB | **opt-in**, see below |

Only Caddy publishes ports; the rest talk over the internal Docker network and are not
reachable from the internet.

### Web search on a 1 GiB VM

The local `docker-compose.yml` runs a private SearXNG for web search. Here it is behind a
compose profile, because the four services above already account for the whole gigabyte:

```bash
# only worth doing on a VM with more RAM than the free tier
docker compose --env-file .env -f deploy/azure/docker-compose.azure.yml \
  --profile searxng up -d
# then in .env:  WEB_SEARCH_PROVIDER=searxng  WEB_SEARCH_BASE_URL=http://searxng:8080
```

On the free tier the sensible options are `duckduckgo` (keyless, throttles), pointing
`WEB_SEARCH_BASE_URL` at a SearXNG instance you run elsewhere, or letting each user
configure their own under **Settings → API access**.

---

## 8. Step 6 — Build, start, verify

```bash
cd ~/youtube-content-intelligence
docker compose --env-file .env -f deploy/azure/docker-compose.azure.yml up -d --build
```

The first build takes 15–30 minutes on a burstable VM: Maven downloads the Spring
dependency tree and compiles, and pip builds the Python wheels. Watch it with
`docker compose -f deploy/azure/docker-compose.azure.yml logs -f`.

Once it is up, the quickest end-to-end check of the credentials is from inside the app:
sign in, open **Settings → API access** and press *Test* under YouTube and the model. Each
one makes a single real call and reports back — far faster than discovering a wrong key
three minutes into a run.

Certificate issuance happens on Caddy's first start and needs port 80 reachable from the
internet — that is what the NSG rule in §5 is for. Confirm:

```bash
docker logs yci-caddy | grep -i "certificate obtained"
```

Then check the deployment from your own machine:

```bash
curl https://$SITE/api/health          # {"emailConfigured":false,"status":"UP"}
curl -o /dev/null -w '%{http_code}\n' https://$SITE/   # 200
curl -o /dev/null -w '%{http_code}\n' https://$SITE/api/home   # 401 — auth is enforced
```

Run the pipeline end to end over HTTPS (same sequence as `PROJECT_GUIDE.md` §3.3):

```bash
T=$(curl -s -X POST https://$SITE/api/auth/signup -H 'Content-Type: application/json' \
  -d '{"name":"You","email":"you@example.com","password":"supersecret123"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')

curl -s -X PUT https://$SITE/api/channel -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
  -d '{"channelUrl":"https://youtube.com/@someChannel"}'

# keep the first run small on a burstable VM
curl -s -X PUT https://$SITE/api/settings -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
  -d '{"retrievalMode":"LAST_N_VIDEOS","retrievalValue":3,"ownCommentLimit":150}'

RUN=$(curl -s -X POST https://$SITE/api/ideas/generate -H "Authorization: Bearer $T" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["runId"])')
curl -s https://$SITE/api/ideas/runs/$RUN -H "Authorization: Bearer $T"   # poll to COMPLETED
```

Open **`https://<your-label>.<region>.cloudapp.azure.com`** in a browser.

Watch memory while a job runs — this is the number that decides whether the deployment is
stable:

```bash
docker stats --no-stream
free -h
```

Some swap usage is expected and fine. Containers being killed and restarted is not; see
§10.4.

---

## 9. Step 7 — Operations

### Start on boot

Docker's `restart: unless-stopped` handles container restarts, and the Docker service
itself is enabled by default. Confirm with `sudo systemctl is-enabled docker`, then
reboot once (`sudo reboot`) and re-run the health check to prove the whole stack comes
back unattended.

### Deploying a change

```bash
cd ~/youtube-content-intelligence
git pull
docker compose --env-file .env -f deploy/azure/docker-compose.azure.yml up -d --build
```

Rebuilding only what changed is faster: append the service name, e.g. `--build backend`.

### Logs

```bash
docker compose -f deploy/azure/docker-compose.azure.yml logs -f --tail 100
docker logs yci-backend --tail 60
```

### Database backups

The managed server takes automatic backups with a 7-day retention by default. For a copy
you control:

```bash
mysqldump -h <MYSQL_HOST> -u yciadmin -p --ssl-mode=REQUIRED \
  --single-transaction --routines yci > yci-backup-$(date +%F).sql
```

### Budget alert

Do this on day one, before the $200 credit expires:

```bash
az consumption budget create \
  --budget-name yci-monthly --amount 5 --time-grain Monthly \
  --category Cost --start-date $(date +%Y-%m-01) --end-date $(date -d '+1 year' +%Y-%m-01)
```

If the CLI version rejects that, use **Cost Management → Budgets** in the portal. Either
way, set an email alert at 80%.

### Pausing to save hours

Deallocating stops VM compute billing (the disk and IP still bill):

```bash
az vm deallocate -g $RG -n $VM_NAME     # stop
az vm start -g $RG -n $VM_NAME          # resume — the public IP may change, see §10.5
```

The database can be stopped too, for up to 30 days:

```bash
az mysql flexible-server stop  -g $RG -n $MYSQL_NAME
az mysql flexible-server start -g $RG -n $MYSQL_NAME
```

---

## 10. Troubleshooting

### 10.1 Backend cannot reach the database

```
Communications link failure / connect timed out
```

The MySQL firewall does not have the VM's IP. Verify the rule matches the VM's *current*
public IP:

```bash
az mysql flexible-server firewall-rule list -g $RG -n $MYSQL_NAME -o table
az vm show -d -g $RG -n $VM_NAME --query publicIps -o tsv
```

From the VM, prove connectivity independently of the app:

```bash
mysql -h <MYSQL_HOST> -u yciadmin -p --ssl-mode=REQUIRED -e "SELECT 1;"
```

### 10.2 `SSL connection is required`

`MYSQL_URL` still carries `useSSL=false` (copied from the local `.env`). Use
`sslMode=REQUIRED`.

### 10.3 Every API call returns 401

Expected for anything except `/api/auth/signup`, `/api/auth/login` and `/api/health` —
the app requires `Authorization: Bearer <token>`. If the browser is signed in and *still*
gets 401, the token expired (`AUTH_TOKEN_TTL_DAYS`, default 30) or the account was
deleted; sign in again.

If sign-up itself fails with a database error, `database/schema.sql` was never loaded
(§6.4). `SHOW TABLES FROM yci;` returning fewer than 9 tables confirms it.

### 10.4 Containers restarting, `docker stats` shows a service at its limit

Out of memory. In order of preference: confirm swap is active (`free -h` — §6.1); confirm
the ML service is the **lite** build — it is not published to the internet, so check it
from the VM:

```bash
docker exec yci-ml-service python -c "import urllib.request,json; \
print(json.load(urllib.request.urlopen('http://localhost:8000/health')))"
# → {'status': 'UP', 'embedding_backend': 'tfidf-fallback'}
```

`tfidf-fallback` means the lite build is running; `sentence-transformers` means the full
image got built and will not fit. Then lower `-Xmx` toward 256m in the compose file. If the backend still cannot hold, the free VM
sizes are genuinely too small and the next step up is a paid size.

`dmesg | grep -i oom` names whatever the kernel killed.

### 10.5 The site stops resolving after a restart

A dynamically allocated public IP can change when a VM is deallocated and restarted. The
DNS label follows the IP automatically, so `$SITE` keeps working — but the **MySQL
firewall rule does not**. Re-run the firewall command in §5 with the new IP. To avoid
this entirely, make the IP static (it is the same price):

```bash
az network public-ip update -g $RG -n ${VM_NAME}PublicIP --allocation-method Static
```

### 10.6 Caddy cannot get a certificate

Check, in this order: port 80 is open in the NSG (§5); `$SITE_ADDRESS` in `.env` exactly
matches the FQDN; DNS resolves (`dig +short $SITE`). Let's Encrypt has rate limits — if
you hit one while experimenting, wait rather than retrying in a loop. `docker logs
yci-caddy` states the reason.

### 10.7 The build gets killed part-way

Almost always the Maven build hitting OOM without swap. Do §6.1, then rebuild. As a
fallback, build the jar on your laptop (`mvn -DskipTests package`) and copy `target/*.jar`
to the VM with a Dockerfile that only does the run stage.

### 10.8 Disk filling up

Docker build layers accumulate:

```bash
docker system df
docker system prune -af --volumes    # careful: also removes unused volumes
```

---

## 11. Teardown

Everything lives in one resource group:

```bash
az group delete --name $RG --yes --no-wait
```

Confirm afterwards that nothing survived — the group is the billing boundary:

```bash
az resource list --output table
```

---

## 12. If you outgrow the free tier

- **Full semantic quality** — the ML service needs ~1.5 GB on its own. A `B2s`
  (2 vCPU / 4 GiB) runs the full image alongside everything else, at real cost.
- **Separating the services** — once you are paying anyway, Container Apps with
  scale-to-zero on the ML service is cheaper than a second always-on VM, since topic
  discovery is bursty.
- **Not managing a server** — App Service (Linux, B1+) runs the backend container with
  managed TLS, certificates and deployment slots, and pairs with Static Web Apps for the
  frontend.
