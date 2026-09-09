output "site_url" {
  description = "The deployed application. Caddy serves the frontend and /api on this one origin."
  value       = "https://${local.site_address}"
}

output "site_address" {
  description = "FQDN of the VM — also the certificate subject Caddy requests from Let's Encrypt."
  value       = local.site_address
}

output "vm_public_ip" {
  description = "Static public IP. It is also the single address allowed through the database firewall."
  value       = azurerm_public_ip.vm.ip_address
}

output "ssh_command" {
  description = "Shell into the VM."
  value       = "ssh ${var.admin_username}@${local.site_address}"
}

output "bootstrap_log_command" {
  description = "Follow the first-boot build, which takes 15-30 minutes on a B-series VM."
  value       = "ssh ${var.admin_username}@${local.site_address} 'sudo tail -f /var/log/yci-bootstrap.log'"
}

output "health_check_command" {
  description = "Expect {\"emailConfigured\":false,\"status\":\"UP\"} once the stack is up."
  value       = "curl -s https://${local.site_address}/api/health"
}

output "mysql_fqdn" {
  description = "Managed MySQL hostname."
  value       = azurerm_mysql_flexible_server.db.fqdn
}

output "mysql_admin_username" {
  description = "Database administrator login."
  value       = var.mysql_admin_username
}

output "mysql_admin_password" {
  description = "Database password. Read it with: terraform output -raw mysql_admin_password"
  value       = local.mysql_admin
  sensitive   = true
}

output "mysql_jdbc_url" {
  description = "The JDBC URL written into the VM's .env."
  value       = local.mysql_url
}

output "app_secret_key" {
  description = <<-EOT
    The key encrypting users' saved API keys. Keep it: changing it makes every
    stored key unreadable. `terraform output -raw app_secret_key`
  EOT
  value       = local.app_secret_key
  sensitive   = true
}

output "mysql_client_command" {
  description = "Connect from a machine you have allowed through mysql_allowed_client_cidrs."
  value       = "mysql -h ${azurerm_mysql_flexible_server.db.fqdn} -u ${var.mysql_admin_username} -p --ssl-mode=REQUIRED ${var.mysql_database_name}"
}
