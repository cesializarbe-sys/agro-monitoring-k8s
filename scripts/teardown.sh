#!/usr/bin/env bash
# Elimina los recursos de agro-monitoring.
#
# Uso:
#   ./scripts/teardown.sh              # borra microservicios, alertas, dashboard,
#                                      # stack de monitoreo (Helm + CRDs) e imagenes;
#                                      # el cluster Minikube queda vivo
#   ./scripts/teardown.sh --cluster    # ademas elimina el cluster Minikube completo
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
PROFILE=minikube
BORRAR_CLUSTER=false

case "${1:-}" in
  --cluster|-c) BORRAR_CLUSTER=true ;;
  "") ;;
  -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
  *) echo "Parametro desconocido: $1 (usa --cluster o --help)" >&2; exit 1 ;;
esac

paso() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }

if ! minikube -p "$PROFILE" status --format '{{.Host}}' 2>/dev/null | grep -q Running; then
  if $BORRAR_CLUSTER; then
    paso "El cluster no esta corriendo: eliminandolo directamente"
    minikube -p "$PROFILE" delete
    exit 0
  fi
  echo "El cluster '$PROFILE' no esta corriendo; no hay recursos que borrar." >&2
  exit 1
fi

paso "Microservicios, ServiceMonitors y PrometheusRule (namespace agro)"
kubectl delete -f k8s/prometheus-rules.yaml --ignore-not-found
kubectl delete -f k8s/catalogo-cultivos/ -f k8s/gestion-cosechas/ --ignore-not-found
kubectl delete -f k8s/00-namespace.yaml --ignore-not-found --wait=true

paso "Dashboard de Grafana"
kubectl -n monitoring delete configmap agro-monitoring-dashboard --ignore-not-found

paso "Stack de monitoreo (Helm release + namespace + CRDs del operador)"
helm uninstall monitoring -n monitoring 2>/dev/null || echo "release 'monitoring' no encontrado"
kubectl delete namespace monitoring --ignore-not-found --wait=true
# helm uninstall no borra las CRDs; quedarian ServiceMonitor, PrometheusRule, etc.
kubectl get crd -o name | grep 'monitoring.coreos.com' | xargs -r kubectl delete

paso "Imagenes en el daemon Docker de Minikube"
eval "$(minikube -p "$PROFILE" docker-env --shell bash)"
docker rmi -f agro/catalogo-cultivos:1.0.0 agro/gestion-cosechas:1.0.0 2>/dev/null || true
eval "$(minikube -p "$PROFILE" docker-env --shell bash --unset)"

if $BORRAR_CLUSTER; then
  paso "Cluster Minikube completo"
  minikube -p "$PROFILE" delete
else
  echo
  echo "Listo. El cluster sigue activo; para eliminarlo: ./scripts/teardown.sh --cluster"
fi
