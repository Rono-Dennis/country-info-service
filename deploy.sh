#!/usr/bin/env bash
# =============================================================================
# deploy.sh - version, build, push and deploy country-service to Kubernetes
#
#   1. bump the version (major.minor, minor rolls over after 9, kept in version files)
#   2. run the unit tests
#   3. build the Docker image (multi-stage Dockerfile compiles the jar inside Docker)
#   4. push to the registry (or load into kind/minikube)
#   5. apply the Kubernetes manifests, create the DB secret, roll out the new image
#   6. wait for the rollout, auto-rollback on failure, smoke-test through port-forward
#   7. optionally commit and push to git
#
# Credentials are NEVER stored in this file. Provide them as environment variables:
#   DOCKER_USERNAME, DOCKER_PASSWORD   registry login (or run `docker login` once yourself)
#   DB_USER, DB_PASSWORD, MYSQL_ROOT_PASSWORD
#                                      first deploy / rotation. If the secret already exists
#                                      in the cluster and these are unset, it is kept as is.
#
# Usage:  ./deploy.sh [options]      (see --help)
# =============================================================================
set -Eeuo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

# ----------------------------- configuration ---------------------------------
IMAGE_NAME="${IMAGE_NAME:-ronodennis/country-info-service}"
K8S_DIR="${K8S_DIR:-k8s}"

# Names are read from your manifests, so the script follows any renaming you made there
# (e.g. country-info-service). Override with NAMESPACE=, DEPLOYMENT=, SECRET_NAME=,
# SERVICE_NAME= or APP_LABEL= if you ever need to.
yaml_name() {      # yaml_name <file> <Kind>  ->  metadata.name of the first <Kind> in <file>
  awk -v kind="$2" '
    { sub(/\r$/, "") }
    $0 == "kind: " kind { f = 1; next }
    f && /^  name:/ { gsub(/"/, "", $2); print $2; exit }' "$1" 2>/dev/null || true
}
NAMESPACE="${NAMESPACE:-$(yaml_name "$K8S_DIR/00-namespace.yaml" Namespace)}"
DEPLOYMENT="${DEPLOYMENT:-$(yaml_name "$K8S_DIR/03-deployment.yaml" Deployment)}"
SECRET_NAME="${SECRET_NAME:-$(yaml_name "$K8S_DIR/01-secret.yaml" Secret)}"
SERVICE_NAME="${SERVICE_NAME:-$(yaml_name "$K8S_DIR/04-service-ingress.yaml" Service)}"
APP_LABEL="${APP_LABEL:-$(awk '{ sub(/\r$/, "") } /^[[:space:]]+app:[[:space:]]/ { print $2; exit }' "$K8S_DIR/03-deployment.yaml" 2>/dev/null || true)}"
NAMESPACE="${NAMESPACE:-country-info-service}"
DEPLOYMENT="${DEPLOYMENT:-country-info-service}"
SECRET_NAME="${SECRET_NAME:-country-info-service-secret}"
SERVICE_NAME="${SERVICE_NAME:-$DEPLOYMENT}"
APP_LABEL="${APP_LABEL:-$DEPLOYMENT}"
MAJOR_VERSION_FILE="${MAJOR_VERSION_FILE:-major-version.txt}"
MINOR_VERSION_FILE="${MINOR_VERSION_FILE:-minor-version.txt}"
ROLLOUT_TIMEOUT="${ROLLOUT_TIMEOUT:-240s}"
SMOKE_PORT="${SMOKE_PORT:-18080}"

SKIP_TESTS=0
SKIP_PUSH=0
NO_GIT=0
ASSUME_YES=0
DRY_RUN=0
LOAD_INTO=""
PF_PID=""
DOCKER_LOGGED_IN=0

# ------------------------------- helpers -------------------------------------
log()  { printf '\n==> %s\n' "$*"; }
warn() { printf 'WARNING: %s\n' "$*" >&2; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'EOF'
Usage: ./deploy.sh [options]

  --skip-tests        do not run `mvn test` before building
  --skip-push         build only; do not push to the registry
                      (fine for Docker Desktop Kubernetes, which shares local images)
  --load kind|minikube
                      load the image into a local cluster instead of pushing (implies --skip-push)
  --no-git            do not commit/push to git at the end
  -y, --yes           do not ask for confirmation of the target cluster
  --dry-run           print every command instead of running it (nothing is changed)
  -h, --help          show this help

Environment overrides: IMAGE_NAME, NAMESPACE, K8S_DIR, ROLLOUT_TIMEOUT, SMOKE_PORT,
GIT_MESSAGE, GIT_BRANCH, DOCKER_USERNAME, DOCKER_PASSWORD, DB_USER, DB_PASSWORD,
MYSQL_ROOT_PASSWORD
EOF
}

run() {                       # execute, or only print when --dry-run
  if (( DRY_RUN )); then printf '[dry-run] %s\n' "$*"; else "$@"; fi
}

k() { kubectl -n "$NAMESPACE" "$@"; }

need() {
  if ! command -v "$1" >/dev/null 2>&1; then
    if (( DRY_RUN )); then warn "'$1' not installed (ignored in --dry-run)"; else die "'$1' is required but not installed"; fi
  fi
}

cleanup() {
  if [[ -n "$PF_PID" ]]; then kill "$PF_PID" 2>/dev/null || true; fi
  if [[ "$DOCKER_LOGGED_IN" == 1 ]]; then docker logout >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT

# ----------------------------- arguments -------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-tests) SKIP_TESTS=1 ;;
    --skip-push)  SKIP_PUSH=1 ;;
    --load)       LOAD_INTO="${2:-}"; SKIP_PUSH=1; shift ;;
    --no-git)     NO_GIT=1 ;;
    -y|--yes)     ASSUME_YES=1 ;;
    --dry-run)    DRY_RUN=1 ;;
    -h|--help)    usage; exit 0 ;;
    *)            die "Unknown option: $1 (try --help)" ;;
  esac
  shift
done
if [[ -n "$LOAD_INTO" && "$LOAD_INTO" != "kind" && "$LOAD_INTO" != "minikube" ]]; then
  die "--load expects 'kind' or 'minikube'"
fi

# ------------------------------ versioning -----------------------------------
# Same scheme as the original script: minor counts 0..9, then major+1 and minor back to 0.
# Starting values (1 / -1) make the first release 1.0.
read_version() {
  if [[ ! -f "$MAJOR_VERSION_FILE" ]]; then (( DRY_RUN )) || echo "1"  > "$MAJOR_VERSION_FILE"; fi
  if [[ ! -f "$MINOR_VERSION_FILE" ]]; then (( DRY_RUN )) || echo "-1" > "$MINOR_VERSION_FILE"; fi
  MAJOR_VERSION="$(cat "$MAJOR_VERSION_FILE" 2>/dev/null || echo 1)"
  MINOR_VERSION="$(cat "$MINOR_VERSION_FILE" 2>/dev/null || echo -1)"
  if ! [[ "$MAJOR_VERSION" =~ ^[0-9]+$ ]] || ! [[ "$MINOR_VERSION" =~ ^-?[0-9]+$ ]]; then
    die "Version files must contain integers (found major='$MAJOR_VERSION' minor='$MINOR_VERSION')"
  fi
}

increment_version() {
  if [[ "$MINOR_VERSION" -ge 9 ]]; then
    MAJOR_VERSION=$((MAJOR_VERSION + 1))
    MINOR_VERSION=0
  else
    MINOR_VERSION=$((MINOR_VERSION + 1))
  fi
}

persist_version() {
  if (( DRY_RUN )); then
    log "dry-run: version files not updated"
  else
    echo "$MAJOR_VERSION" > "$MAJOR_VERSION_FILE"
    echo "$MINOR_VERSION" > "$MINOR_VERSION_FILE"
  fi
}

# ------------------------------- build steps ---------------------------------
run_tests() {
  if (( SKIP_TESTS )); then log "Skipping tests (--skip-tests)"; return 0; fi
  if [[ -f ./mvnw ]]; then
    log "Running unit tests (Maven wrapper)"
    run bash ./mvnw -B -q clean test
  elif command -v mvn >/dev/null 2>&1; then
    log "Running unit tests"
    run mvn -B -q clean test
  else
    warn "Neither ./mvnw nor mvn found: tests skipped (the Docker build compiles but does not run tests)"
  fi
}

build_image() {
  log "Building ${FULL_IMAGE} (also tagged :latest)"
  run docker build -t "$FULL_IMAGE" -t "${IMAGE_NAME}:latest" .
}

docker_login() {
  if [[ -n "${DOCKER_USERNAME:-}" && -n "${DOCKER_PASSWORD:-}" ]]; then
    log "Logging in to the registry as ${DOCKER_USERNAME}"
    if (( DRY_RUN )); then
      echo "[dry-run] docker login -u ${DOCKER_USERNAME} --password-stdin"
    else
      echo "$DOCKER_PASSWORD" | docker login -u "$DOCKER_USERNAME" --password-stdin || die "Docker login failed"
      DOCKER_LOGGED_IN=1
    fi
  else
    warn "DOCKER_USERNAME/DOCKER_PASSWORD not set: relying on an existing 'docker login' session"
  fi
}

publish_image() {
  if [[ -n "$LOAD_INTO" ]]; then
    log "Loading ${FULL_IMAGE} into ${LOAD_INTO}"
    if [[ "$LOAD_INTO" == "kind" ]]; then run kind load docker-image "$FULL_IMAGE"
    else run minikube image load "$FULL_IMAGE"; fi
  elif (( SKIP_PUSH )); then
    log "Skipping push (--skip-push). The cluster must be able to see the local image."
  else
    docker_login
    log "Pushing ${FULL_IMAGE} and :latest"
    run docker push "$FULL_IMAGE"  || die "Docker push failed"
    run docker push "${IMAGE_NAME}:latest" || die "Docker push (latest) failed"
  fi
}

# ------------------------------ kubernetes steps -----------------------------
confirm_cluster() {
  local ctx="(unknown)"
  if command -v kubectl >/dev/null 2>&1; then ctx="$(kubectl config current-context 2>/dev/null || echo '(none)')"; fi
  log "Target Kubernetes context: ${ctx}  |  namespace: ${NAMESPACE}  |  deployment: ${DEPLOYMENT}  |  secret: ${SECRET_NAME}"
  if (( ! DRY_RUN )); then
    kubectl cluster-info >/dev/null 2>&1 || die "Cannot reach the cluster with context '${ctx}'"
  fi
  if (( ! ASSUME_YES && ! DRY_RUN )); then
    local answer=""
    read -rp "Deploy ${FULL_IMAGE} to this cluster? [y/N] " answer || true
    [[ "$answer" =~ ^[Yy]$ ]] || die "Aborted by user"
  fi
}

ensure_secret() {
  if [[ -n "${DB_USER:-}" && -n "${DB_PASSWORD:-}" && -n "${MYSQL_ROOT_PASSWORD:-}" ]]; then
    log "Creating/updating secret ${SECRET_NAME} from environment variables"
    if (( DRY_RUN )); then
      echo "[dry-run] kubectl create secret generic ${SECRET_NAME} --from-literal=... | kubectl apply -f -"
    else
      k create secret generic "$SECRET_NAME" \
        --from-literal=DB_USER="$DB_USER" \
        --from-literal=DB_PASSWORD="$DB_PASSWORD" \
        --from-literal=MYSQL_ROOT_PASSWORD="$MYSQL_ROOT_PASSWORD" \
        --dry-run=client -o yaml | kubectl apply -f -
    fi
  elif (( DRY_RUN )); then
    echo "[dry-run] would keep existing secret ${SECRET_NAME} (DB_* variables not set)"
  elif k get secret "$SECRET_NAME" >/dev/null 2>&1; then
    log "Keeping existing secret ${SECRET_NAME} (DB_* variables not set)"
  else
    die "Secret ${SECRET_NAME} does not exist. Export DB_USER, DB_PASSWORD and MYSQL_ROOT_PASSWORD and run again."
  fi
}

apply_app_manifest() {
  # The manifest ships with a placeholder image; replace it with the version just built.
  local rendered
  # Replace the image of the app container (the first `image:` under `containers:`), whatever it is
  # called in the manifest. The init container (busybox) is left alone. CRLF line endings are kept.
  rendered="$(awk -v img="$FULL_IMAGE" '
    { cr = ($0 ~ /\r$/) ? "\r" : ""; line = $0; sub(/\r$/, "", line) }
    line ~ /^[[:space:]]*containers:[[:space:]]*$/ { main = 1 }
    main && !done && line ~ /^[[:space:]]*(-[[:space:]]+)?image:/ {
      sub(/image:.*/, "image: " img, line); done = 1; print line cr; next }
    { print }
  ' "${K8S_DIR}/03-deployment.yaml")"
  grep -q "image: ${FULL_IMAGE}" <<<"$rendered" \
    || die "Could not find the app container image under 'containers:' in ${K8S_DIR}/03-deployment.yaml"
  log "Applying deployment with image ${FULL_IMAGE}"
  if (( DRY_RUN )); then echo "[dry-run] kubectl apply -f - (03-deployment.yaml with image ${FULL_IMAGE})"
  else kubectl apply -f - <<<"$rendered"; fi
}

deploy_to_kubernetes() {
  log "Applying namespace, config and database"
  run kubectl apply -f "${K8S_DIR}/00-namespace.yaml"
  run kubectl apply -f "${K8S_DIR}/01-config.yaml"
  ensure_secret
  run kubectl apply -f "${K8S_DIR}/02-mysql.yaml"
  if (( ! DRY_RUN )); then
    log "Waiting for MySQL"
    k rollout status statefulset/mysql --timeout=240s || die "MySQL did not become ready (kubectl -n ${NAMESPACE} describe pod mysql-0)"
  fi
  apply_app_manifest
  run kubectl apply -f "${K8S_DIR}/04-service-ingress.yaml"
  run kubectl apply -f "${K8S_DIR}/05-hpa-pdb.yaml"
}

rollback_and_die() {
  warn "$1"
  k get pods -l app="$APP_LABEL" -o wide || true
  k logs -l app="$APP_LABEL" --all-containers=true --tail=40 || true
  warn "Rolling back to the previous revision"
  k rollout undo "deployment/${DEPLOYMENT}" || true
  die "Deployment of ${FULL_IMAGE} failed and was rolled back (if a previous revision existed)"
}

wait_for_rollout() {
  if (( DRY_RUN )); then echo "[dry-run] kubectl rollout status deployment/${DEPLOYMENT}"; return 0; fi
  log "Waiting for rollout (timeout ${ROLLOUT_TIMEOUT})"
  k rollout status "deployment/${DEPLOYMENT}" --timeout="$ROLLOUT_TIMEOUT" \
    || rollback_and_die "Rollout did not complete"
}

http_code() { curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$1" || true; }

smoke_test() {
  if (( DRY_RUN )); then echo "[dry-run] port-forward + GET readiness and /api/v1/countries"; return 0; fi
  if ! command -v curl >/dev/null 2>&1; then warn "curl not found: smoke test skipped"; return 0; fi
  log "Smoke test through port-forward on localhost:${SMOKE_PORT}"
  k port-forward "svc/${SERVICE_NAME}" "${SMOKE_PORT}:80" >/dev/null 2>&1 &
  PF_PID=$!
  local code="" i
  for i in $(seq 1 30); do
    code="$(http_code "http://localhost:${SMOKE_PORT}/actuator/health/readiness")"
    if [[ "$code" == "200" ]]; then break; fi
    sleep 1
  done
  [[ "$code" == "200" ]] || rollback_and_die "Readiness endpoint returned '${code}' instead of 200"
  code="$(http_code "http://localhost:${SMOKE_PORT}/api/v1/countries")"
  [[ "$code" == "200" ]] || rollback_and_die "GET /api/v1/countries returned '${code}' instead of 200"
  echo "Smoke test passed (readiness 200, list endpoint 200)"
  kill "$PF_PID" 2>/dev/null || true
  PF_PID=""
}

git_commit_push() {
  if (( NO_GIT )); then return 0; fi
  if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then warn "Not a git repository: skipping git step"; return 0; fi
  local msg="${GIT_MESSAGE:-}"
  if [[ -z "$msg" ]]; then
    if [[ -t 0 ]]; then read -rp "Enter git commit message: " msg || true
    else msg="Release ${VERSION}"; fi
  fi
  [[ -n "$msg" ]] || die "Commit message cannot be empty"
  local branch="${GIT_BRANCH:-$(git rev-parse --abbrev-ref HEAD)}"
  log "Committing and pushing to origin/${branch}"
  run git add -A
  run git commit -m "$msg"
  run git push origin "$branch"
}

# --------------------------------- main --------------------------------------
need docker
need kubectl
if (( ! NO_GIT )); then need git; fi
if [[ -n "$LOAD_INTO" ]]; then need "$LOAD_INTO"; fi

read_version
echo "Current version: ${MAJOR_VERSION}.${MINOR_VERSION}"
increment_version
VERSION="${MAJOR_VERSION}.${MINOR_VERSION}"
FULL_IMAGE="${IMAGE_NAME}:${VERSION}"
echo "New version:     ${VERSION}"

confirm_cluster
run_tests
build_image
publish_image
persist_version              # the image exists now, so this version number is used up
deploy_to_kubernetes
wait_for_rollout
smoke_test
git_commit_push

log "Done: ${FULL_IMAGE} is deployed to namespace '${NAMESPACE}'"
echo "Check:  kubectl -n ${NAMESPACE} get pods"
echo "Undo:   kubectl -n ${NAMESPACE} rollout undo deployment/${DEPLOYMENT}"