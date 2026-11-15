# Terraform — Azure deployment

Provisions the VM and managed MySQL server this project runs on, and hands the VM
a cloud-init file that installs Docker and starts the stack.

```bash
# Run from deploy/azure/terraform/ after selecting the intended Azure subscription.
az login
cp terraform.tfvars.example terraform.tfvars   # fill in subscription_id and repo_url
terraform init
terraform apply
terraform output site_url
```

The full guide — what it creates, why this shape, day-2 operations, drift, and
troubleshooting — is [`../../../AZURE_TERRAFORM.md`](../../../AZURE_TERRAFORM.md).
For the same deployment built by hand with `az`, see
[`../../../AZURE_DEPLOYMENT.md`](../../../AZURE_DEPLOYMENT.md).

| File | |
|---|---|
| `versions.tf` | Provider pins and the optional remote state backend. |
| `variables.tf` | Every input; check account eligibility, SKU availability and pricing before applying. |
| `main.tf` | Resource group, network, VM, MySQL, firewall, budget. |
| `outputs.tf` | Site URL, SSH command, generated secrets. |
| `cloud-init.yaml.tftpl` | First-boot: swap, Docker, `.env`, schema, `compose up`. |
| `terraform.tfvars.example` | Copy to `terraform.tfvars`. Never commit that copy. |

`terraform.tfstate` holds the database password and `APP_SECRET_KEY` in clear
text. It is gitignored here; treat the file itself as a secret.

## Authentication and source access

Azure credentials authorize infrastructure creation. The public project URL
`https://github.com/Sumukha-Rao/youtube-content-intelligence.git` needs no GitHub PAT.
An expired PAT does not block Azure provisioning or that public clone. Local Git SSH
access is separate from credentials available to the VM.

For private source, leave `repo_url` empty and copy the checkout, or configure a
read-only deploy key before cloning. Do not embed a PAT in `repo_url`; it can enter
state, cloud-init data and Git configuration. See the
[full authentication guide](../../../AZURE_TERRAFORM.md#21-authentication-and-github-tokens).

## Deploying the current application

Publish the source revision before the VM clones/pulls it. Rebuild the backend and ML
service together for verified competitor suggestions, persisted video associations and
caption summaries. Existing deployments with automatic schema updates disabled need
[migration 002](../../../database/migrations/002_competitor_suggestions_and_summaries.sql)
only if its columns are missing. Fresh schema loading does not upgrade a populated DB.
Older databases must also apply the repeatable
[migration 003](../../../database/migrations/003_competitor_comment_uniqueness.sql)
if they retain the global competitor-comment unique key. Hibernate `update` adds
the scoped key but does not remove the obsolete global key.

## Verify without provisioning

From this directory:

```bash
terraform init -backend=false
terraform fmt -check
terraform validate
python3 tests/test_bootstrap.py
```

The bootstrap tests require Terraform, Docker Compose, the `busybox` image and
PyYAML. They render cloud-init with synthetic values, check shell syntax, confirm
private service ports, and round-trip quotes, dollars, backslashes and newlines
through a disposable container. They do not contact Azure or start the application.

For a live read-only plan, select an **Enabled** subscription with `az login` and
`az account set --subscription ...`, populate `terraform.tfvars`, then run:

```bash
ARM_RESOURCE_PROVIDER_REGISTRATIONS=none terraform plan -input=false
```

This disables automatic provider registration during verification. Required
resource providers must already be registered before deployment. A subscription
in `Warned` state cannot create resources; resolve its account/billing status or
select an enabled subscription before deployment. See
[Azure subscription states](https://learn.microsoft.com/en-us/azure/cost-management-billing/manage/subscription-states).

`validate` and the bootstrap tests do not verify subscription access, regional
capacity, VM/MySQL quotas, resource naming availability, first boot or public TLS.
An `apply` and a successful deployed `/api/health` check are still required to
confirm a live deployment.

The frontend remains static and includes the refreshed creator workspace. Email setup
is optional; use the [SMTP guide](../../../docs/email-notifications-implementation.md)
and leave per-account notifications off until delivery is configured. After first boot,
edit application environment values on the VM; changing tfvars alone does not reapply
cloud-init. Free-offer eligibility and cost must be checked in the Azure portal; budget
alerts do not enforce spending caps.
