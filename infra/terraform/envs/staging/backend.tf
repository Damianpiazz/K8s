# ---------------------------------------------------------------------------
# STAGING remote state backend (Azure Storage).
#
# TODO(student): BEFORE running `terraform init`, create the storage account
# and container that will hold the state, then either:
#
#   Option A — inline in this file:
#     storage_account_name = "tfecommerce<something>"
#     container_name       = "tfstate"
#     key                  = "staging/ecommerce.terraform.tfstate"
#     access_key            = "<storage-account-access-key>"
#
#   Option B (recommended) — keep this file empty and pass values at init:
#     terraform init \
#       -backend-config="storage_account_name=tfecommerce01" \
#       -backend-config="container_name=tfstate" \
#       -backend-config="key=staging/ecommerce.terraform.tfstate" \
#       -backend-config="access_key=<storage-account-access-key>"
#
# A `backend.tfvars.example` is provided next to this file.
# ---------------------------------------------------------------------------
terraform {
  backend "azurerm" {
    # storage_account_name = ""
    # container_name       = ""
    # key                  = "staging/ecommerce.terraform.tfstate"
    # access_key           = ""
  }
}