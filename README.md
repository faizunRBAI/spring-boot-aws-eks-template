# __UDAP_PROJECT__

A Spring Boot API running on Amazon EKS, provisioned by Terraform and shipped by
a security-gated GitHub Actions pipeline.

> Built from the UDAP `spring-boot-eks` production blueprint. The long-form
> description of the platform lives in [.udap/docs/README.md](.udap/docs/README.md).

## Layout

| Path | What it holds |
| --- | --- |
| `src/main/java/` | The service: `Application`, `web/HealthController`, `web/InfoController` |
| `src/main/resources/` | `application.yaml`, the welcome page in `static/`, Flyway DDL in `db/migration/{vendor}/` |
| `src/test/java/` | JUnit 5 tests |
| `config/checkstyle/` | The coding-standards rule set |
| `bin/migrate` | The migration entrypoint the deploy calls — point it at your tool |
| `infra/` | All Terraform: VPC, EKS, node group, ECR, KMS, and the database |
| `k8s/` | Deployment, Service, HPA, PodDisruptionBudget, ServiceAccount, migration Job |
| `.udap/architecture.d2` | The architecture diagram, in the UDAP D2 profile |
| `.udap/pipeline.yaml` | The pipeline spec — CI workflow files are rendered from it |
| `pom.xml` *or* `build.gradle.kts` | Whichever build tool was chosen when the project was created |

## Endpoints

| Route | Purpose |
| --- | --- |
| `GET /` | UDAP welcome page, with the status line read live from `/health` |
| `GET /health` | Liveness. Never touches the database, so a database outage cannot restart healthy pods |
| `GET /ready` | Readiness. Checks the database when one is configured |
| `GET /api/info` | Runtime and build facts |
| `GET /api/echo` | Sample route to replace with your own |
| `GET /actuator/**` | Spring Actuator: health, info, metrics, prometheus |

## Running it locally

```bash
# Maven
mvn spring-boot:run

# Gradle
gradle bootRun
```

Then open <http://localhost:8080>.

With a database:

```bash
SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5432/appdb' \
SPRING_DATASOURCE_USERNAME=appuser \
SPRING_DATASOURCE_PASSWORD=secret \
  mvn spring-boot:run
```

Without `SPRING_DATASOURCE_URL` the service starts anyway and reports
`database: not configured` — the same behaviour as a deploy with the `none`
database module. That works because an `EnvironmentPostProcessor` switches the
DataSource auto-configuration off when no URL is present; otherwise Spring treats
a missing URL as a fatal misconfiguration and the pod would crash-loop.

The gates the pipeline runs, in local form:

```bash
mvn -B checkstyle:check      # or: gradle checkstyleMain checkstyleTest
mvn -B test                  # or: gradle test
mvn -B verify                # both, plus the packaged jar
docker build -t app .        # the image the pipeline builds
```

## Database migrations

The deploy runs `sh bin/migrate` as a Kubernetes Job before the new Deployment is
applied. A failure stops the deploy, with the migration log printed.

Add numbered Flyway files under the directory for your engine:

```
src/main/resources/db/migration/postgresql/V1__init.sql   # shipped as an example
src/main/resources/db/migration/postgresql/V2__orders.sql # yours
```

`{vendor}` resolves to `postgresql`, `mysql`, `mariadb` or `oracle`, so the same
build carries the right DDL for whichever database module was chosen. Aurora
reuses its engine's directory.

Flyway is **disabled in the running application** on purpose — every replica would
otherwise race to migrate on startup. `bin/migrate` runs the same jar under the
`migrate` profile, which enables Flyway, disables the web server and exits.

Because a rolling update runs old and new pods together, **a migration must work
with the code already running**. Add a nullable column and ship the code that
writes it, then drop the old column in a later release.

## How a deploy runs

Seven gates run in parallel — Checkstyle, unit tests, Semgrep SAST, Gitleaks
secret scanning, licence compliance, SBOM generation and Terraform security
scanning. All of them must pass before any AWS resource is touched. Then Terraform
applies `infra/`, the image is built, pushed to ECR and scanned by Trivy, Flyway
applies the schema as a Job, the manifests in `k8s/` are applied, the rollout is
watched, and the load balancer is health-checked before the deploy is called green.

The pipeline is defined once in `.udap/pipeline.yaml`. Change it there and let the
platform re-render the workflows — never edit `.github/workflows/*` by hand,
because the next render will overwrite it.

## Two kinds of placeholder

| Form | Substituted | By |
| --- | --- | --- |
| `__UDAP_*__` | Once, when the project was created from the blueprint | The blueprint materialiser |
| `%%IMAGE%%`, `%%IMAGE_TAG%%`, `%%NAMESPACE%%` | On every deploy | The configure stage, with `sed` |

## Configuration

Nothing needs to be set by hand. The platform provides these as repository secrets
at deploy time:

| Secret | Used for |
| --- | --- |
| `PROJECT_NAME` | Resource prefix, Kubernetes namespace, ECR repository name, Terraform state key |
| `TF_STATE_BUCKET` | Terraform remote state bucket |
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | Provisioning and cluster access |

The database password is generated by Terraform, stays in the state file, and
reaches the pods only as the `app-database` Kubernetes Secret, which carries
`SPRING_DATASOURCE_URL`, `_USERNAME` and `_PASSWORD`.

## Finding the application URL

The deploy prints it three times: in the **Wait for the load balancer** step, in
the **Health check** step (`Service is healthy at …`), and in the workflow run's
**Summary** panel as a clickable link.

Any time afterwards:

```bash
aws eks update-kubeconfig --name "$PROJECT_NAME-eks" --region "$AWS_REGION"
kubectl get svc api -n "$PROJECT_NAME"      # EXTERNAL-IP column
```

If the link does not open immediately after a first deploy, that is DNS: a newly
created load balancer resolves from inside AWS before it resolves everywhere else,
which is why the health check can pass while a browser still cannot find it.

## Operating it

```bash
aws eks update-kubeconfig --name "$PROJECT_NAME-eks" --region "$AWS_REGION"

kubectl get pods -n "$PROJECT_NAME"
kubectl logs -n "$PROJECT_NAME" -l app.kubernetes.io/name=api --tail=100
kubectl rollout undo deployment/api -n "$PROJECT_NAME"      # roll back one release
kubectl get svc api -n "$PROJECT_NAME"                      # public hostname
```

## Accepted security findings

`.trivyignore` records every infrastructure finding this project accepts, each with
the reason. A finding that is not listed there is a real one — fix it rather than
adding a line.
