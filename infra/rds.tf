# Managed PostgreSQL (Amazon RDS).
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
  description = "Postgres access for the EKS workloads of ${local.name}"
  vpc_id      = aws_vpc.this.id

  ingress {
    description = "Postgres from the cluster node security group"
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

resource "aws_db_instance" "this" {
  identifier = "${local.name}-db"

  engine = "postgres"
  # Major version only, so AWS selects the current minor and patches it during
  # the maintenance window.
  engine_version             = "16"
  auto_minor_version_upgrade = true

  instance_class        = var.db_instance_class
  allocated_storage     = var.db_allocated_storage
  max_allocated_storage = var.db_allocated_storage * 4
  storage_type          = "gp3"
  storage_encrypted     = true

  db_name  = "appdb"
  username = "appuser"
  password = random_password.db.result
  port     = 5432

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.db.id]
  publicly_accessible    = false
  multi_az               = false

  backup_retention_period = 7
  backup_window           = "02:00-03:00"
  maintenance_window      = "sun:03:30-sun:04:30"
  copy_tags_to_snapshot   = true

  # A blueprint deploy must be reversible: teardown should not stop on a
  # deletion guard or wait for a final snapshot. Turn both around for a
  # long-lived production database.
  deletion_protection = false
  skip_final_snapshot = true
  apply_immediately   = true

  tags = {
    Name = "${local.name}-db"
  }
}

output "database_jdbc_url" {
  description = "JDBC URL, read by the configure stage into a Kubernetes Secret."
  value       = "jdbc:postgresql://${aws_db_instance.this.address}:${aws_db_instance.this.port}/appdb?sslmode=verify-full"
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
  value       = aws_db_instance.this.endpoint
}
