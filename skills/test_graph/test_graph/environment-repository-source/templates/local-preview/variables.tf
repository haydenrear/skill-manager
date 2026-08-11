variable "environment_id" {
  description = "Branch-scoped test graph environment id."
  type        = string
}

variable "branch" {
  description = "Feature branch that owns this preview environment."
  type        = string
}
