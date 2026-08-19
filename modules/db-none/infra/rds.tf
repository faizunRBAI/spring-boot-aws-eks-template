# database = none
#
# This overlay replaces infra/rds.tf, so the stack provisions no RDS instance,
# no database security group and no `database_url` output. The configure stage
# finds no connection string, skips creating the app-database Secret, and the
# service runs statelessly — its readiness probe reports "not configured"
# instead of checking a database.
#
# Bring your own datastore by adding its resources here, or switch the
# `database` module choice back to `postgres`.
