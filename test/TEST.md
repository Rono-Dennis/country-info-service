# TEST.md: testing country-service with Docker

This guide tests the whole system **using only Docker Desktop**. You do not need Java or Maven installed.
It exercises every endpoint, the error cases, the database, observability and failure handling.

- App: `http://localhost:8080`
- MySQL (inside Docker): service `mysql`, database `countrydb`, user `root` / password `root`

---

## 1. Prerequisites

| Need | Check |
|---|---|
| Docker Desktop **running** (whale icon steady) | `docker version` shows both Client and Server |
| Docker Compose v2 | `docker compose version` |
| Free ports **8080** and **3306** | see Troubleshooting if either is taken |
| Internet access | the build downloads Maven dependencies, and the app calls the public SOAP service at `webservices.oorsprong.org` |
| A way to send requests | Postman, or `curl` (Windows: use `curl.exe` in PowerShell, see the note below) |

> **Windows / PowerShell note:** in PowerShell `curl` is an alias of `Invoke-WebRequest`. Use `curl.exe`, and escape
> quotes inside JSON: `curl.exe -X POST ... -d "{\"name\":\"kenya\"}"`. Postman avoids this entirely.

---

## 2. Build and start everything

From the project root (the folder containing `docker-compose.yml`):

```bash
docker compose up --build -d
```

The first build takes a few minutes (it downloads dependencies). Compose starts MySQL first and only starts the
app once MySQL is healthy.

Check the state:

```bash
docker compose ps
```

Expected: `mysql` is `healthy` and `app` is `running`.

Watch the app boot (Ctrl+C stops following, not the app):

```bash
docker compose logs -f app
```

Wait for a line containing `Started CountryServiceApplication`.

Quick liveness check:

```bash
curl http://localhost:8080/actuator/health
```

Expected: `{"status":"UP"}`

---

## 3. Endpoint reference

Base path: `/api/v1/countries`

| # | Method | URL | Body | Success | Possible errors |
|---|---|---|---|---|---|
| 1 | POST | `/api/v1/countries` | `{"name":"kenya"}` | **201** new / **200** already stored | 400, 404, 502, 503 |
| 2 | GET | `/api/v1/countries?page=0&size=20` | none | **200** | |
| 3 | GET | `/api/v1/countries/{id}` | none | **200** | 400, 404 |
| 4 | PUT | `/api/v1/countries/{id}` | partial JSON (see below) | **200** | 400, 404 |
| 5 | DELETE | `/api/v1/countries/{id}` | none | **204** | 400, 404 |
| 6 | GET | `/actuator/health` | none | **200** | |
| 7 | GET | `/actuator/health/liveness` | none | **200** | |
| 8 | GET | `/actuator/health/readiness` | none | **200** | 503 if DB is down |
| 9 | GET | `/actuator/metrics` | none | **200** | |
| 10 | GET | `/actuator/metrics/soap.client.requests` | none | **200** | 404 until a SOAP call has been made |
| 11 | GET | `/actuator/prometheus` | none | **200** | |

Every response carries an `X-Correlation-Id` header. Error bodies are `application/problem+json`.

---

## 4. Functional tests: the happy path

Run these **in order**. IDs below assume an empty database, so Kenya gets `id` 1.

### T1. Create a country (POST)

```bash
curl -i -X POST http://localhost:8080/api/v1/countries \
  -H "Content-Type: application/json" \
  -d '{"name":"kenya"}'
```

Expected:
- Status **201 Created**, with a `Location: http://localhost:8080/api/v1/countries/1` header
- Body:

```json
{
  "id": 1,
  "isoCode": "KE",
  "name": "Kenya",
  "capitalCity": "Nairobi",
  "phoneCode": "254",
  "continentCode": "AF",
  "currencyIsoCode": "KES",
  "countryFlag": "http://www.oorsprong.org/WebSamples.CountryInfo/Flags/Kenya.jpg",
  "languages": [ { "isoCode": "swa", "name": "Swahili" } ],
  "createdAt": "...",
  "updatedAt": "..."
}
```

This proves the full chain: sentence case (`kenya` becomes `Kenya`), SOAP `CountryISOCode`, SOAP `FullCountryInfo`, and save to MySQL.

### T2. Idempotency: same country again

```bash
curl -i -X POST http://localhost:8080/api/v1/countries \
  -H "Content-Type: application/json" \
  -d '{"name":"KENYA"}'
```

Expected: **200 OK** (not 201), the **same `id` 1**. A different spelling of the same country does not create a duplicate.

### T3. Add more countries

```bash
curl -s -X POST http://localhost:8080/api/v1/countries -H "Content-Type: application/json" -d '{"name":"uganda"}'
curl -s -X POST http://localhost:8080/api/v1/countries -H "Content-Type: application/json" -d '{"name":"tanzania"}'
```

Expected: each returns 201 with its own `id`, ISO code (`UG`, `TZ`), capital, phone code and currency.

### T4. List all (GET, paged)

```bash
curl -s "http://localhost:8080/api/v1/countries?page=0&size=20"
```

Expected: `content` has 3 countries sorted by name (Kenya, Tanzania, Uganda), and `page.totalElements` is `3`.

Paging check:

```bash
curl -s "http://localhost:8080/api/v1/countries?page=0&size=1"
```

Expected: 1 item in `content`, `page.totalPages` is `3`. A `size` above 100 is capped to 100.

### T5. Get one (GET by id)

```bash
curl -i http://localhost:8080/api/v1/countries/1
```

Expected: **200**, the Kenya record.

### T6. Update (PUT)

Fields you omit stay unchanged. If you send `languages`, the whole list is replaced.

```bash
curl -i -X PUT http://localhost:8080/api/v1/countries/1 \
  -H "Content-Type: application/json" \
  -d '{"capitalCity":"Nairobi City"}'
```

Expected: **200**, `capitalCity` is `Nairobi City`, everything else (name, ISO code, languages) is unchanged, and `updatedAt` is later than `createdAt`.

Replacing languages:

```bash
curl -s -X PUT http://localhost:8080/api/v1/countries/1 \
  -H "Content-Type: application/json" \
  -d '{"languages":[{"isoCode":"eng","name":"English"},{"isoCode":"swa","name":"Swahili"}]}'
```

Expected: `languages` now has exactly those 2 entries.

### T7. Delete

```bash
curl -i -X DELETE http://localhost:8080/api/v1/countries/1
```

Expected: **204 No Content** and an empty body.

Confirm it is gone:

```bash
curl -i http://localhost:8080/api/v1/countries/1
```

Expected: **404**.

---

## 5. Negative tests: validation and errors

| # | Request | Expected |
|---|---|---|
| E1 | `POST` body `{"name":""}` | **400**, `title: "Validation failed"`, `errors.name` present |
| E2 | `POST` body `{"name":"   "}` | **400** (blank is rejected) |
| E3 | `POST` body `{}` | **400** |
| E4 | `POST` body `{oops` (malformed JSON) | **400** |
| E5 | `POST` body `{"name":"atlantis"}` | **404**, `title: "Country not found"` |
| E6 | `POST` body `{"name":"<script>"}` | **400** (invalid characters) |
| E7 | `POST` with a name of 101+ characters | **400** |
| E8 | `GET /api/v1/countries/999` | **404** |
| E9 | `GET /api/v1/countries/abc` | **400** (invalid parameter) |
| E10 | `PUT /api/v1/countries/999` body `{}` | **404** |
| E11 | `PUT /api/v1/countries/2` body `{"name":""}` | **400** |
| E12 | `DELETE /api/v1/countries/999` | **404** |
| E13 | `POST` with `Content-Type: text/plain` | **415** |

Example (E1):

```bash
curl -i -X POST http://localhost:8080/api/v1/countries \
  -H "Content-Type: application/json" -d '{"name":""}'
```

Expected body shape (RFC 9457 problem+json):

```json
{
  "type": "about:blank",
  "title": "Validation failed",
  "status": 400,
  "detail": "One or more fields are invalid.",
  "errors": { "name": "name is required" },
  "timestamp": "...",
  "correlationId": "..."
}
```

Multi-word names (known risk, test it):

```bash
curl -i -X POST http://localhost:8080/api/v1/countries \
  -H "Content-Type: application/json" -d '{"name":"south africa"}'
```

Sentence case turns this into `South africa`. If the SOAP service does not accept that spelling you will get a 404.
That is a limitation of strict sentence case, not a crash.

---

## 6. Verify the database

Check the rows directly in MySQL inside Docker:

```bash
docker compose exec mysql mysql -uroot -proot countrydb \
  -e "select id, iso_code, name, capital_city, currency_iso_code from country_info; select * from country_language;"
```

Expected: one row per stored country and its language rows. The `country_language` rows reference `country_id`.

Check the unique constraint on the ISO code:

```bash
docker compose exec mysql mysql -uroot -proot countrydb -e "show index from country_info;"
```

Expected: a unique index on `iso_code`.

After T7 (delete), the deleted country's language rows must be gone too (cascade).

---

## 7. Observability tests

### Correlation id and logs

Send your own id:

```bash
curl -i http://localhost:8080/api/v1/countries -H "X-Correlation-Id: test-123"
```

Expected: the response header `X-Correlation-Id: test-123` (a UUID is generated if you send none).

Find it in the logs (logs are JSON, one object per line):

```bash
docker compose logs app | grep test-123
```

A POST for a new country logs, in order: `http_request`, `lookup_started`, `soap_call` (twice, once for each SOAP
operation, with `durationMs`), `lookup_stored`.

### Health and metrics

```bash
curl -s http://localhost:8080/actuator/health/liveness
curl -s http://localhost:8080/actuator/health/readiness
curl -s http://localhost:8080/actuator/metrics/soap.client.requests
curl -s http://localhost:8080/actuator/prometheus | grep soap_client
```

Expected:
- liveness and readiness are `{"status":"UP"}`
- `soap.client.requests` shows a `COUNT` and `TOTAL_TIME`, tagged by `operation` and `outcome`
- the Prometheus output has `soap_client_requests_seconds_count{...operation="CountryISOCode",outcome="success"}`

### Cache check

The country lookups are cached for 24 hours inside the app.

1. Note the `COUNT` of `soap.client.requests`.
2. POST a country you already fetched (for example `kenya` after T1).
3. Read `COUNT` again. It must **not** have increased, because the cache answered and no SOAP call was made.
4. POST a new country (for example `rwanda`). `COUNT` increases.

---

## 8. Persistence test

Data lives in a Docker volume, so it survives restarts.

```bash
docker compose restart app            # app restarts, data stays
curl -s http://localhost:8080/api/v1/countries

docker compose down                   # removes containers, KEEPS the volume
docker compose up -d
curl -s http://localhost:8080/api/v1/countries    # data still there
```

Expected: the same countries are still listed.

---

## 9. Failure handling tests

### 9a. SOAP provider outage

Point the app at an unreachable SOAP endpoint:

```bash
# macOS / Linux / Git Bash
SOAP_ENDPOINT=http://localhost:9/bad docker compose up -d app

# Windows PowerShell
$env:SOAP_ENDPOINT="http://localhost:9/bad"; docker compose up -d app
```

Then request a country that is **not cached** (the cache was cleared by the restart):

```bash
curl -i -X POST http://localhost:8080/api/v1/countries \
  -H "Content-Type: application/json" -d '{"name":"ghana"}'
```

Expected: **503 Service Unavailable** with a `Retry-After` header and `title: "Upstream service unavailable"`.
The app retries up to 3 times with backoff before giving up, so it takes a moment.

Send 2 to 3 more requests. After enough failures the circuit breaker opens, and responses become near-instant. The logs show:

```bash
docker compose logs app | grep -i "fallback"
```

Expect `SOAP fallback triggered ... reason=circuit open` once the breaker is open.

The data endpoints keep working while the provider is down:

```bash
curl -s http://localhost:8080/api/v1/countries       # still 200
```

Restore normal operation:

```bash
# macOS / Linux / Git Bash
unset SOAP_ENDPOINT && docker compose up -d app
# Windows PowerShell
Remove-Item Env:SOAP_ENDPOINT; docker compose up -d app
```

Then POST `ghana` again. It should return 201.

### 9b. Database outage

```bash
docker compose stop mysql
curl -i http://localhost:8080/actuator/health/readiness     # expect 503 (DOWN)
curl -i http://localhost:8080/actuator/health/liveness      # expect 200 (the process itself is alive)
curl -i http://localhost:8080/api/v1/countries              # expect an error, not a hang
docker compose start mysql
```

Expected: readiness goes DOWN while MySQL is stopped and comes back UP shortly after it restarts, with no need to restart the app.

---

## 10. Run the unit and integration tests inside Docker

No local Java needed:

```bash
docker run --rm -v "$(pwd)":/src -w /src maven:3.9-eclipse-temurin-21 mvn -B test
```

(PowerShell: use `${PWD}` instead of `$(pwd)`.) Expected: `BUILD SUCCESS`, all tests passing.

---

## 11. Stop and clean up

```bash
docker compose down            # stop, keep the data
docker compose down -v         # stop AND delete the MySQL volume (fresh start)
docker image rm country-service-app   # optional: remove the built image (name may differ; see `docker images`)
```

---

## 12. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `Cannot connect to the Docker daemon` | Docker Desktop is not running | Start Docker Desktop and wait until it says "running" |
| `port is already allocated` for **3306** | A local MySQL is using the port | Stop it, or change the mapping in `docker-compose.yml` to `"3307:3306"` (the app is unaffected, it uses the internal port) |
| `port is already allocated` for **8080** | Something else uses 8080 | Stop it, or change the mapping to `"8081:8080"` and use `localhost:8081` |
| `app` exits right after start | DB not ready or bad config | `docker compose logs app`. Look for `Communications link failure` or `Access denied` |
| POST returns **503** on a healthy machine | The container cannot reach `webservices.oorsprong.org` (corporate proxy, firewall, VPN, or the public service is down) | `docker compose exec app sh -c "echo ok"` is not enough. Test from the host browser: `http://webservices.oorsprong.org/websamples.countryinfo/CountryInfoService.wso?WSDL`. If the host cannot open it either, the provider or your network is the cause |
| POST returns **404** for a valid country | The provider does not recognise the spelling | Try a single-word country first, and see the multi-word note in section 5 |
| Build fails while downloading dependencies | No internet / proxy | Retry on an open network. Check Docker Desktop proxy settings |
| Changes to code are not reflected | Old image reused | `docker compose up --build -d` |
| MySQL password or user changes are ignored | MySQL only reads them on first initialisation | `docker compose down -v` to recreate the volume (deletes data) |
| Port 8080 answers but data is empty | Fresh volume or you ran `down -v` | POST countries again (T1 and T3) |

---

## 13. Quick pass/fail checklist

- [ ] `docker compose up --build -d`: mysql healthy, app running
- [ ] T1 POST returns 201 with Kenya data (KE, Nairobi, 254, KES, Swahili)
- [ ] T2 repeat POST returns 200 with the same id
- [ ] T4 list, T5 get by id, T6 update, T7 delete all behave as expected
- [ ] E1 to E13 return the expected status codes
- [ ] MySQL rows match what the API returns
- [ ] `X-Correlation-Id` is echoed and appears in the logs
- [ ] `/actuator/health/*` and `/actuator/prometheus` respond
- [ ] Repeat POST of a cached country does not increase the SOAP timer count
- [ ] Data survives `docker compose restart app` and `docker compose down` / `up`
- [ ] SOAP outage gives 503 with `Retry-After`, and recovers after restoring the endpoint
- [ ] DB outage turns readiness DOWN and back UP
