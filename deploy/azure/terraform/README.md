# Terraform — Azure deployment

Provisions the VM and managed MySQL server this project runs on, and hands the VM
a cloud-init file that installs Docker and starts the stack.

```bash
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
| `variables.tf` | Every input, with defaults that stay inside the free tier. |
| `main.tf` | Resource group, network, VM, MySQL, firewall, budget. |
| `outputs.tf` | Site URL, SSH command, generated secrets. |
| `cloud-init.yaml.tftpl` | First-boot: swap, Docker, `.env`, schema, `compose up`. |
| `terraform.tfvars.example` | Copy to `terraform.tfvars`. Never commit that copy. |

`terraform.tfstate` holds the database password and `APP_SECRET_KEY` in clear
text. It is gitignored here; treat the file itself as a secret.
