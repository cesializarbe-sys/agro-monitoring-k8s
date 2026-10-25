#!/usr/bin/env bash
# Generador de trafico para agro-monitoring.
#
# Cada request queda en un archivo propio (las escrituras concurrentes a un solo
# archivo se mezclan en Git Bash/Windows). Codigo 000 = curl sin respuesta en 10 s.
#
# Uso:
#   ./load-testing/stress.sh [-d DURACION_S] [-r REQ_POR_SEG] [-m MODO] [-u URL_COSECHAS] [-c URL_CATALOGO]
#
# Modos:
#   mixto            (default) cosechas de los 3 cultivos, ~70% cumple el rendimiento
#                    esperado + consultas GET al catalogo
#   bajo-rendimiento todas las cosechas por debajo del rendimiento esperado
#   solo-palta       todas las cosechas son de Palta Hass (cultivoId=3); usado en la casuistica 3
#   catalogo         solo GET al catalogo-cultivos
#
# Ejemplos:
#   ./load-testing/stress.sh                         # 120 s, 5 req/s, mixto
#   ./load-testing/stress.sh -d 300 -r 10 -m solo-palta
set -uo pipefail

DURACION=120
RPS=5
MODO=mixto
URL_COSECHAS="${URL_COSECHAS:-http://localhost:30082}"
URL_CATALOGO="${URL_CATALOGO:-http://localhost:30081}"

while getopts "d:r:m:u:c:h" opt; do
  case $opt in
    d) DURACION=$OPTARG ;;
    r) RPS=$OPTARG ;;
    m) MODO=$OPTARG ;;
    u) URL_COSECHAS=$OPTARG ;;
    c) URL_CATALOGO=$OPTARG ;;
    h|*) sed -n '2,21p' "$0"; exit 0 ;;
  esac
done

case $MODO in mixto|bajo-rendimiento|solo-palta|catalogo) ;; *) echo "Modo invalido: $MODO" >&2; exit 1 ;; esac

# Rendimiento esperado (kg/ha) de los cultivos sembrados en catalogo-cultivos
RENDIMIENTO=( [1]=18000 [2]=9000 [3]=12000 )
RESULTADOS=$(mktemp -d)
trap 'rm -rf "$RESULTADOS"' EXIT
N=0

registrar_cosecha() {
  local cultivo factor ha kg
  if [[ $MODO == solo-palta ]]; then cultivo=3; else cultivo=$(( RANDOM % 3 + 1 )); fi
  case $MODO in
    bajo-rendimiento) factor=$(( 40 + RANDOM % 50 )) ;;   # 40%..89% del esperado
    *)                factor=$(( 85 + RANDOM % 50 )) ;;   # 85%..134% -> ~70% cumple
  esac
  ha=$(( RANDOM % 5 + 1 ))
  kg=$(( ha * RENDIMIENTO[cultivo] * factor / 100 ))
  curl -s -o /dev/null -w "POST %{http_code}\n" --max-time 10 \
    -H 'Content-Type: application/json' \
    -d "{\"cultivoId\":$cultivo,\"hectareasCosechadas\":$ha,\"cantidadKg\":$kg}" \
    "$URL_COSECHAS/api/cosechas" > "$RESULTADOS/$1"
}

consultar_catalogo() {
  curl -s -o /dev/null -w "GET %{http_code}\n" --max-time 10 \
    "$URL_CATALOGO/api/cultivos/$(( RANDOM % 3 + 1 ))" > "$RESULTADOS/$1"
}

echo "Stress: modo=$MODO duracion=${DURACION}s rps=$RPS cosechas=$URL_COSECHAS catalogo=$URL_CATALOGO"
fin=$(( SECONDS + DURACION ))
while (( SECONDS < fin )); do
  for (( i = 0; i < RPS; i++ )); do
    if [[ $MODO == catalogo ]] || { [[ $MODO == mixto ]] && (( RANDOM % 4 == 0 )); }; then
      N=$(( N + 1 )); consultar_catalogo $N &
    else
      N=$(( N + 1 )); registrar_cosecha $N &
    fi
  done
  sleep 1
  restante=$(( fin - SECONDS ))
  (( restante % 15 == 0 )) && echo "  ... ${restante}s restantes ($N requests enviados)"
done
wait

echo "Resumen (metodo codigo_http cantidad):"
cat "$RESULTADOS"/* | sort | uniq -c | awk '{printf "  %s %s -> %s\n", $2, $3, $1}'
