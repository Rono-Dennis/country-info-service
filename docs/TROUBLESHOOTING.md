# Troubleshooting country-info-service on Kubernetes

Set a shortcut first: `alias k='kubectl -n country-info-service'`

## 1. commands to run

```bash
k get pods -o wide                      # status, restarts, node
k get events --sort-by=.lastTimestamp   # scheduling, image pull
k describe pod <pod>                    # events 
k logs <pod> --tail=100                 # app logs
k logs <pod> --previous                 # logs of the crashed container
k get endpoints country-info-service         # empty = no ready pods behind the Service
k top pods                              # CPU/memory (needs metrics-server)
```

## 2. Symptom → cause → fix

| Symptom | probable cause | How to confirm | Fix |
|---|---|---|---|
| `ImagePullBackOff` / `ErrImagePull` | Image not on the node / wrong tag / private registry | `k describe pod` → Events | `minikube image load` / `kind load docker-image`, or fix `image:` and add `imagePullSecrets` |
| `Init:0/1` stuck | MySQL not ready (init container waits) | `k logs <pod> -c wait-for-mysql`; `k get pod mysql-0` | Fix MySQL first (see below) |
| `CrashLoopBackOff` | Bad DB URL/credentials, MySQL down, bad env | `k logs <pod> --previous` (look for `Communications link failure`, `Access denied`) | Correct `country-service-config` / `-secret`, then `k rollout restart deploy/country-service` |
| `Running` but `0/1` Ready | Readiness probe failing: DB unreachable | `k describe pod` (Readiness probe failed); `k exec <pod> -- wget -qO- localhost:8080/actuator/health/readiness` | Restore DB connectivity; pod becomes Ready by itself |
| Pod restarts every few minutes, `OOMKilled` | Memory limit too low | `k describe pod` → Last State: OOMKilled | Raise `limits.memory` (and keep `MaxRAMPercentage` at ~75), check for traffic spikes |
| Pod killed during startup | Startup probe too short for slow node | Events: `Startup probe failed` | Increase `failureThreshold` of `startupProbe` |
| `Pending` | No node has the requested CPU/memory | `k describe pod` → FailedScheduling | Lower requests or add nodes |
| API returns **503** `Upstream service unavailable` | SOAP provider down/slow, or circuit breaker open | Logs: `soap_call ... outcome=error`, `SOAP fallback triggered`; `curl localhost:8080/actuator/health` shows `countrySoap` circuit state | Check egress to `webservices.oorsprong.org:80`; `k exec <pod> -- wget -qO- --timeout=5 "http://webservices.oorsprong.org/websamples.countryinfo/CountryInfoService.wso?WSDL"`. Circuit closes automatically ~20s after the provider recovers |
| API returns **404** for a valid country | Name not matched by the SOAP service | Logs `not_found`; test the same name in SoapUI | Use the provider's exact spelling (multi-word names: sentence case turns "south africa" into "South africa") |
| API returns **409** | Concurrent insert of the same ISO code | Logs `data_conflict` | Retry the request (second call becomes an update) |
| API returns **502** | SOAP reply didn't match the expected schema | Logs `upstream_payload_error` with stack trace | Compare the raw reply in SoapUI with `client/wsdl` classes |
| API returns **500** | Unhandled bug | Log line `unhandled_exception` with the same `correlationId` as in the response body | Fix code / report with the correlation id |
| High latency | Cache cold, SOAP slow, DB pool exhausted | Metrics: `soap_client_requests_seconds`, `hikaricp_connections_pending` | Scale out (`k scale`), tune `DB_POOL_SIZE`, check provider latency |
| Ingress returns 404/502 | Wrong host/class, no ready endpoints | `k describe ingress`; `k get endpoints` | Fix host/`ingressClassName`, resolve not-ready pods |
| Service reachable inside cluster but not via ingress | Missing `/etc/hosts` entry or no ingress controller | `kubectl get pods -n ingress-nginx` | Enable controller, add host entry |

## 3. Tracing one request

Every response carries `X-Correlation-Id` (and error bodies include `correlationId`). Find all log lines for it:

```bash
k logs -l app=country-service --tail=-1 | grep '<correlation-id>'
```

Typical healthy sequence: `http_request` ← `lookup_started` ← `soap_call CountryISOCode` ← `soap_call FullCountryInfo` ← `lookup_stored`.

## 4. Useful endpoints (inside the pod / via port-forward)

```bash
k port-forward deploy/country-info-service 8080:8080
curl localhost:8080/actuator/health                 # overall + circuit breaker + db
curl localhost:8080/actuator/health/readiness       # what Kubernetes uses to route traffic
curl localhost:8080/actuator/health/liveness
curl localhost:8080/actuator/metrics/soap.client.requests
curl localhost:8080/actuator/prometheus             # scrape format
curl localhost:8080/actuator/metrics/resilience4j.circuitbreaker.state
```

## 5. MySQL checks

```bash
k get pod mysql-0 && k logs mysql-0 --tail=50
k exec -it mysql-0 -- mysql -ucountryapp -p countrydb -e 'select id, iso_code, name from country_info;'
k get pvc                                           # data volume should be Bound
```

- `mysql-0` `Pending`: PVC cannot bind → `k describe pvc data-mysql-0`, check the StorageClass.
- Auth errors after changing the secret: MySQL only reads `MYSQL_USER/PASSWORD` on first initialisation of
  the volume. Either change the password inside MySQL or delete the PVC (**destroys data**).

## 6. Rolling back a bad release

```bash
k rollout history deploy/country-info-service
k rollout undo deploy/country-info-service
k rollout status deploy/country-info-service
```

## 7. Debug a pod interactively

```bash
k exec -it <pod> -- sh
k debug -it <pod> --image=busybox:1.36 --target=country-info-service   # when the image has no tools
```
