# Deploying country-info-service on Kubernetes

Works on a local cluster (minikube / kind / Docker Desktop) or a managed cluster (EKS, GKE, AKS).

## 0. Prerequisites

| Tool | Check |
|---|---|
| Docker | `docker version` |
| kubectl | `kubectl version --client` |
| A cluster | `kubectl get nodes` shows `Ready` |
| (optional) ingress controller | `minikube addons enable ingress` |
| (optional) metrics-server for the HPA | `minikube addons enable metrics-server` |

## 1. Build the image

```bash
docker build -t country-info-service:1.0.0 .
```

**Make the image available to the cluster**

- minikube: `minikube image load country-info-service:1.0.0`
- kind: `kind load docker-image country-info-service:1.0.0`
- Remote cluster: push to a registry and change `image:` in `k8s/03-deployment.yaml`
  ```bash
  docker tag country-info-service:1.0.0 <registry>/country-info-service:1.0.0
  docker push <registry>/country-info-service:1.0.0
  ```
  If the registry is private, create a pull secret and add `imagePullSecrets` to the Deployment.

## 2. Set real credentials

`k8s/01-config.yaml` contains demo passwords. Change them, or create the secret by hand and remove the
Secret from the file:

```bash
kubectl create namespace country-info-service
kubectl -n country-info-service create secret generic country-info-service-secret \
  --from-literal=DB_USER=countryapp \
  --from-literal=DB_PASSWORD='<password>' \
  --from-literal=MYSQL_ROOT_PASSWORD='<root-password>'
```

## 3. Apply the manifests (in order)

```bash
kubectl apply -f k8s/00-namespace.yaml
kubectl apply -f k8s/01-config.yaml
kubectl apply -f k8s/02-mysql.yaml
kubectl -n country-info-service rollout status statefulset/mysql --timeout=180s
kubectl apply -f k8s/03-deployment.yaml
kubectl apply -f k8s/04-service-ingress.yaml
kubectl apply -f k8s/05-hpa-pdb.yaml
kubectl -n country-service rollout status deployment/country-info-service --timeout=240s
```

(Or `kubectl apply -f k8s/` once; the app's init container waits for MySQL.)

## 4. Verify

```bash
kubectl -n country-info-service get pods,svc,hpa,ingress
kubectl -n country-info-service logs deploy/country-info-service --tail=20
```

Expected: 2 `country-info-service` pods `1/1 Running`, 1 `mysql-0` pod `1/1 Running`.

## 5. Smoke test

```bash
kubectl -n country-info-service port-forward svc/country-info-service 8080:80 &

curl -s localhost:8080/actuator/health/readiness
curl -s -X POST localhost:8080/api/v1/countries \
     -H 'Content-Type: application/json' -d '{"name":"kenya"}'
curl -s localhost:8080/api/v1/countries
curl -s localhost:8080/api/v1/countries/1
curl -s -X PUT localhost:8080/api/v1/countries/1 \
     -H 'Content-Type: application/json' -d '{"capitalCity":"Nairobi"}'
curl -s -X DELETE -i localhost:8080/api/v1/countries/1
```

Through the ingress (minikube): add `$(minikube ip) country-info-service.local` to `/etc/hosts`, then use
`http://country-service.local/api/v1/countries`.

Note: the pods need outbound HTTP access to `webservices.oorsprong.org` (port 80). If your cluster restricts
egress, allow it, otherwise POST returns `503`.

## 6. Scale, update, roll back

```bash
# manual scale (HPA normally manages this)
kubectl -n country-info-service scale deploy/country-info-service --replicas=3

# new version
docker build -t country-info-service:1.0.1 . && minikube image load country-info-service:1.0.1
kubectl -n country-info-service set image deploy/country-info-service country-info-service=country-info-service:1.0.1
kubectl -n country-info-service rollout status deploy/country-info-service

# undo
kubectl -n country-info-service rollout undo deploy/country-info-service
kubectl -n country-info-service rollout history deploy/country-info-service
```

## 7. Remove everything

```bash
kubectl delete namespace country-info-service   # also deletes the MySQL PVC and its data
```

## Configuration reference

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:mysql://localhost:3306/countrydb...` | JDBC URL |
| `DB_USER` / `DB_PASSWORD` | `root` / `root` | DB credentials (Secret in k8s) |
| `DB_POOL_SIZE` | `10` | Hikari max pool size per pod |
| `DDL_AUTO` | `update` | Hibernate schema management (`validate` once schema is managed by migrations) |
| `SOAP_ENDPOINT` | CountryInfoService URL | SOAP endpoint |
| `SOAP_CONNECT_TIMEOUT` / `SOAP_READ_TIMEOUT` | `3s` / `5s` | Outbound timeouts |
| `CACHE_SPEC` | `maximumSize=1000,expireAfterWrite=24h` | Caffeine settings |
