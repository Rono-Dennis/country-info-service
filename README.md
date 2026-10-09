# Country Info Service

A Spring Boot microservice that integrates a **REST** API with the public **SOAP** CountryInfoService and stores the
result in **MySQL**. `POST {"name":"kenya"}` returns and saves the country's ISO code, capital, phone code, currency,
flag and languages, and the stored records can then be listed, read, updated and deleted.

```
POST /api/v1/countries {"name":"kenya"}
  1. sentence case            kenya  ->  Kenya
  2. SOAP CountryISOCode      Kenya  ->  KE
  3. SOAP FullCountryInfo     KE     ->  capital, phone code, currency, flag, languages
  4. upsert into MySQL        country_info + country_language   (201 new, 200 refreshed)
```

## Documentation

| Guide | What it covers |
|---|---|
| [docs/TEST.md](docs/TEST.md) | Run and test everything with Docker: every endpoint, error cases, database, metrics, failure tests |
| [docs/DEPLOY.md](docs/DEPLOY.md) | Deploy to Kubernetes (manual steps and the `deploy.sh` script) |
| [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | Symptom, cause and fix table for the running application on Kubernetes |

## Quick start (Docker only)

Requires Docker Desktop and internet access (the service calls `webservices.oorsprong.org`).

```bash
docker compose up --build -d        # MySQL + the app; the first build takes a few minutes
curl -i -X POST http://localhost:8080/api/v1/countries \
  -H "Content-Type: application/json" -d '{"name":"kenya"}'
curl -s http://localhost:8080/api/v1/countries
docker compose down                 # add -v to also delete the database volume
```

Without Docker for the app: `docker compose up -d mysql`, then `SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run`
(Java 17+). Run the automated tests with `./mvnw test` (they use H2 and a mocked SOAP client, so no MySQL or
internet is needed). Windows users and the full test script: [docs/TEST.md](docs/TEST.md).

## API

Base path `/api/v1/countries`. Errors are `application/problem+json` and every response carries `X-Correlation-Id`.

| Method | Path | Purpose | Success | Errors |
|---|---|---|---|---|
| POST | `/api/v1/countries` | Look up by name via SOAP and store | 201 new / 200 refreshed | 400, 404, 502, 503 |
| GET | `/api/v1/countries?page=0&size=20` | List (paged, sorted by name) | 200 | |
| GET | `/api/v1/countries/{id}` | Get one | 200 | 400, 404 |
| PUT | `/api/v1/countries/{id}` | Update (omitted fields unchanged; `languages` replaces the list) | 200 | 400, 404 |
| DELETE | `/api/v1/countries/{id}` | Delete | 204 | 404 |
| GET | `/actuator/health/liveness`, `/actuator/health/readiness` | Kubernetes probes | 200 | readiness 503 if DB is down |
| GET | `/actuator/prometheus`, `/actuator/metrics` | Metrics | 200 | |

## Design

```
client -> Ingress -> Service -> country-info-service pods (2 to 6, stateless)
                                 controller -> service -> repository -> MySQL
                                                  |
                                                  +-> Caffeine cache (24h)
                                                  +-> SOAP client [timeout -> retry -> circuit breaker -> fallback]
                                                            -> CountryInfoService (external)
```

| Concern | Decision | Trade-off |
|---|---|---|
| Scalability | Stateless pods, state only in MySQL; Kubernetes Service load-balances; CPU-based autoscaler (2 to 6 pods) | The database becomes the shared bottleneck |
| Caching | Both SOAP lookups cached in Caffeine for 24h (country data barely changes) | Cache is per pod and can be 24h stale |
| Failures | 3s connect and 5s read timeouts, 3 retries with exponential backoff, circuit breaker (opens at 50% failures over 10 calls, 20s), then a fallback returning **503 + Retry-After** | A request can take up to about 16s in the worst case |
| Idempotency | `iso_code` is unique and POST upserts, so repeating a country never duplicates it | POST doubles as "refresh" |
| Transactions | SOAP calls run outside the DB transaction | A slow provider never holds a DB connection |
| Queuing | Not used: ingestion is synchronous so the caller gets the final result. Kafka or RabbitMQ with a `202 Accepted` flow is the next step if volume grows | Simpler now, less buffering under spikes |
| SOAP binding | JAXB classes kept in source, not generated at build | Must be updated if the provider changes its contract |
| Observability | JSON logs with a correlation id (`X-Correlation-Id`), a `soap.client.requests` timer, Prometheus metrics, liveness and readiness probes | |
| Structure | MVC layers: `controller` (HTTP), `service` (logic, transactions), `client` (SOAP), `repository` and `model` (data), `exception` (problem+json handler), `config` | |
| Containers | Multi-stage Dockerfile, non-root user, container-aware JVM memory, config in ConfigMap and Secret, resource limits, startup/readiness/liveness probes, rolling updates | |

## Configuration (environment variables)

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:mysql://localhost:3306/countrydb...` | JDBC URL (`mysql:3306` in Docker Compose and Kubernetes) |
| `DB_USER` / `DB_PASSWORD` | `root` / `root` | Database credentials (a Secret in Kubernetes) |
| `DB_POOL_SIZE` | `10` | Connection pool size per pod |
| `DDL_AUTO` | `update` | Hibernate schema management |
| `SOAP_ENDPOINT` | CountryInfoService URL | SOAP endpoint |
| `SOAP_CONNECT_TIMEOUT` / `SOAP_READ_TIMEOUT` | `3s` / `5s` | Outbound timeouts |
| `CACHE_SPEC` | `maximumSize=1000,expireAfterWrite=24h` | Cache size and expiry |

The image contains no database settings: the app reads these variables at startup, so the same image runs locally,
in Docker Compose and in Kubernetes.

## Project structure

```
src/main/java/.../{controller,service,client,model,dto,repository,exception,config,util}
src/main/resources/                application.yml, logback-spring.xml
src/test/                          sentence case, SOAP/JAXB mapping, controller and CRUD tests
k8s/                               namespace, config, secret, mysql, deployment, service+ingress, hpa+pdb
docs/                              TEST.md, DEPLOY.md, TROUBLESHOOTING.md
Dockerfile, docker-compose.yml     container build and local stack
deploy.sh                          version, build, push and deploy to Kubernetes
```

## Assessment coverage

| Task | Where |
|---|---|
| 1. Spring Boot app (Web, JPA, MySQL) | `pom.xml` |
| 2. SoapUI / WSDL | JAXB classes in `client/wsdl` mirror the SoapUI messages |
| 3. POST endpoint + sentence case | `CountryController`, `TextUtils` |
| 4. SOAP: name to ISO code | `CountrySoapClient.getIsoCode` |
| 5. SOAP: ISO code to full info | `CountrySoapClient.getFullInfo` |
| 6. `CountryInfo` and `Language` models | `model/` |
| 7. CRUD endpoints | `CountryController`, `CountryService` |
| 8. Kubernetes deployment scripts | `k8s/`, `deploy.sh` |
| 9. Deployment guide | [docs/DEPLOY.md](docs/DEPLOY.md) |
| 10. Troubleshooting guide | [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) |
| Run and test steps | [docs/TEST.md](docs/TEST.md) |

## Known limitations

- **Sentence case:** strict sentence case turns `south africa` into `South africa`, which the SOAP provider may not
  accept for multi-word names (it would return 404).
- **Schema:** `ddl-auto=update` keeps the exercise self-contained; use Flyway or Liquibase in production.
- **Secrets:** `k8s/01-secret.yaml` holds demo values; `deploy.sh` creates the real secret from environment variables.
- **External dependency:** the public SOAP service is the main availability risk, which is why timeouts, retries,
  the circuit breaker and the cache exist.

## Verification status

| Check | Status |
|---|---|
| REST to SOAP to MySQL flow (POST Kenya, KE / Nairobi / 254 / KES / Swahili) | Verified locally |
| Automated tests (`./mvnw test`) | [update before submitting] |
| Docker Compose stack | [update before submitting] |
| Kubernetes deployment (`deploy.sh`) | [update before submitting] |
