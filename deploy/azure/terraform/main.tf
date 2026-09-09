# ─────────────────────────────────────────────────────────────────────────────
# YouTube Content Intelligence — Azure infrastructure
#
# Two billable resources and the plumbing between them:
#
#     Internet ──HTTPS──▶ Azure VM (Ubuntu 24.04, 1 GiB)
#                           caddy      TLS, /api/* → backend, /* → frontend
#                           backend    Spring Boot
#                           ml-service FastAPI, Dockerfile.lite
#                           frontend   nginx (static)
#                             │
#                             └──TLS:3306──▶ Azure Database for MySQL Flexible Server
#
# This is the same shape AZURE_DEPLOYMENT.md builds by hand; the reasoning for it
# (why not App Service, Container Apps, AKS, ACR) is in that document's §1.
#
# The VM is provisioned by cloud-init: swap, Docker, the .env file, the schema
# load and `docker compose up`. Terraform's job ends at the boundary of the box.
# ─────────────────────────────────────────────────────────────────────────────

resource "random_string" "suffix" {
  length  = 6
  lower   = true
  upper   = false
  numeric = true
  special = false
}

# Used only when mysql_admin_password is left null. The character set avoids
# quotes, backslashes and @, which travel badly through JDBC URLs and shell here-docs.
resource "random_password" "mysql" {
  length           = 28
  min_upper        = 2
  min_lower        = 2
  min_numeric      = 2
  min_special      = 2
  override_special = "!#%*()-_=+[]"
}

# 64 hex characters — the same thing `openssl rand -hex 32` produces.
resource "random_id" "app_secret" {
  byte_length = 32
}

resource "random_id" "searxng_secret" {
  byte_length = 32
}

locals {
  dns_label   = var.dns_label != "" ? var.dns_label : "${var.prefix}-${random_string.suffix.result}"
  mysql_name  = var.mysql_server_name != "" ? var.mysql_server_name : "${var.prefix}-mysql-${random_string.suffix.result}"
  vm_name     = "${var.prefix}-vm"
  mysql_admin = var.mysql_admin_password != null ? var.mysql_admin_password : random_password.mysql.result

  app_secret_key = var.app_secret_key != "" ? var.app_secret_key : random_id.app_secret.hex
  searxng_secret = var.searxng_secret != "" ? var.searxng_secret : random_id.searxng_secret.hex

  # <label>.<region>.cloudapp.azure.com — a real DNS name, which is what lets Caddy
  # get a Let's Encrypt certificate without you buying a domain.
  site_address = "${local.dns_label}.${var.location}.cloudapp.azure.com"

  # sslMode=REQUIRED, not the useSSL=false of the local compose file: the managed
  # server runs with require_secure_transport ON and rejects plaintext.
  mysql_url = join("", [
    "jdbc:mysql://${azurerm_mysql_flexible_server.db.fqdn}:3306/${var.mysql_database_name}",
    "?sslMode=REQUIRED&serverTimezone=UTC&allowPublicKeyRetrieval=true",
  ])
}

resource "azurerm_resource_group" "rg" {
  name     = var.resource_group_name
  location = var.location
  tags     = var.tags
}

# ─────────────────────────────────────────────────────────────────────────────
# Network
# ─────────────────────────────────────────────────────────────────────────────

resource "azurerm_virtual_network" "vnet" {
  name                = "${var.prefix}-vnet"
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location
  address_space       = ["10.42.0.0/16"]
  tags                = var.tags
}

resource "azurerm_subnet" "app" {
  name                 = "${var.prefix}-subnet"
  resource_group_name  = azurerm_resource_group.rg.name
  virtual_network_name = azurerm_virtual_network.vnet.name
  address_prefixes     = ["10.42.1.0/24"]
}

# Only 22, 80 and 443 are reachable. Nothing else needs to be: inside the VM only
# Caddy publishes ports, and backend / ml-service / frontend talk over the Docker
# network.
resource "azurerm_network_security_group" "nsg" {
  name                = "${var.prefix}-nsg"
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location
  tags                = var.tags

  security_rule {
    name                       = "ssh"
    priority                   = 1000
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "22"
    source_address_prefix      = var.ssh_source_address_prefix
    destination_address_prefix = "*"
  }

  # Port 80 must stay open even though the app redirects to HTTPS: Let's Encrypt
  # completes the HTTP-01 challenge over it, on every renewal, not just the first.
  security_rule {
    name                       = "http"
    priority                   = 1010
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "80"
    source_address_prefix      = "Internet"
    destination_address_prefix = "*"
  }

  security_rule {
    name                       = "https"
    priority                   = 1020
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "443"
    source_address_prefix      = "Internet"
    destination_address_prefix = "*"
  }
}

resource "azurerm_subnet_network_security_group_association" "app" {
  subnet_id                 = azurerm_subnet.app.id
  network_security_group_id = azurerm_network_security_group.nsg.id
}

# Static, deliberately. A dynamic IP can change when the VM is deallocated and
# restarted; the DNS label follows it, but the database firewall rule below does
# not — so the site would resolve and the backend would still be locked out. A
# Standard SKU IP costs the same either way.
resource "azurerm_public_ip" "vm" {
  name                = "${var.prefix}-pip"
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location
  allocation_method   = "Static"
  sku                 = "Standard"
  domain_name_label   = local.dns_label
  tags                = var.tags
}

resource "azurerm_network_interface" "vm" {
  name                = "${var.prefix}-nic"
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location
  tags                = var.tags

  ip_configuration {
    name                          = "internal"
    subnet_id                     = azurerm_subnet.app.id
    private_ip_address_allocation = "Dynamic"
    public_ip_address_id          = azurerm_public_ip.vm.id
  }
}

# ─────────────────────────────────────────────────────────────────────────────
# Database
#
# Managed rather than a container on the VM: MySQL would eat ~440 MB of the box's
# 1 GiB, and the B1ms server is free for 12 months.
# ─────────────────────────────────────────────────────────────────────────────

resource "azurerm_mysql_flexible_server" "db" {
  name                = local.mysql_name
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location
  tags                = var.tags

  administrator_login    = var.mysql_admin_username
  administrator_password = local.mysql_admin

  # Burstable B1ms is the size the free tier covers. HA is left off (a `high_availability`
  # block would double the instance), as is geo-redundant backup.
  sku_name                     = var.mysql_sku_name
  version                      = var.mysql_version
  backup_retention_days        = var.mysql_backup_retention_days
  geo_redundant_backup_enabled = false

  storage {
    size_gb = var.mysql_storage_gb
    # Off on purpose: auto-grow would silently expand past the free storage
    # allowance and start billing. The schema is tiny; watch usage instead.
    auto_grow_enabled = false
  }

  lifecycle {
    # Azure picks an availability zone at creation. Reading it back as a change
    # would otherwise propose replacing the server — and the data with it.
    ignore_changes = [zone]
  }
}

resource "azurerm_mysql_flexible_database" "app" {
  name                = var.mysql_database_name
  resource_group_name = azurerm_resource_group.rg.name
  server_name         = azurerm_mysql_flexible_server.db.name
  charset             = "utf8mb4"
  collation           = "utf8mb4_unicode_ci"
}

# Explicit rather than implicit. It is the server default, and MySQL Connector/J
# with sslMode=REQUIRED needs no truststore setup to satisfy it, so there is no
# reason to turn it off — but stating it keeps a future edit honest.
resource "azurerm_mysql_flexible_server_configuration" "require_tls" {
  name                = "require_secure_transport"
  resource_group_name = azurerm_resource_group.rg.name
  server_name         = azurerm_mysql_flexible_server.db.name
  value               = "ON"
}

# The VM's public IP, and nothing else, unless mysql_allowed_client_cidrs adds more.
resource "azurerm_mysql_flexible_server_firewall_rule" "vm" {
  name                = "allow-vm"
  resource_group_name = azurerm_resource_group.rg.name
  server_name         = azurerm_mysql_flexible_server.db.name
  start_ip_address    = azurerm_public_ip.vm.ip_address
  end_ip_address      = azurerm_public_ip.vm.ip_address
}

resource "azurerm_mysql_flexible_server_firewall_rule" "extra" {
  for_each = var.mysql_allowed_client_cidrs

  name                = each.key
  resource_group_name = azurerm_resource_group.rg.name
  server_name         = azurerm_mysql_flexible_server.db.name
  start_ip_address    = each.value.start
  end_ip_address      = each.value.end
}

# ─────────────────────────────────────────────────────────────────────────────
# The virtual machine
# ─────────────────────────────────────────────────────────────────────────────

resource "azurerm_linux_virtual_machine" "vm" {
  name                = local.vm_name
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location
  size                = var.vm_size
  admin_username      = var.admin_username
  tags                = var.tags

  network_interface_ids = [azurerm_network_interface.vm.id]

  # Key only. There is no password on this account at all.
  disable_password_authentication = true

  admin_ssh_key {
    username   = var.admin_username
    public_key = file(pathexpand(var.ssh_public_key_path))
  }

  os_disk {
    caching              = "ReadWrite"
    storage_account_type = var.os_disk_type
    disk_size_gb         = var.os_disk_size_gb
  }

  source_image_reference {
    publisher = "Canonical"
    offer     = "ubuntu-24_04-lts"
    sku       = "server"
    version   = "latest"
  }

  # Serial console and boot screenshots, on Azure-managed storage. Worth having
  # the first time the box does not come back from a reboot.
  boot_diagnostics {}

  custom_data = base64encode(templatefile("${path.module}/cloud-init.yaml.tftpl", {
    admin_username = var.admin_username
    swap_size_gb   = var.swap_size_gb
    repo_url       = var.repo_url
    repo_branch    = var.repo_branch
    load_schema    = var.load_schema
    compose_args   = join(" ", [for p in var.compose_profiles : "--profile ${p}"])

    site_address   = local.site_address
    mysql_host     = azurerm_mysql_flexible_server.db.fqdn
    mysql_url      = local.mysql_url
    mysql_user     = var.mysql_admin_username
    mysql_password = local.mysql_admin
    mysql_database = var.mysql_database_name

    ddl_auto             = var.ddl_auto
    app_secret_key       = local.app_secret_key
    youtube_api_key      = var.youtube_api_key
    youtube_max_comments = var.youtube_max_comments_per_video
    llm_base_url         = var.llm_base_url
    llm_api_key          = var.llm_api_key
    llm_model            = var.llm_model
    llm_timeout_seconds  = var.llm_timeout_seconds
    llm_max_prompt_chars = var.llm_max_prompt_chars
    web_search_provider  = var.web_search_provider
    web_search_base_url  = var.web_search_base_url
    web_search_fallback  = var.web_search_fallback
    searxng_secret       = local.searxng_secret
    auth_token_ttl_days  = var.auth_token_ttl_days
    smtp_host            = var.smtp_host
    smtp_port            = var.smtp_port
    smtp_username        = var.smtp_username
    smtp_password        = var.smtp_password
    notify_from          = var.notify_from
    notify_cron          = var.notify_cron
  }))

  # cloud-init runs once, on first boot. Without this, editing any application
  # variable would propose destroying the VM — losing the Docker images, the
  # Caddy certificate and any local state — to deliver a changed .env.
  #
  # So: change application settings by editing /opt/yci/.env on the box and
  # re-running `docker compose ... up -d`. To genuinely rebuild the machine from
  # this template, ask for it explicitly:
  #
  #     terraform apply -replace=azurerm_linux_virtual_machine.vm
  lifecycle {
    ignore_changes = [custom_data]
  }

  depends_on = [
    azurerm_mysql_flexible_database.app,
    azurerm_mysql_flexible_server_firewall_rule.vm,
    azurerm_mysql_flexible_server_configuration.require_tls,
    azurerm_subnet_network_security_group_association.app,
  ]
}

# ─────────────────────────────────────────────────────────────────────────────
# Budget alert
#
# The free tiers expire after 12 months while the resources keep running, and a
# Standard public IPv4 address bills from day one. Set budget_contact_emails.
# ─────────────────────────────────────────────────────────────────────────────

resource "azurerm_consumption_budget_resource_group" "monthly" {
  count = length(var.budget_contact_emails) > 0 ? 1 : 0

  name              = "${var.prefix}-monthly"
  resource_group_id = azurerm_resource_group.rg.id
  amount            = var.budget_amount
  time_grain        = "Monthly"

  time_period {
    start_date = formatdate("YYYY-MM-01'T'00:00:00'Z'", timestamp())
    end_date   = formatdate("YYYY-MM-01'T'00:00:00'Z'", timeadd(timestamp(), "87600h"))
  }

  # Actual spend, once it has happened.
  notification {
    enabled        = true
    threshold      = 80
    operator       = "GreaterThan"
    threshold_type = "Actual"
    contact_emails = var.budget_contact_emails
  }

  # Forecast, which is the one that warns you *before* the month ends.
  notification {
    enabled        = true
    threshold      = 100
    operator       = "GreaterThan"
    threshold_type = "Forecasted"
    contact_emails = var.budget_contact_emails
  }

  lifecycle {
    # timestamp() is re-evaluated on every plan; without this the budget window
    # would show as drift forever.
    ignore_changes = [time_period]
  }
}
