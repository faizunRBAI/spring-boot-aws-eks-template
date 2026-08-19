# Amazon Aurora PostgreSQL (Serverless v2).
#
# This whole file is what the `database` module choice replaces, so every
# database-specific resource AND every database output must live here and
# nowhere else. That is what lets `database=none` work by shipping a file with
# no resources at all.

resource "random_password" "db" {
  length = 32
  # Alphanumeric only: the password travels inside a JDBC URL, and
  # percent-encoding round-trips are a documented source of connection bugs.
  special = false
}

resource "aws_db_subnet_group" "this" {
  name       = "${local.name}-db"
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_security_group" "db" {
  name = "${local.name}-db"
  # AWS restricts security group and rule descriptions to a limited character
  # set. Anything outside it is rejected at apply time, NOT by
  # `terraform validate`. Keep these strings plain.
  description = "Aurora PostgreSQL access for the EKS workloads of ${local.name}"
  vpc_id      = aws_vpc.this.id

  ingress {
    description = "Aurora PostgreSQL from the cluster node security group"
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    # The security group EKS creates and attaches to every managed node — the
    # reliable way to say "the cluster" without hardcoding CIDRs.
    security_groups = [aws_eks_cluster.this.vpc_config[0].cluster_security_group_id]
  }

  # No egress rules: the database never initiates outbound connections.

  tags = {
    Name = "${local.name}-db"
  }
}

resource "aws_rds_cluster" "this" {
  cluster_identifier = "${local.name}-db"

  engine         = "aurora-postgresql"
  engine_version = "16.6"
  engine_mode    = "provisioned"
  port           = 5432

  database_name   = "appdb"
  master_username = "appuser"
  master_password = random_password.db.result

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.db.id]
  storage_encrypted      = true

  backup_retention_period      = 7
  preferred_backup_window      = "02:00-03:00"
  preferred_maintenance_window = "sun:03:30-sun:04:30"
  copy_tags_to_snapshot        = true

  deletion_protection = false
  skip_final_snapshot = true
  apply_immediately   = true

  # Serverless v2 scales the writer with load and costs little when idle, which
  # suits a blueprint better than guessing an instance size.
  serverlessv2_scaling_configuration {
    min_capacity = 0.5
    max_capacity = 4.0
  }

  tags = {
    Name = "${local.name}-db"
  }
}

resource "aws_rds_cluster_instance" "writer" {
  identifier         = "${local.name}-db-1"
  cluster_identifier = aws_rds_cluster.this.id
  instance_class     = "db.serverless"
  engine             = aws_rds_cluster.this.engine
  engine_version     = aws_rds_cluster.this.engine_version

  publicly_accessible        = false
  auto_minor_version_upgrade = true

  tags = {
    Name = "${local.name}-db-1"
  }
}

output "database_jdbc_url" {
  description = "JDBC URL, read by the configure stage into a Kubernetes Secret."
  value       = "jdbc:postgresql://${aws_rds_cluster.this.endpoint}:${aws_rds_cluster.this.port}/appdb?sslmode=verify-full"
}

output "database_username" {
  description = "Database user the application connects as."
  value       = "appuser"
}

output "database_password" {
  description = "Generated database password."
  value       = random_password.db.result
  sensitive   = true
}

output "database_endpoint" {
  description = "Host and port of the database."
  value       = aws_rds_cluster.this.endpoint
}
