/**
 * Originex Dev Environment Composition
 * Wires together all infrastructure modules for the development environment.
 */

terraform {
  required_version = ">= 1.7.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.70"
    }
  }

  backend "s3" {
    bucket         = "originex-terraform-state"
    key            = "dev/terraform.tfstate"
    region         = "ap-south-1"
    dynamodb_table = "originex-terraform-locks"
    encrypt        = true
  }
}

provider "aws" {
  region = "ap-south-1"

  default_tags {
    tags = {
      Project     = "originex"
      Environment = "dev"
      ManagedBy   = "terraform"
    }
  }
}

locals {
  project     = "originex"
  environment = "dev"
  region      = "ap-south-1"
}

# ─── VPC ───
module "vpc" {
  source      = "../modules/vpc"
  project     = local.project
  environment = local.environment
  region      = local.region
  vpc_cidr    = "10.0.0.0/16"
}

# ─── EKS ───
module "eks" {
  source             = "../modules/eks"
  project            = local.project
  environment        = local.environment
  vpc_id             = module.vpc.vpc_id
  private_subnet_ids = module.vpc.private_subnet_ids
  cluster_version    = "1.30"
}

# ─── RDS (per-service databases) ───
module "rds_lms" {
  source          = "../modules/rds"
  project         = local.project
  environment     = local.environment
  vpc_id          = module.vpc.vpc_id
  data_subnet_ids = module.vpc.data_subnet_ids
  database_name   = "lms"
  instance_class  = "db.r6g.large"  # Smaller for dev
}

module "rds_los" {
  source          = "../modules/rds"
  project         = local.project
  environment     = local.environment
  vpc_id          = module.vpc.vpc_id
  data_subnet_ids = module.vpc.data_subnet_ids
  database_name   = "los"
  instance_class  = "db.r6g.large"
}

module "rds_ledger" {
  source          = "../modules/rds"
  project         = local.project
  environment     = local.environment
  vpc_id          = module.vpc.vpc_id
  data_subnet_ids = module.vpc.data_subnet_ids
  database_name   = "ledger"
  instance_class  = "db.r6g.large"
}

# ─── Redis ───
module "redis" {
  source             = "../modules/redis"
  project            = local.project
  environment        = local.environment
  vpc_id             = module.vpc.vpc_id
  data_subnet_ids    = module.vpc.data_subnet_ids
  node_type          = "cache.r6g.large"
  num_cache_clusters = 2  # Smaller for dev
}

# ─── Outputs ───
output "eks_cluster_name" {
  value = module.eks.cluster_name
}

output "rds_lms_endpoint" {
  value = module.rds_lms.endpoint
}

output "redis_endpoint" {
  value = module.redis.primary_endpoint
}
