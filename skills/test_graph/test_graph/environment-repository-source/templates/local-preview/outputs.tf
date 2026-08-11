output "EnvironmentId" {
  description = "Stable branch environment identifier."
  value       = local.environment_id
}

output "KUBECONFIG" {
  description = "Kubeconfig path for downstream Kubernetes and Helm nodes."
  value       = local.kubeconfig
}

output "KUBECONTEXT" {
  description = "Kubernetes context name for downstream Kubernetes and Helm nodes."
  value       = local.kube_context
}
