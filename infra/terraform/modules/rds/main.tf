/**
 * Originex RDS Module
 * Creates a Multi-AZ PostgreSQL 16 instance with encryption and automated backups.
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

variable "data_subnet_ids" {
  type = list(string)
}

variable "instance_class" {
  type    = string
  default = "db.r6g.xlarge"
}

variable "database_name" {
  type = string
}

variable "allocated_storage" {
  type    = number
  default = 100
}

variable "max_allocated_storage" {
  type    = number
  default = 1000
}

# ─── Subnet Group ───
resource "aws_db_subnet_group" "main" {
  name       = "${var.project}-${var.environment}-${var.database_name}"
  subnet_ids = var.data_subnet_ids

  tags = {
    Name = "${var.project}-${var.environment}-${var.database_name}-subnet-group"
  }
}

# ─── Security Group ───
resource "aws_security_group" "rds" {
  name_prefix = "${var.project}-${var.environment}-rds-"
  vpc_id      = var.vpc_id
  description = "RDS PostgreSQL security group"

  ingress {
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    description = "PostgreSQL from private subnets"
    cidr_blocks = ["10.0.0.0/16"]  # VPC CIDR
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.project}-${var.environment}-rds-sg"
  }
}

# ─── KMS Key for Encryption ───
resource "aws_kms_key" "rds" {
  description             = "RDS encryption key for ${var.project}-${var.environment}-${var.database_name}"
  deletion_window_in_days = 30
  enable_key_rotation     = true

  tags = {
    Name = "${var.project}-${var.environment}-rds-key"
  }
}

# ─── Parameter Group (PostgreSQL 16 tuned for lending workloads) ───
resource "aws_db_parameter_group" "postgres16" {
  family = "postgres16"
  name   = "${var.project}-${var.environment}-${var.database_name}-pg16"

  parameter {
    name  = "shared_preload_libraries"
    value = "pg_stat_statements,pgaudit"
  }

  parameter {
    name  = "log_statement"
    value = "ddl"
  }

  parameter {
    name  = "log_min_duration_statement"
    value = "1000"  # Log queries > 1 second
  }

  parameter {
    name  = "idle_in_transaction_session_timeout"
    value = "60000"  # Kill idle-in-transaction after 60s
  }

  parameter {
    name  = "statement_timeout"
    value = "30000"  # 30s query timeout
  }

  tags = {
    Name = "${var.project}-${var.environment}-pg16-params"
  }
}

# ─── RDS Instance ───
resource "aws_db_instance" "main" {
  identifier     = "${var.project}-${var.environment}-${var.database_name}"
  engine         = "postgres"
  engine_version = "16.4"
  instance_class = var.instance_class

  allocated_storage     = var.allocated_storage
  max_allocated_storage = var.max_allocated_storage
  storage_type          = "gp3"
  storage_encrypted     = true
  kms_key_id            = aws_kms_key.rds.arn

  db_name  = replace(var.database_name, "-", "_")
  username = "originex_admin"
  manage_master_user_password = true  # AWS Secrets Manager managed

  multi_az               = true
  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  parameter_group_name   = aws_db_parameter_group.postgres16.name
  publicly_accessible    = false

  backup_retention_period = 35
  backup_window           = "03:00-04:00"
  maintenance_window      = "sun:04:30-sun:05:30"
  copy_tags_to_snapshot   = true

  deletion_protection       = true
  skip_final_snapshot       = false
  final_snapshot_identifier = "${var.project}-${var.environment}-${var.database_name}-final"

  performance_insights_enabled          = true
  performance_insights_retention_period = 7

  monitoring_interval = 60
  monitoring_role_arn = aws_iam_role.rds_monitoring.arn

  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]

  tags = {
    Name        = "${var.project}-${var.environment}-${var.database_name}"
    Project     = var.project
    Environment = var.environment
  }
}

# ─── Enhanced Monitoring IAM Role ───
resource "aws_iam_role" "rds_monitoring" {
  name = "${var.project}-${var.environment}-rds-monitoring"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action = "sts:AssumeRole"
      Effect = "Allow"
      Principal = {
        Service = "monitoring.rds.amazonaws.com"
      }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "rds_monitoring" {
  role       = aws_iam_role.rds_monitoring.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
}

# ─── Outputs ───
output "endpoint" {
  value = aws_db_instance.main.endpoint
}

output "address" {
  value = aws_db_instance.main.address
}

output "port" {
  value = aws_db_instance.main.port
}

output "database_name" {
  value = aws_db_instance.main.db_name
}
