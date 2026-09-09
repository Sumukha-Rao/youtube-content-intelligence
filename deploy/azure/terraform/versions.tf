# Provider and version pins.
#
# azurerm 4.x requires an explicit subscription id — there is no implicit "whatever
# `az account show` says" any more — so it is a variable rather than ambient state.

terraform {
  required_version = ">= 1.6.0"

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 4.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # State lives on disk by default, which is fine for one operator. For a team,
  # uncomment and point at a storage account you created beforehand — the state
  # file holds the database password in clear text, so it belongs somewhere with
  # access control and versioning.
  #
  # backend "azurerm" {
  #   resource_group_name  = "tfstate-rg"
  #   storage_account_name = "yciTfState"
  #   container_name       = "tfstate"
  #   key                  = "yci.tfstate"
  # }
}

provider "azurerm" {
  subscription_id = var.subscription_id

  features {
    resource_group {
      # Refuse to delete a resource group that still holds resources Terraform
      # does not know about, rather than taking them with it.
      prevent_deletion_if_contains_resources = true
    }
  }
}
