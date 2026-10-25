#!/usr/bin/env bash
# Levanta agro-monitoring desde cero, sin intervencion manual:
#   1. prerequisitos (docker, minikube, kubectl, helm, curl)
#   2. cluster Minikube + addon metrics-server
#   3. stack de monitoreo (kube-prometheus-stack con monitoring/values.yaml)
#   4. build de imagenes dentro del daemon Docker de Minikube
#   5. manifiestos de k8s/ (namespace, configmap, deployments, services,
#      servicemonitors, prometheusrule) + dashboard de Grafana
#   6. verificacion (pods Ready, targets UP) y URLs de acceso
#
# Probado en Windows 11 con Git Bash; tambien funciona en Linux/macOS.
# Es idempotente: se puede volver a ejecutar para aplicar cambios.
#
# Variables opcionales:
#   MINIKUBE_CPUS    (default: min(8, CPUs del host - 2))
#   MINIKUBE_MEMORY  (default: 4608 MB)
#   GRAFANA_PASSWORD solo se muestra; la contrasena real esta en monitoring/values.yaml
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PROFILE=minikube
K8S_VERSION=v1.33.1
NODE_PORTS="30300:30300,30900:30900,30903:30903,30081:30081,30082:30082"
HOST_CPUS=$(nproc 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)
DEFAULT_CPUS=$(( HOST_CPUS - 2 )); (( DEFAULT_CPUS > 8 )) && DEFAULT_CPUS=8; (( DEFAULT_CPUS < 2 )) && DEFAULT_CPUS=2
MINIKUBE_CPUS=${MINIKUBE_CPUS:-$DEFAULT_CPUS}
MINIKUBE_MEMORY=${MINIKUBE_MEMORY:-4608}
IMAGES=(catalogo-cultivos gestion-cosechas)
TAG=1.0.0

paso() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
ok()   { printf '    \033[32m✔\033[0m %s\n' "$*"; }
falla(){ printf '    \033[31m✘ %s\033[0m\n' "$*" >&2; exit 1; }

es_windows() { [[ "$(uname -s)" == MINGW* || "$(uname -s)" == MSYS* || "$(uname -s)" == CYGWIN* ]]; }

# En Windows, winget deja los alias en %LOCALAPPDATA%\Microsoft\WinGet\Links;
# lo agregamos al PATH por si la terminal se abrio antes de instalar.
if es_windows && [[ -n "${LOCALAPPDATA:-}" ]]; then
  export PATH="$PATH:$(cygpath -u "$LOCALAPPDATA")/Microsoft/WinGet/Links"
fi

instalar() {   # instalar <comando> <id-winget> <instrucciones-linux>
  local cmd=$1 winget_id=$2 linux=$3
  if es_windows && command -v winget >/dev/null; then
    echo "    $cmd no encontrado: instalando con winget ($winget_id)..."
    winget install --id "$winget_id" -e --accept-source-agreements --accept-package-agreements --disable-interactivity >/dev/null
    hash -r
  elif [[ -n "$linux" ]]; then
    echo "    $cmd no encontrado: instalando..."
    eval "$linux"
  fi
  command -v "$cmd" >/dev/null || falla "No se pudo instalar $cmd. Instalalo manualmente y vuelve a ejecutar."
}

# ---------------------------------------------------------------------------
paso "1/6 Prerequisitos"
command -v curl >/dev/null || falla "curl es necesario"
command -v docker >/dev/null || falla "Docker no esta instalado (Windows: winget install Docker.DockerDesktop)"
command -v minikube >/dev/null || instalar minikube Kubernetes.minikube \
  'curl -fsSLo /tmp/minikube https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64 && sudo install /tmp/minikube /usr/local/bin/minikube'
command -v kubectl >/dev/null || instalar kubectl Kubernetes.kubectl \
  'curl -fsSLo /tmp/kubectl "https://dl.k8s.io/release/$(curl -fsSL https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl" && sudo install /tmp/kubectl /usr/local/bin/kubectl'
command -v helm >/dev/null || instalar helm Helm.Helm \
  'curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash'

if ! docker info >/dev/null 2>&1; then
  if es_windows && [[ -x "/c/Program Files/Docker/Docker/Docker Desktop.exe" ]]; then
    echo "    Docker no responde: iniciando Docker Desktop..."
    "/c/Program Files/Docker/Docker/Docker Desktop.exe" >/dev/null 2>&1 &
    for _ in $(seq 1 60); do docker info >/dev/null 2>&1 && break; sleep 5; done
  fi
  docker info >/dev/null 2>&1 || falla "El daemon de Docker no responde"
fi
ok "docker $(docker version --format '{{.Server.Version}}')"
ok "minikube $(minikube version --short)"
ok "kubectl $(kubectl version --client -o json 2>/dev/null | grep -m1 gitVersion | cut -d'"' -f4)"
ok "helm $(helm version --short)"

# ---------------------------------------------------------------------------
paso "2/6 Cluster Minikube (${MINIKUBE_CPUS} CPUs, ${MINIKUBE_MEMORY} MB, Kubernetes ${K8S_VERSION})"
if minikube -p "$PROFILE" status --format '{{.Host}}' 2>/dev/null | grep -q Running; then
  ok "el cluster ya esta corriendo"
else
  # --ports publica los NodePorts en localhost: con el driver docker en
  # Windows/macOS la IP del nodo (192.168.49.2) no es accesible desde el host.
  minikube start -p "$PROFILE" --driver=docker \
    --cpus="$MINIKUBE_CPUS" --memory="$MINIKUBE_MEMORY" --disk-size=30g \
    --kubernetes-version="$K8S_VERSION" --ports="$NODE_PORTS"
fi
minikube -p "$PROFILE" addons enable metrics-server >/dev/null
kubectl config use-context "$PROFILE" >/dev/null
kubectl wait --for=condition=Ready node --all --timeout=180s >/dev/null
ok "nodo Ready, metrics-server habilitado"

# ---------------------------------------------------------------------------
paso "3/6 Stack de monitoreo (kube-prometheus-stack)"
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts --force-update >/dev/null
helm repo update prometheus-community >/dev/null
helm upgrade --install monitoring prometheus-community/kube-prometheus-stack \
  -n monitoring --create-namespace -f monitoring/values.yaml --wait --timeout 15m >/dev/null
ok "release 'monitoring' instalado en el namespace monitoring"

# ---------------------------------------------------------------------------
paso "4/6 Build de imagenes en el daemon Docker de Minikube"
eval "$(minikube -p "$PROFILE" docker-env --shell bash)"
for img in "${IMAGES[@]}"; do
  docker build -q -t "agro/$img:$TAG" "microservices/$img" >/dev/null
  ok "agro/$img:$TAG"
done
eval "$(minikube -p "$PROFILE" docker-env --shell bash --unset)"

# ---------------------------------------------------------------------------
paso "5/6 Manifiestos de k8s/ y dashboard"
# Si los deployments ya existian, la imagen cambio con el mismo tag y hay que
# reiniciarlos para que la tomen (imagePullPolicy: Never).
YA_EXISTIAN=$(kubectl -n agro get deploy -o name 2>/dev/null || true)
kubectl apply -f k8s/00-namespace.yaml >/dev/null
for img in "${IMAGES[@]}"; do kubectl apply -f "k8s/$img/" >/dev/null; done
kubectl apply -f k8s/prometheus-rules.yaml >/dev/null
[[ -n "$YA_EXISTIAN" ]] && kubectl -n agro rollout restart deploy >/dev/null
kubectl -n monitoring create configmap agro-monitoring-dashboard \
  --from-file=monitoring/dashboards/agro-monitoring-dashboard.json --dry-run=client -o yaml \
  | kubectl label --local -f - grafana_dashboard=1 -o yaml | kubectl apply -f - >/dev/null
for img in "${IMAGES[@]}"; do
  kubectl -n agro rollout status "deploy/$img" --timeout=300s >/dev/null
  ok "deployment $img listo"
done

# ---------------------------------------------------------------------------
paso "6/6 Verificacion"
for _ in $(seq 1 40); do
  UP=$(curl -s http://localhost:30900/api/v1/query --data-urlencode 'query=sum(up{namespace="agro"})' \
        | grep -o '"value":\[[^]]*\]' | grep -o '"[0-9]*"\]' | tr -d '"]' || true)
  [[ "$UP" == 2 ]] && break
  sleep 5
done
[[ "${UP:-0}" == 2 ]] && ok "Prometheus: 2/2 targets agro UP" || echo "    ! Prometheus aun no ve los 2 targets UP (revisa http://localhost:30900/targets)"
kubectl -n agro get pods

cat <<EOF

  ============================================================
   agro-monitoring listo
  ============================================================
   Grafana       http://localhost:30300   (admin / ${GRAFANA_PASSWORD:-AgroMonitoring2026!})
                 Dashboard: http://localhost:30300/d/agro-monitoring
   Prometheus    http://localhost:30900   (targets: /targets, alertas: /alerts)
   Alertmanager  http://localhost:30903
   catalogo      http://localhost:30081/api/cultivos   (metricas: /metrics)
   cosechas      http://localhost:30082/api/cosechas   (metricas: /metrics)

   Generar trafico:  ./load-testing/stress.sh -d 120 -r 5
  ============================================================
EOF
