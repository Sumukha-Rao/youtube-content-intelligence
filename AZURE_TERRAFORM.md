# Hosting on Azure with Terraform

Provisioning this project on Azure as code: one `terraform apply` creates the VM and
the managed database, and the VM builds and starts the stack on first boot.

Companion to [`AZURE_DEPLOYMENT.md`](AZURE_DEPLOYMENT.md), which builds the *same*
deployment by hand with `az`. Read that one if you want to understand the target
architecture and why it was chosen — §1 there argues the case against App Service,
Container Apps, AKS and a container registry, and none of that reasoning is repeated
here. Read this one if you want the deployment reproducible, reviewable and deletable.

The Terraform lives in [`deploy/azure/terraform/`](deploy/azure/terraform/).

---

## 1. What it creates

```
                    Internet
                       │  :443 HTTPS   :80 ACME   :22 SSH
                       ▼
   ┌───────────────────────────────────────────────┐
   │ azurerm_network_security_group   yci-nsg      │
   │ azurerm_public_ip (Standard, Static, DNS lbl) │
   │ azurerm_linux_virtual_machine    yci-vm       │
   │   Ubuntu 24.04 · B2ats_v2 · 64 GB P6 disk     │
   │   cloud-init ▸ swap, Docker, .env, compose up │
   │   ┌─────────────────────────────────────────┐ │
   │   │ caddy  ·  backend  ·  ml-service  ·  fe  │ │
   │   └─────────────────────────────────────────┘ │
   └────────────────────┬──────────────────────────┘
                        │ TLS :3306, one firewall rule
                        ▼
   ┌───────────────────────────────────────────────┐
   │ azurerm_mysql_flexible_server   B_Standard_B1ms│
   │ azurerm_mysql_flexible_database yci            │
   └───────────────────────────────────────────────┘
```

All of it inside one resource group:

| Resource | Notes |
|---|---|
| `azurerm_resource_group` | The billing and teardown boundary. |
| `azurerm_virtual_network` + `azurerm_subnet` | `10.42.0.0/16`, one `/24`. |
| `azurerm_network_security_group` (+ association) | 22, 80, 443. Nothing else. |
| `azurerm_public_ip` | **Standard SKU, Static**, with a DNS label. |
| `azurerm_network_interface` | |
| `azurerm_linux_virtual_machine` | Ubuntu 24.04, key-only login, cloud-init. |
| `azurerm_mysql_flexible_server` | Burstable B1ms, 20 GB, no HA, no auto-grow. |
| `azurerm_mysql_flexible_database` | `yci`, utf8mb4. |
| `azurerm_mysql_flexible_server_configuration` | `require_secure_transport = ON`. |
| `azurerm_mysql_flexible_server_firewall_rule` | The VM's IP, plus anything you add. |
| `azurerm_consumption_budget_resource_group` | Only if you set `budget_contact_emails`. |
| `random_password` / `random_id` ×3 | Database password, `APP_SECRET_KEY`, SearXNG secret. |

The defaults target a small deployment. Free-account eligibility and region/SKU
availability must be checked for your subscription; the configuration does not guarantee
a zero bill. See §9.

### Three choices worth knowing about before you apply

**The public IP is static.** This configuration explicitly requests a static Standard
SKU IP, so the address survives VM deallocation and the MySQL firewall rule stays
aligned. Replacing the IP resource still requires updating dependent configuration.
Check [current public-IP pricing](https://azure.microsoft.com/en-us/pricing/details/ip-addresses/)
for your region.

**cloud-init changes are ignored after the first boot.** `custom_data` is only ever read
once, on first boot, but Terraform still diffs it. Without `ignore_changes` a one-character
edit to `llm_model` would propose *destroying the VM* — the Docker images, the Caddy
certificate, everything — in order to deliver a changed `.env`. So the resource carries:

```hcl
lifecycle {
  ignore_changes = [custom_data]
}
```

The consequence is stated plainly rather than hidden: **after the first apply, application
settings are changed on the box**, not in `terraform.tfvars` (§7.2). To genuinely rebuild
the machine from the template, ask for it: `terraform apply -replace=azurerm_linux_virtual_machine.vm`.

**Ordering is what makes the unattended boot work.** The database firewall rule needs the
VM's public IP. Terraform creates the IP resource first, so the address is known before the
VM exists, and the VM is declared `depends_on` the firewall rule, the database and the NSG
association. By the time cloud-init reaches for MySQL, the path is already open — which is
why the schema can be loaded without you logging in.

---

## 2. Before you start

- An Azure account (`https://azure.microsoft.com/free`) and the Azure CLI, signed in:

  ```bash
  az login
  az account show --query id -o tsv     # this is your subscription_id
  ```

  Terraform authenticates through the CLI's session by default; no service principal
  is needed for a single operator.

- Terraform ≥ 1.6:

  ```bash
  terraform version
  ```

  Install: `https://developer.hashicorp.com/terraform/install`.

- An SSH key pair — `ssh-keygen -t ed25519` if you have none.

- Somewhere the VM can clone this repository from without a password prompt. If there
  is no such place, leave `repo_url` empty and copy the code yourself (§5.2).

### 2.1 Authentication and GitHub tokens

Terraform uses the AzureRM and random providers, not the GitHub provider. For local
operation, authenticate to Azure with `az login` and select the intended subscription;
see [AzureRM CLI authentication](https://registry.terraform.io/providers/hashicorp/azurerm/latest/docs/guides/azure_cli).

The current project repository is public and can be cloned without a GitHub PAT:

```hcl
repo_url = "https://github.com/Sumukha-Rao/youtube-content-intelligence.git"
```

An expired GitHub PAT does not block this public clone or Azure provisioning. Local
Git SSH authentication is independent and is not automatically installed on the VM.
If you make the repository private, either leave `repo_url = ""` and copy the source
(§5.2), or configure a read-only deploy key on the VM before cloning. A PAT is another
option for private HTTPS access, but do not embed it in `repo_url`: this configuration
can persist that value in Terraform state, cloud-init data and Git remote settings.
GitHub documents [PAT use for HTTPS Git](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens).

---

## 3. Quick start

```bash
cd deploy/azure/terraform
cp terraform.tfvars.example terraform.tfvars
$EDITOR terraform.tfvars          # subscription_id, repo_url, budget_contact_emails

terraform init
terraform plan                    # read it
terraform apply
```

`apply` takes about 8–12 minutes, most of it the MySQL server. It returns before the
application is up — the VM is still building images. Watch that part:

```bash
eval "$(terraform output -raw bootstrap_log_command)"
```

Then, once Caddy has its certificate:

```bash
curl -s "$(terraform output -raw site_url)/api/health"
# {"emailConfigured":false,"status":"UP"}

terraform output site_url         # open this in a browser
```

Collect the generated secrets:

```bash
terraform output -raw mysql_admin_password
terraform output -raw app_secret_key
```

`app_secret_key` encrypts the API keys your users save in Settings. Keep it. Changing
it does not break the app, but it makes every stored key unreadable and everyone has
to re-enter theirs.

---

## 4. Configuring it

Everything is in `variables.tf`; `terraform.tfvars.example` lists the ones you are
likely to touch. The defaults are chosen to stay inside the free tier, so a minimal
`terraform.tfvars` is two lines:

```hcl
subscription_id = "00000000-0000-0000-0000-000000000000"
repo_url        = "https://github.com/<you>/youtube-content-intelligence.git"
```

### 4.1 The variables that change the shape of the deployment

| Variable | Default | |
|---|---|---|
| `location` | `centralindia` | `centralindia` / `southindia` are closest from India. |
| `vm_size` | `Standard_B2ats_v2` | 2 vCPU, 1 GiB. `Standard_B1s` if the size is not offered in your region — check with `az vm list-skus --location "<region>" --size Standard_B2ats --all -o table`. |
| `dns_label` | random | Becomes `<label>.<region>.cloudapp.azure.com`, which is the HTTPS name and the certificate subject. Globally unique per region. |
| `repo_url` | `""` | Empty = provision and prepare the box, but do not clone or start anything. |
| `compose_profiles` | `[]` | `["searxng"]` adds the private metasearch container — see §4.3. |
| `load_schema` | `true` | Leave it on. See §5.3. |
| `ssh_source_address_prefix` | `"*"` | Who may reach port 22. |
| `budget_contact_emails` | `[]` | Empty means no budget alert is created. Don't leave it empty. |

### 4.2 Application settings

`youtube_api_key`, `llm_base_url`, `llm_api_key`, `llm_model`, `smtp_*`, `notify_*`,
`auth_token_ttl_days` and the rest map one-to-one onto the environment variables
documented in [`.env.example`](.env.example), and are written into `/opt/yci/.env` on
the VM.

All of them are *server-wide defaults*. Each account can override its own YouTube key,
model and search provider in the app under **Settings → API access**, so a shared
install does not have to share one quota or one bill. Blank values are fine if every
user brings their own.

Per-channel volumes — last N videos vs days, comment budgets, web search on or off —
are **not** environment variables at all. They are per-user rows in the database, edited
in the app.

### 4.3 Web search

`web_search_provider` defaults to `duckduckgo` here rather than the `searxng` of the
local compose file, because the SearXNG container is not running: it needs roughly
200 MiB, and the four services already account for the VM's whole gigabyte. To run it
anyway on a larger `vm_size`:

```hcl
vm_size             = "Standard_B2s"     # 4 GiB — this is a paid size
compose_profiles    = ["searxng"]
web_search_provider = "searxng"
web_search_base_url = "http://searxng:8080"
```

`duckduckgo` needs no container and no key but throttles repeat callers; `none` disables
search entirely. Users can also point at their own instance from Settings.

### 4.4 Locking down SSH

The default, `"*"`, matches what `az vm create` does: port 22 open to the internet, key
auth only, no password. If your address is stable, narrow it — the box holds the database
credentials and your API keys:

```hcl
ssh_source_address_prefix = "203.0.113.7"     # or "203.0.113.0/24"
```

Ports 80 and 443 stay open to `Internet` regardless. Port 80 is not decorative: Let's
Encrypt completes its HTTP-01 challenge over it on **every renewal**, not just the first,
so closing it breaks the site sixty days later.

---

## 5. What happens on the VM

Terraform's job ends at the boundary of the box. Inside it, `cloud-init.yaml.tftpl` does
what §6–§8 of `AZURE_DEPLOYMENT.md` asks you to do by hand.

### 5.1 The sequence

1. **Swap** — 4 GB (`swap_size_gb`), `vm.swappiness=10`, persisted in `/etc/fstab`.
   Not optional: 1 GiB of RAM with no swap gets the Maven build OOM-killed part-way
   through, which looks like a mysterious `docker compose` failure.
2. **Docker** — engine and compose plugin from Docker's own apt repository; the admin
   user is added to the `docker` group; the service is enabled so the stack survives
   a reboot.
3. **`/opt/yci/.env`** — written by Terraform, mode `0600`, then copied into the checkout.
4. **The code** — `git clone --depth 1 --branch <repo_branch> <repo_url>` into
   `/opt/yci/app`.
5. **The schema** — load `database/schema.sql` only into an empty configured database.
   Fresh schema already includes the migrations; populated databases are upgraded
   explicitly as described in §5.3.
6. **`docker compose --env-file .env -f deploy/azure/docker-compose.azure.yml up -d --build`**.

Progress goes to `/var/log/yci-bootstrap.log`; cloud-init's own output is in
`/var/log/cloud-init-output.log`. The script is idempotent and guarded by
`/opt/yci/.bootstrap-complete`, so re-running it is safe:

```bash
sudo /usr/local/bin/yci-bootstrap.sh
```

**The first build takes 15–30 minutes** on a burstable VM — Maven fetches and compiles
the Spring dependency tree and pip builds the Python wheels. `terraform apply` finishing
does not mean the site is up.

### 5.2 Without a `repo_url`

Leave it empty and steps 4–6 are skipped. Terraform still gives you a box with swap,
Docker, `mysql-client` and the `.env` file, and the log tells you what remains:

```bash
# from your machine, starting in the repository root
# Resolve the address before changing to the project's parent directory.
SITE=$(terraform -chdir=deploy/azure/terraform output -raw site_address)
cd ..
rsync -av --exclude '.venv' --exclude '.git' --exclude 'target' --exclude '__pycache__' \
  --exclude '.pytest_cache' --exclude '.idea' --exclude '.env' --exclude '.env.*' \
  --exclude '*.tfvars' --exclude '*.tfstate*' --exclude '.terraform' --exclude 'outputs' \
  youtube-content-intelligence/ azureuser@"$SITE":/opt/yci/app/

# on the VM
sudo cp /opt/yci/.env /opt/yci/app/.env
cd /opt/yci/app
docker compose --env-file .env -f deploy/azure/docker-compose.azure.yml up -d --build
```

This is the right choice for a private repository you would rather not hand a token to.

### 5.3 Why the schema is loaded explicitly

In local Docker, `database/schema.sql` is run by the MySQL container's entrypoint. A
managed server has no such hook, and Hibernate's `ddl-auto=update` is **not** a
substitute: several entities carry a plain `Long userId` column rather than a JPA
association, so Hibernate emits no foreign keys for `user_settings`, `idea_runs`,
`auth_tokens` or `competitor_comments`, so database-level cascades would be missing.
Services explicitly clean up dependent rows; retain the SQL constraints to protect direct SQL and other writers too.

cloud-init loads the file only when the database has zero tables, so it will not stamp
on an existing deployment. The `mysql` client reads its credentials from
`/opt/yci/.my.cnf` (mode `0600`) rather than a `-p` argument, so the password never
lands in `ps` output or in anyone's shell history.

**Existing deployments:** first-boot schema loading skips a database that already has
tables. Back up the database, inspect the columns and, if `DDL_AUTO` is not `update`,
apply [migration 002](database/migrations/002_competitor_suggestions_and_summaries.sql)
once for missing discovery, video-snapshot and recommendation JSON columns. Do not
reapply after Hibernate or an earlier migration has added them. No new tables or
historical-run backfill are needed; generate a new run for related-video associations.

### 5.4 Fitting in 1 GiB

`docker-compose.azure.yml` builds the ML service from `Dockerfile.lite` — TF-IDF backend,
no `torch` — and caps every service. The details, and what the lite build gives up, are
in `AZURE_DEPLOYMENT.md` §7. The short version: explicit "make a video on X" requests
are found by regex and are unaffected; *grouping* of paraphrased requests degrades from
semantic embeddings to shared vocabulary. Confirm which build is running:

```bash
docker exec yci-ml-service python -c "import urllib.request,json; \
print(json.load(urllib.request.urlopen('http://localhost:8000/health')))"
# → {'status': 'UP', 'embedding_backend': 'tfidf-fallback'}
```

---

## 6. State

`terraform.tfstate` contains **the database password and `APP_SECRET_KEY` in clear text**.
This configuration persists those resource and bootstrap values in state. Treat the
file and its backups as secrets. `deploy/azure/terraform/.gitignore` keeps it and `*.tfvars` out of the
repository; `.terraform.lock.hcl` is deliberately *not* ignored, so everyone resolves the
same provider versions.

For anything beyond one operator, move state into a storage account with access control
and versioning. The block is in `versions.tf`, commented out:

```hcl
backend "azurerm" {
  resource_group_name  = "tfstate-rg"
  storage_account_name = "yciTfState"
  container_name       = "tfstate"
  key                  = "yci.tfstate"
}
```

Create that account first — a bootstrap problem Terraform cannot solve for itself:

```bash
az group create -n tfstate-rg -l centralindia
az storage account create -n ycitfstate$RANDOM -g tfstate-rg -l centralindia \
  --sku Standard_LRS --encryption-services blob --min-tls-version TLS1_2
az storage container create -n tfstate --account-name "<storage-account-name>"
```

Then `terraform init -migrate-state`.

---

## 7. Day-two operations

### 7.1 Deploying a code change

Terraform is not involved. The VM has the checkout:

```bash
ssh azureuser@$(terraform output -raw site_address)
cd /opt/yci/app
git pull
docker compose --env-file .env -f deploy/azure/docker-compose.azure.yml up -d --build
```

Append a service name to rebuild only that one, e.g. `--build backend`.

### 7.2 Changing an application setting

Also not Terraform — see the `ignore_changes` discussion in §1. Edit the file on the box:

```bash
sudo -e /opt/yci/.env
cd /opt/yci/app && sudo cp /opt/yci/.env .env
docker compose --env-file .env -f deploy/azure/docker-compose.azure.yml up -d
```

Keep `terraform.tfvars` in step anyway, so a future `-replace` rebuilds the machine you
actually have rather than the one you first described.

For SMTP setup, account opt-in and controlled delivery checks, use the
[email guide](docs/email-notifications-implementation.md). With Azure Compose, recreate
the backend using the deployment file after changing `.env`. Blank SMTP stops sending,
but opted-in accounts can still consume generation quota. Daily/weekly delivery uses a
rolling attempt timestamp; there is no guaranteed send time or separate delivery retry.

### 7.2.1 Deploying the recommendation features

Publish the reviewed source revision before pulling on the VM; local uncommitted changes
are not included in a GitHub clone. Rebuild both `backend` and `ml-service` so the Python
caption dependency and Java endpoints are deployed together. Frontend files are mounted
from the checkout and appear after refresh. Preserve the existing ML dependency profile.

Verify the public health route, sign in and test: save a creator channel → verified
suggestions → add a competitor → generate ideas → inspect related links → request a
transcript summary. Skipping creator setup must retain manual competitor entry and no
automatic suggestions. Treat blocked subtitle requests as errors; do not add bypasses.
See [feature deployment and checks](docs/recommendation-features.md#database-and-deployment).

### 7.3 Changing the infrastructure

This *is* Terraform. Resizing the VM, growing the disk, opening the database to another
address, adjusting the budget — edit the variable and `terraform apply`. Read the plan:
a resize restarts the VM, and a change to `os_disk_size_gb` or `source_image_reference`
replaces it.

### 7.4 Reaching the database from your laptop

```hcl
mysql_allowed_client_cidrs = {
  my-laptop = { start = "203.0.113.7", end = "203.0.113.7" }
}
```

`terraform apply`, then:

```bash
eval "$(terraform output -raw mysql_client_command)"
```

Remove the entry and apply again when you are done. For a backup you control:

```bash
mysqldump -h $(terraform output -raw mysql_fqdn) -u yciadmin -p --ssl-mode=REQUIRED \
  --single-transaction --routines yci > yci-backup-$(date +%F).sql
```

The managed server also takes automatic backups, retained 7 days by default
(`mysql_backup_retention_days`).

### 7.5 Pausing to save free hours

Outside Terraform, and Terraform will not fight you about it — a deallocated VM is
still the same resource:

```bash
az vm deallocate -g yci-rg -n yci-vm     # stop compute billing; disk and IP still bill
az vm start      -g yci-rg -n yci-vm

az mysql flexible-server stop  -g yci-rg -n "<server>"   # up to 30 days
az mysql flexible-server start -g yci-rg -n "<server>"
```

Because the public IP is static, the database firewall rule stays correct across a
stop/start. That is the whole reason it is static.

---

## 8. Teardown

```bash
terraform destroy
```

It removes everything it created, including the database and its backups. There is no
undo. Then confirm nothing survived — the resource group is the billing boundary:

```bash
az resource list --output table
```

`terraform destroy` will not delete a resource group that has picked up resources
Terraform does not know about (`prevent_deletion_if_contains_resources` in `versions.tf`).
That is the intended behaviour: it tells you something is there rather than taking it
with everything else.

---

## 9. What this costs

Azure lists limited-time allowances for eligible Linux VMs and MySQL B1ms instances;
eligibility, included storage and available sizes depend on the account and region.
Confirm the selected resources against [Azure's free-service listing](https://azure.microsoft.com/en-in/pricing/free-services/)
and your subscription's **Free services** page before applying. Do not assume the disk,
IP, traffic, LLM usage or resources beyond the allowance are covered.

Standard public IPv4 addresses and outbound data can add charges. Use the
[current IP pricing](https://azure.microsoft.com/en-us/pricing/details/ip-addresses/)
and [pricing calculator](https://azure.microsoft.com/en-us/pricing/calculator/) for your
region and currency. Promotional allowances expire while provisioned resources continue
to exist. A budget alert is a notification, not a spending cap or automatic shutdown.

So set the budget on day one:

```hcl
budget_contact_emails = ["you@example.com"]
budget_amount         = 5
```

That creates two alerts — at 80% of *actual* spend, and at 100% of *forecast*, which is
the one that warns you before the month ends rather than after.

> Free-tier quantities change. Confirm current limits on the portal's **Free services**
> page before you provision.

---

## 10. Troubleshooting

### 10.1 `terraform apply` succeeded but the site does not answer

Almost always: it is still building. Check.

```bash
ssh azureuser@$(terraform output -raw site_address) 'sudo tail -f /var/log/yci-bootstrap.log'
```

`building and starting the stack` with no `done` line yet means wait — 15–30 minutes is
normal on a burstable VM.

### 10.2 The build was killed part-way

Maven hitting OOM. Confirm swap is actually on (`free -h`); if it is not, the swap step
failed and everything after it is unreliable — re-run `sudo /usr/local/bin/yci-bootstrap.sh`.
`dmesg | grep -i oom` names whatever the kernel killed.

### 10.3 Caddy cannot get a certificate

In this order: `docker logs yci-caddy` states the reason; `dig +short $(terraform output -raw site_address)`
should return the value of `terraform output vm_public_ip`; port 80 must be open (it is,
unless the NSG was edited outside Terraform — `terraform plan` will say so). Let's Encrypt
rate-limits; if you hit one while experimenting, wait rather than retrying in a loop.

### 10.4 Backend cannot reach the database

```
Communications link failure / connect timed out
```

The firewall rule and the VM's current IP have diverged. With a static IP they should not,
so check that the IP really is static and that nobody replaced it:

```bash
az mysql flexible-server firewall-rule list -g yci-rg -n "<server>" -o table
terraform output vm_public_ip
```

From the VM, prove connectivity independently of the app:

```bash
sudo mysql --defaults-extra-file=/opt/yci/.my.cnf -e "SELECT 1;"
```

`SSL connection is required` instead means `MYSQL_URL` lost its `sslMode=REQUIRED` — the
managed server rejects plaintext.

### 10.5 Sign-up fails with a database error

The schema never loaded. Ten tables are expected:

```bash
sudo mysql --defaults-extra-file=/opt/yci/.my.cnf -e "SHOW TABLES FROM yci;"
```

Fix by re-running the bootstrap script, which loads it if and only if the table count is
zero.

### 10.6 Terraform wants to replace the VM

Read the `# forces replacement` lines in the plan. Legitimate causes: `os_disk_size_gb`,
`source_image_reference`, `admin_username`, `custom_data` (only if you removed the
`ignore_changes`). If it is `custom_data` and you did not intend a rebuild, that block
is missing.

A deliberate rebuild — new image, changed cloud-init — destroys the Docker images and
the Caddy certificate but **not** the database, which is a separate resource:

```bash
terraform apply -replace=azurerm_linux_virtual_machine.vm
```

### 10.7 `Standard_B2ats_v2 is not available in this region`

```bash
az vm list-skus --location "<region>" --size Standard_B2ats --all -o table
```

Set `vm_size = "Standard_B1s"`. Same 1 GiB of RAM and the same free allowance; only the
build takes longer.

### 10.8 `dns_label` or `mysql_server_name` already taken

Both are globally unique. The defaults append a random suffix, so this only happens when
you set them yourself — pick another, or clear the variable and let Terraform choose.

### 10.9 Disk filling up

Docker build layers accumulate on every `--build`:

```bash
docker system df
docker system prune -af          # add --volumes carefully: it drops unused volumes too
```

---

## 11. Where this stops

Terraform describes the infrastructure. It does not describe the application: the images
are built on the VM by `docker compose`, and a code change is a `git pull` (§7.1), not an
apply. That is a deliberate trade for the free tier — a container registry is not in the
free tier, and building on the box avoids it entirely.

If you outgrow that, the pieces to change are known:

- **Full semantic quality** — the ML service needs ~1.5 GB on its own; `Standard_B2s`
  (2 vCPU / 4 GiB) runs the full image alongside everything else, at real cost. Change
  `vm_size` and build from `Dockerfile` instead of `Dockerfile.lite`.
- **Immutable deploys** — add `azurerm_container_registry`, push images from CI, and
  reduce cloud-init to `docker compose pull && up -d`.
- **No server to manage** — App Service (Linux, B1+) for the backend container with
  managed certificates and deployment slots, `azurerm_static_site` for the frontend.
  Both are ordinary `azurerm` resources; the reason they are not here is cost, and it
  is argued in `AZURE_DEPLOYMENT.md` §1.
