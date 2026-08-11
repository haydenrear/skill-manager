terraform {
  required_version = ">= 1.6.0"
}

locals {
  environment_id = var.environment_id
  branch         = var.branch
  kube_context   = "test-graph-${var.branch}"
  kubeconfig     = "${path.module}/generated/kubeconfig"
}
