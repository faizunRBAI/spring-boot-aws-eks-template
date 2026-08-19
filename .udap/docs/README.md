# Spring Boot API on Amazon EKS

A production platform for Spring Boot services on managed Kubernetes, against
whichever database your organisation already standardised on. You get the
cluster, the network, the container registry, the database, the delivery pipeline
and the security gates as one working system in your own AWS account — and you
write the application code.

## What problem this solves

Getting one Spring Boot jar onto EKS is a weekend. Getting it there *safely*,
repeatably, against Oracle or Aurora rather than whatever the tutorial used, and
in a way a second engineer can operate, is several weeks: node groups and IAM
roles, subnet tagging so the load balancer can be placed, secret encryption,
probes that survive a slow JVM start, migrations that run before the new pods
serve, trust stores so the database connection is verified rather than merely
encrypted, a pipeline that refuses to ship vulnerable images, and a teardown that
does not leave orphaned load balancers holding your VPC hostage.

That work is done here, and it has been through the same validators the platform
runs against everything it builds.

## Who it is for

- Enterprise APIs on the JVM.
- Teams whose database is already decided — and is not necessarily Postgres.
- Spring Boot services moving off application servers or single-VM hosting that
  want CI/CD and security gates from day one rather than bolted on later.

## The two choices you make at creation time

**Build tool** — `maven` (default) or `gradle`. Neither is in the base tree;
both are module overlays. That is deliberate: an overlay can add a file but never
remove one, so shipping `pom.xml` in the base would leave a stale, unused, still
scanned pom behind in every Gradle project. Every pipeline stage and the
Dockerfile detect which one landed.

Neither wrapper (`mvnw`, `gradlew`) is shipped, because both need a committed
`.jar` and template repositories are binary-free. The build tool comes from a
pinned container image or a pinned distribution URL instead, which is at least as
reproducible.

**Database** — `postgres` (default), `mysql`, `mariadb`, `oracle`,
`aurora-postgresql`, `aurora-mysql`, or `none`.

| Choice | What Terraform builds | Port | Notes |
| --- | --- | --- | --- |
| `postgres` | RDS PostgreSQL 16 | 5432 | The default; cheapest and best exercised |
| `mysql` | RDS MySQL 8.0 | 3306 | |
| `mariadb` | RDS MariaDB 11.4 | 3306 | |
| `oracle` | RDS Oracle SE2 19c, licence included, SSL option group | 2484 | Several times the cost of the others; needs `db.t3.small` or larger |
| `aurora-postgresql` | Aurora PostgreSQL Serverless v2 cluster, one writer | 5432 | Scales 0.5–4 ACU |
| `aurora-mysql` | Aurora MySQL Serverless v2 cluster, one writer | 3306 | Scales 0.5–4 ACU |
| `none` | nothing | — | The service runs statelessly |

Each choice replaces `infra/rds.tf` wholesale, which is why every database
resource *and* every database output lives in that one file — it is what lets
`none` work by shipping a file with no resources at all.

**One build serves all of them.** The five JDBC drivers ship in the jar (about
12 MB) and Spring selects one from the URL, because the module choice rewrites
Terraform and cannot reach into your `pom.xml`. Delete the drivers you do not use
once your database is settled.

## What you inherit

**Platform**

- EKS control plane with API, audit, authenticator, controller-manager and
  scheduler logs streaming to CloudWatch.
- Managed node group across two availability zones, drained one node at a time
  during upgrades.
- VPC with public subnets for the nodes and load balancer, private subnets for the
  database, and no NAT gateway to pay for.
- Kubernetes Secrets encrypted with a KMS key created for this project.
- ECR repository with scan-on-push and a lifecycle policy that keeps the last
  twenty images.

**Delivery**

- Thirteen-stage GitHub Actions pipeline rendered from `.udap/pipeline.yaml`.
- Rolling updates with `maxUnavailable: 0`, readiness-gated traffic, and Spring's
  graceful shutdown draining in-flight requests so a release never drops one.
- **A startup probe ahead of liveness.** A JVM takes longer to come up than a
  Node or Python process, and without this the liveness probe kills the pod
  mid-boot and the deploy never converges.
- Horizontal Pod Autoscaler on CPU, with the metrics server installed by the
  deploy.
- PodDisruptionBudget so node drains cannot take the last pod.
- Teardown that deletes the Kubernetes load balancer before running
  `terraform destroy`.

**Security and compliance, enforced in the pipeline**

| Gate | Tool | Blocks the deploy when |
| --- | --- | --- |
| Coding standards | Checkstyle | any violation, warnings included |
| Unit tests | JUnit 5 | any test fails |
| SAST | Semgrep (`p/default`, `p/owasp-top-ten`, `p/java`) | an ERROR-severity finding |
| Secret scanning | Gitleaks | a credential is committed |
| Licence compliance | CycloneDX allowlist | a dependency carries a licence nobody has approved |
| SBOM | CycloneDX | generation fails |
| IaC security | Trivy config, Checkov | Trivy reports a CRITICAL misconfiguration |
| Image security | Trivy | a fixable HIGH or CRITICAL vulnerability in the image |

The scanners are wired to **report first, then fail**: each writes its report,
uploads it, and only then does a separate step decide whether to block, printing
every finding with its file, line and rule id. A gate that dies before it can tell
you what it found is worse than no gate at all.

### Checkstyle, but not google_checks

`config/checkstyle/checkstyle.xml` is a deliberately small rule set: real defects
(`EqualsHashCode`, `StringLiteralEquality`, `FallThrough`, `UnusedImports`,
`NeedBraces`) and naming consistency. `google_checks` and `sun_checks` demand
javadoc on every member and a house indentation style, which means a gate that
fails on perfectly correct code and gets switched off within a week. This one can
stay blocking and stay respected.

### The licence allowlist is reviewed, not permissive-only

A stock Spring Boot application cannot satisfy a permissive-only allowlist and
never could. The gate reads the CycloneDX document — the same one the SBOM stage
publishes, so the two can never disagree — and every non-permissive entry carries
its reason:

| Licence | Arrives with | Why it is allowed |
| --- | --- | --- |
| EPL-1.0 / LGPL | logback, the default logging backend | Weak copyleft; obligations attach to modifying the library |
| EPL-2.0 / GPL-2.0-with-classpath-exception | jakarta.annotation-api | The classpath exception exists precisely so linking does not propagate the GPL |
| LGPL-2.1 | MariaDB Connector/J, JNA | Unmodified library use |
| GPL-2.0 + Universal FOSS Exception | MySQL Connector/J | See the caveat below |
| Oracle FUTC | ojdbc11 | Free to use with Oracle Database |

A component may declare several licences; **one** allowed licence is enough,
which is the correct reading of a dual licence.

**The MySQL Connector/J caveat.** Running it inside a service you operate is not
distribution, so the GPL's distribution obligations do not attach. If you ever
ship the artefact to someone else, that analysis changes and the FOSS Exception
may not cover a proprietary application — MariaDB Connector/J is LGPL, speaks the
MySQL protocol, and is the usual way out. This is a decision for your legal
counsel, not for a pipeline; the gate's job is to make sure nobody makes it by
accident.

## Database migrations

The blueprint owns **when** migrations run. Your project owns **how**.

What the blueprint provides, and expects to keep:

- The `db-migrate` Kubernetes Job, running on the image just built, in the
  `configure` stage, **before** the new Deployment is applied — so a failed
  migration stops the deploy instead of half-releasing it. The step always prints
  the migration log, on success and on failure.
- `bin/migrate` as the seam. The Job runs `sh bin/migrate` and nothing else.
- Skipping cleanly when there is no database.

What is yours:

- **The schema.** `src/main/resources/db/migration/{vendor}/` holds the DDL, and
  `{vendor}` resolves to `postgresql`, `mysql`, `mariadb` or `oracle` — so one
  build carries the right DDL for whichever module was chosen, and Aurora reuses
  its engine's directory. Add `V2__…sql` and up.
- **The tool.** The default runs the application jar under the `migrate` profile,
  which turns Flyway on, disables the web server and exits when the context
  closes. Point `bin/migrate` at Liquibase or anything else if you prefer.

**Flyway is off in the running application on purpose.** Spring would otherwise
run it at startup in every replica, which races, and turns a bad migration into a
crash-loop instead of a stopped deploy.

One rule the rolling update imposes: releases run old and new pods at the same
time (`maxUnavailable: 0`), so **every migration must be backwards compatible with
the currently running code**. Expand, then contract.

## The landing page

`/` serves the UDAP welcome page — the same branded page every project the
platform builds shows on its first deploy. The status line and the release tile
are read live from `/health`, so the page cannot claim the service is online while
it is not. Replace it with your own UI when you have one; it is a single static
file, named in the page's own footer.

## Endpoints

`/health` and `/ready` are plain controllers rather than Actuator paths, because
the platform's health check, the Kubernetes probes and the welcome page all
address those two. Actuator is still enabled at `/actuator` for real operations.

The split is the important part: **`/health` never touches the database** — a
database outage must not make Kubernetes restart pods that are serving fine —
while `/ready` does, so a pod that cannot reach its database stops receiving
traffic without being killed.

## Database connections are verified, not just encrypted

RDS presents certificates from a private Amazon CA that no system or JVM trust
store carries. The Dockerfile imports the RDS certificate authorities into the
JVM's own trust store, once, at build time. That is what lets every driver verify
the server with nothing but its ordinary `sslmode` setting — the alternative is
per-driver trust plumbing (PostgreSQL reads a PEM, MySQL and Oracle want a
keystore, MariaDB wants another flag), which is four ways to get it subtly wrong.

Each database module emits the JDBC URL with its own engine's verification
parameters, because that is where engine-specific knowledge belongs.

## What you are expected to write

- Controllers, services and persistence under `src/main/java` — keeping `/health`
  and `/ready`.
- The schema, in `src/main/resources/db/migration/{vendor}/`.
- Application configuration and Kubernetes config.
- Tests under `src/test/java`.

Note the blueprint ships `spring-boot-starter-jdbc`, not `-data-jpa`: there are no
entities yet, Flyway owns the schema, and an unused ORM is startup cost plus a
second opinion about your DDL. Add JPA when you add entities.

## What not to rewrite

These files are the blueprint. Each one encodes a deploy failure that has already
been diagnosed and fixed, so a fresh version of it tends to reintroduce the
original problem:

| File | Rewriting it costs you |
| --- | --- |
| `Dockerfile` | the dual build-tool detection, `apk upgrade`, and the RDS trust-store import — without the last one every database connection falls back to unverified TLS |
| `.dockerignore` | the exclusion list the build context relies on |
| `infra/*.tf` | security groups with no `egress 0.0.0.0/0` rule, plain-text AWS descriptions (a character outside AWS's allowed set is rejected at apply time and invisible to `terraform validate`), the provider pin, and the empty S3 backend the platform's state contract requires |
| `.udap/pipeline.yaml` | the apt Trivy install, report-then-fail scanners, the reviewed licence allowlist, and poll-based waits that print the real error |
| `.trivyignore` | every accepted finding and the reason it is accepted |
| `k8s/deployment.yaml` | the startup probe that lets a JVM boot without being killed |
| `k8s/db-migrate-job.yaml` and the migration step | migrations running before the Deployment, and a step that reports the real failure instead of a wait timeout |

Extending them is fine and expected. Replacing them wholesale is what turns a
verified blueprint back into a first draft.

## Cost

Roughly £140–£230 a month for the defaults: the EKS control plane is about £58
regardless of load, two `t3.medium` nodes, a `db.t4g.micro` Postgres instance with
20 GB of gp3 storage, and one network load balancer.

**The database choice moves this more than anything else.** `none` removes about
£18; Aurora Serverless v2 adds roughly £25 at its 0.5 ACU floor; **Oracle SE2 with
an included licence adds roughly £90** and is billed per hour whether or not you
use it. Pick Oracle because you need Oracle.

## Deliberate trade-offs

- **Nodes run in public subnets.** No NAT gateway to pay for or fail; inbound
  access is still restricted to the cluster security group.
- **The Kubernetes API endpoint is public.** CI runners have no fixed egress
  address. Access is IAM-authenticated and audit-logged; recorded in
  `.trivyignore`.
- **A `LoadBalancer` Service, not an Ingress.** EKS provisions the network load
  balancer through the cloud controller it already runs, so there is no ingress
  controller to install, upgrade or debug.
- **The ECR repository allows tag overwrites.** A retried deploy pushes the same
  commit-sha tag, and an immutable repository would reject it.
- **All five JDBC drivers ship.** See "The two choices" above.
- **Aurora uses Serverless v2**, which scales with load rather than asking a
  blueprint to guess an instance size.
- **The AWS provider is pinned below 5.83.0.** From that release the provider
  treats `aws_db_instance.password` as write-only, which breaks reading the
  generated password back out for the application secret.
- **Trivy blocks on CRITICAL for infrastructure, HIGH for images.**

## Deploying

Provide nothing. The platform supplies `PROJECT_NAME`, `TF_STATE_BUCKET` and the
AWS credentials as pipeline secrets; the database password is generated by
Terraform and never leaves the state file and the Kubernetes Secret. First deploy
takes 25–35 minutes, most of it the EKS control plane and the database. Redeploys
are 8–12 minutes.
