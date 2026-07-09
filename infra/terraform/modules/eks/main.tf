/**
 * Originex EKS Module
 * Creates a production-grade EKS cluster with managed node groups.
 */

variable "project" {
  type    = string
  default = "originex"
}

variable "environment" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "cluster_version" {
  type    = string
  default = "1.30"
}

# ─── EKS Cluster ───
resource "aws_eks_cluster" "main" {
  name     = "${var.project}-${var.environment}"
  version  = var.cluster_version
  role_arn = aws_iam_role.cluster.arn

  vpc_config {
    subnet_ids              = var.private_subnet_ids
    endpoint_private_access = true
    endpoint_public_access  = false
    security_group_ids      = [aws_security_group.cluster.id]
  }

  encryption_config {
    provider {
      key_arn = aws_kms_key.eks.arn
    }
    resources = ["secrets"]
  }

  enabled_cluster_log_types = ["api", "audit", "authenticator", "controllerManager", "scheduler"]

  tags = {
    Name        = "${var.project}-${var.environment}-eks"
    Project     = var.project
    Environment = var.environment
    ManagedBy   = "terraform"
  }

  depends_on = [
    aws_iam_role_policy_attachment.cluster_policy,
    aws_iam_role_policy_attachment.vpc_resource_controller,
  ]
}

# ─── Core Services Node Group ───
resource "aws_eks_node_group" "core_services" {
  cluster_name    = aws_eks_cluster.main.name
  node_group_name = "${var.project}-core-services"
  node_role_arn   = aws_iam_role.node.arn
  subnet_ids      = var.private_subnet_ids
  instance_types  = ["m6i.2xlarge"]

  scaling_config {
    desired_size = 3
    min_size     = 3
    max_size     = 12
  }

  update_config {
    max_unavailable = 1
  }

  labels = {
    tier    = "core"
    project = var.project
  }

  tags = {
    Name = "${var.project}-${var.environment}-core-nodes"
  }

  depends_on = [
    aws_iam_role_policy_attachment.node_policy,
    aws_iam_role_policy_attachment.cni_policy,
    aws_iam_role_policy_attachment.ecr_policy,
  ]
}

# ─── Data-Intensive Node Group (Kafka, Flink) ───
resource "aws_eks_node_group" "data_intensive" {
  cluster_name    = aws_eks_cluster.main.name
  node_group_name = "${var.project}-data-intensive"
  node_role_arn   = aws_iam_role.node.arn
  subnet_ids      = var.private_subnet_ids
  instance_types  = ["r6i.2xlarge"]

  scaling_config {
    desired_size = 3
    min_size     = 3
    max_size     = 9
  }

  labels = {
    tier    = "data"
    project = var.project
  }

  taint {
    key    = "dedicated"
    value  = "data"
    effect = "NO_SCHEDULE"
  }

  tags = {
    Name = "${var.project}-${var.environment}-data-nodes"
  }

  depends_on = [
    aws_iam_role_policy_attachment.node_policy,
    aws_iam_role_policy_attachment.cni_policy,
    aws_iam_role_policy_attachment.ecr_policy,
  ]
}

# ─── KMS Key for EKS Secrets Encryption ───
resource "aws_kms_key" "eks" {
  description             = "EKS secrets encryption key for ${var.project}-${var.environment}"
  deletion_window_in_days = 7
  enable_key_rotation     = true

  tags = {
    Name = "${var.project}-${var.environment}-eks-key"
  }
}

# ─── IAM Roles ───
resource "aws_iam_role" "cluster" {
  name = "${var.project}-${var.environment}-eks-cluster"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action = "sts:AssumeRole"
      Effect = "Allow"
      Principal = {
        Service = "eks.amazonaws.com"
      }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "cluster_policy" {
  role       = aws_iam_role.cluster.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSClusterPolicy"
}

resource "aws_iam_role_policy_attachment" "vpc_resource_controller" {
  role       = aws_iam_role.cluster.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSVPCResourceController"
}

resource "aws_iam_role" "node" {
  name = "${var.project}-${var.environment}-eks-node"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action = "sts:AssumeRole"
      Effect = "Allow"
      Principal = {
        Service = "ec2.amazonaws.com"
      }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "node_policy" {
  role       = aws_iam_role.node.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy"
}

resource "aws_iam_role_policy_attachment" "cni_policy" {
  role       = aws_iam_role.node.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy"
}

resource "aws_iam_role_policy_attachment" "ecr_policy" {
  role       = aws_iam_role.node.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly"
}

# ─── Security Group ───
resource "aws_security_group" "cluster" {
  name_prefix = "${var.project}-${var.environment}-eks-"
  vpc_id      = var.vpc_id
  description = "EKS cluster security group"

  tags = {
    Name = "${var.project}-${var.environment}-eks-sg"
  }
}

# ─── Outputs ───
output "cluster_name" {
  value = aws_eks_cluster.main.name
}

output "cluster_endpoint" {
  value = aws_eks_cluster.main.endpoint
}

output "cluster_ca_certificate" {
  value = aws_eks_cluster.main.certificate_authority[0].data
}

output "node_role_arn" {
  value = aws_iam_role.node.arn
}
