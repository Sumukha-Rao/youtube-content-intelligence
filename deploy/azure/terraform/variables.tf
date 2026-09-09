# ─────────────────────────────────────────────────────────────────────────────
# Azure account and placement
# ─────────────────────────────────────────────────────────────────────────────

variable "subscription_id" {
  description = "Azure subscription id to deploy into (`az account show --query id -o tsv`)."
  type        = string
}

variable "location" {
  description = "Azure region. centralindia / southindia are the closest from India."
  type        = string
  default     = "centralindia"
}

variable "prefix" {
  description = "Short name prefix for every resource. Lowercase letters and digits."
  type        = string
  default     = "yci"

  validation {
    condition     = can(regex("^[a-z][a-z0-9]{1,10}$", var.prefix))
    error_message = "prefix must be 2-11 characters, lowercase letters and digits, starting with a letter."
  }
}

variable "resource_group_name" {
  description = "Resource group to create. It is the billing and teardown boundary."
  type        = string
  default     = "yci-rg"
}

variable "tags" {
  description = "Tags applied to every resource."
  type        = map(string)
  default = {
    project = "youtube-content-intelligence"
    managed = "terraform"
  }
}

# ─────────────────────────────────────────────────────────────────────────────
# Virtual machine
# ─────────────────────────────────────────────────────────────────────────────

variable "vm_size" {
  description = <<-EOT
    VM size. Standard_B2ats_v2 and Standard_B1s are both inside the 750 free hours;
    B2ats_v2 gives 2 vCPUs at the same 1 GiB of RAM, so the image build is roughly
    twice as fast. Check availability first:
      az vm list-skus --location <region> --size Standard_B2ats --all -o table
  EOT
  type        = string
  default     = "Standard_B2ats_v2"
}

variable "admin_username" {
  description = "Linux login on the VM. Password auth is disabled; this account gets your SSH key."
  type        = string
  default     = "azureuser"
}

variable "ssh_public_key_path" {
  description = "Public half of the key pair used to reach the VM."
  type        = string
  default     = "~/.ssh/id_ed25519.pub"
}

variable "ssh_source_address_prefix" {
  description = <<-EOT
    Who may reach port 22. "*" is the whole internet, which is what `az vm create`
    does by default and what most people leave in place. Narrow it to your own
    address ("203.0.113.7" or "203.0.113.0/24") if that address is stable —
    the VM holds your database credentials and API keys.
  EOT
  type        = string
  default     = "*"
}

variable "os_disk_size_gb" {
  description = <<-EOT
    OS disk size. 64 GB of Premium_LRS is a P6, which is the size the free-tier
    disk allowance covers — the default 30 GB is a P4 and is *not* covered.
  EOT
  type        = number
  default     = 64
}

variable "os_disk_type" {
  description = "OS disk SKU. Premium_LRS at 64 GB is the free-tier P6."
  type        = string
  default     = "Premium_LRS"
}

variable "dns_label" {
  description = <<-EOT
    DNS label for the public IP, giving <label>.<region>.cloudapp.azure.com. Must be
    globally unique within the region. Left empty, a random suffix is appended to
    `prefix`. This name is the certificate subject Caddy asks Let's Encrypt for, so
    it is what makes HTTPS work without buying a domain.
  EOT
  type        = string
  default     = ""
}

variable "swap_size_gb" {
  description = "Swap file created on first boot. 1 GiB of RAM with no swap OOMs during the Maven build."
  type        = number
  default     = 4
}

# ─────────────────────────────────────────────────────────────────────────────
# Database — Azure Database for MySQL Flexible Server
# ─────────────────────────────────────────────────────────────────────────────

variable "mysql_server_name" {
  description = "Server name; globally unique. Empty means prefix + a random suffix."
  type        = string
  default     = ""
}

variable "mysql_sku_name" {
  description = "B_Standard_B1ms is the free-tier size. Anything larger bills from day one."
  type        = string
  default     = "B_Standard_B1ms"
}

variable "mysql_version" {
  description = "MySQL major version."
  type        = string
  default     = "8.0.21"
}

variable "mysql_storage_gb" {
  description = "Provisioned storage. 20 GB is inside the free allowance and the schema is tiny."
  type        = number
  default     = 20
}

variable "mysql_admin_username" {
  description = "Server administrator login."
  type        = string
  default     = "yciadmin"
}

variable "mysql_admin_password" {
  description = <<-EOT
    Administrator password. Leave null and one is generated for you — read it back
    with `terraform output -raw mysql_admin_password`.
  EOT
  type        = string
  default     = null
  sensitive   = true
}

variable "mysql_database_name" {
  description = "Application database."
  type        = string
  default     = "yci"
}

variable "mysql_backup_retention_days" {
  description = "Automatic backup retention. 7 days is the included default."
  type        = number
  default     = 7
}

variable "mysql_allowed_client_cidrs" {
  description = <<-EOT
    Extra addresses allowed through the database firewall, on top of the VM, as a
    map of rule name to a { start, end } pair. Use it to reach the server from your
    laptop with the `mysql` client; remove it again afterwards.

    Example:
      { my-laptop = { start = "203.0.113.7", end = "203.0.113.7" } }
  EOT
  type = map(object({
    start = string
    end   = string
  }))
  default = {}
}

# ─────────────────────────────────────────────────────────────────────────────
# Application bootstrap
#
# These become /opt/yci/.env on the VM, which cloud-init copies into the checkout
# and docker compose reads. Everything here is a *server-wide default*: each user
# can override their own YouTube key, model and search provider in the app under
# Settings → API access.
# ─────────────────────────────────────────────────────────────────────────────

variable "repo_url" {
  description = <<-EOT
    Git URL cloned onto the VM and started automatically. Must be reachable without
    interactive credentials (a public HTTPS URL, or one with a token in it).

    Left empty, Terraform still provisions and prepares the box — Docker, swap,
    mysql-client, the .env file — and leaves the checkout and `docker compose up`
    to you. That is the right choice for a private repo you would rather rsync.
  EOT
  type        = string
  default     = ""
}

variable "repo_branch" {
  description = "Branch to check out when repo_url is set."
  type        = string
  default     = "main"
}

variable "load_schema" {
  description = <<-EOT
    Load database/schema.sql into the managed server on first boot, if the database
    has no tables yet.

    Keep this on. Hibernate's ddl-auto=update creates most tables but emits no
    foreign keys for user_settings, idea_runs, auth_tokens or competitor_comments —
    those entities carry plain `Long userId` columns rather than JPA associations —
    so deleting an account would orphan its rows.
  EOT
  type        = bool
  default     = true
}

variable "compose_profiles" {
  description = <<-EOT
    Compose profiles to enable, e.g. ["searxng"] for the private metasearch
    instance. SearXNG needs roughly 200 MiB, which a 1 GiB VM running everything
    else does not have — only worth it on a larger vm_size.
  EOT
  type        = list(string)
  default     = []
}

variable "app_secret_key" {
  description = <<-EOT
    Encrypts the API keys users save in Settings before they reach MySQL. Empty
    means one is generated (64 hex characters). Changing it later makes existing
    saved keys unreadable — users simply re-enter them.
  EOT
  type        = string
  default     = ""
  sensitive   = true
}

variable "youtube_api_key" {
  description = "Default YouTube Data API v3 key, used by accounts that have not saved their own."
  type        = string
  default     = ""
  sensitive   = true
}

variable "youtube_max_comments_per_video" {
  description = "Hard cap on comments requested per single video."
  type        = number
  default     = 200
}

variable "llm_base_url" {
  description = "OpenAI-compatible /chat/completions base URL. Prefer a non-reasoning model."
  type        = string
  default     = "https://api.openai.com/v1"
}

variable "llm_api_key" {
  description = "Key for llm_base_url."
  type        = string
  default     = ""
  sensitive   = true
}

variable "llm_model" {
  description = "Default model id."
  type        = string
  default     = "gpt-4o-mini"
}

variable "llm_timeout_seconds" {
  description = "Generous on purpose: generation runs as a background job and a CPU model is slow."
  type        = number
  default     = 900
}

variable "llm_max_prompt_chars" {
  description = "Prompt truncation budget."
  type        = number
  default     = 24000
}

variable "web_search_provider" {
  description = <<-EOT
    searxng | duckduckgo | none. duckduckgo is the default here because the SearXNG
    container is off unless the `searxng` compose profile is enabled, and a provider
    with nowhere to go finds nothing.
  EOT
  type        = string
  default     = "duckduckgo"

  validation {
    condition     = contains(["searxng", "duckduckgo", "none"], var.web_search_provider)
    error_message = "web_search_provider must be one of: searxng, duckduckgo, none."
  }
}

variable "web_search_base_url" {
  description = "SearXNG instance to query. http://searxng:8080 for the bundled container."
  type        = string
  default     = ""
}

variable "web_search_fallback" {
  description = "Try DuckDuckGo when SearXNG comes back empty."
  type        = bool
  default     = true
}

variable "searxng_secret" {
  description = "Session secret for the bundled SearXNG. Empty means one is generated."
  type        = string
  default     = ""
  sensitive   = true
}

variable "auth_token_ttl_days" {
  description = "Lifetime of a session's bearer token."
  type        = number
  default     = 30
}

variable "smtp_host" {
  description = "SMTP server for email digests. Blank keeps sending off — the preference is still saved."
  type        = string
  default     = ""
}

variable "smtp_port" {
  description = "SMTP port."
  type        = number
  default     = 587
}

variable "smtp_username" {
  description = "SMTP username."
  type        = string
  default     = ""
}

variable "smtp_password" {
  description = "SMTP password. For Gmail, an app password."
  type        = string
  default     = ""
  sensitive   = true
}

variable "notify_from" {
  description = "From address on digest emails."
  type        = string
  default     = "yci@localhost"
}

variable "notify_cron" {
  description = "Spring cron for the digest scheduler; each user is mailed on their own cadence."
  type        = string
  default     = "0 0 * * * *"
}

variable "ddl_auto" {
  description = "Hibernate schema handling. `update` fills gaps left by schema.sql; `validate` locks it down."
  type        = string
  default     = "update"
}

# ─────────────────────────────────────────────────────────────────────────────
# Cost guardrail
# ─────────────────────────────────────────────────────────────────────────────

variable "budget_contact_emails" {
  description = <<-EOT
    Addresses alerted when spend crosses the thresholds below. Empty means no budget
    is created — set it. "Free account" means a few dollars a month (a Standard
    public IPv4 address, egress past the free allowance), not zero, and the free
    tiers expire after 12 months while the resources keep running.
  EOT
  type        = list(string)
  default     = []
}

variable "budget_amount" {
  description = "Monthly budget in the subscription's billing currency."
  type        = number
  default     = 5
}
