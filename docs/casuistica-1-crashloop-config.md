# Casuística 1 — Servicio caído por error de configuración (CrashLoopBackOff)

## Contexto narrativo

Es lunes de campaña grande de papa en la cooperativa. Un operador quiere "darle más
margen" a la consulta que `gestion-cosechas` hace al catálogo y edita el ConfigMap
`gestion-cosechas-config`. En vez de `2000` escribe `dos-mil`. No pasa nada: el pod sigue
sano, nadie se entera. Horas después el pod se reinicia (un nodo que se drena, un OOM,
un escalado) y el reemplazo **ya no puede arrancar**. Desde ese momento ningún productor
puede registrar su cosecha en el acopio.

Lo interesante del escenario es que el error y la caída están **separados en el tiempo**:
Kubernetes no valida el contenido de un ConfigMap ni reinicia los pods cuando cambia.

> ¿Por qué no simplemente `kubectl set env deployment/... TIMEOUT=dos-mil`? Eso dispararía un
> RollingUpdate, y el pod viejo seguiría atendiendo hasta que el nuevo estuviera Ready (cosa
> que nunca pasaría), así que el servicio no caería. Es la razón por la que se editó el
> ConfigMap y se reinició el pod a mano: así se reproduce la caída. (Este razonamiento es el
> comportamiento esperado de un RollingUpdate; en esta casuística no se ejecutó esa variante.)

## Síntoma

| Dónde | Qué se ve |
|---|---|
| `kubectl -n agro get pods` | `gestion-cosechas-…  0/1  Running/CrashLoopBackOff  6 restarts` |
| Prometheus `/targets` | el target `gestion-cosechas` desaparece (el pod no Ready sale de los Endpoints) |
| Grafana | panel **gestion-cosechas = CAIDO**, **Reinicios de pods (1h)** crece, **Alertas agro FIRING = 2** |
| Prometheus `/alerts` y Alertmanager | `GestionCosechasCaido` (critical) y `PodAgroEnCrashLoop` (critical) en **FIRING** |
| Clientes | `POST /api/cosechas` → conexión rechazada (no hay pods listos detrás del Service) |

## Comando de activación

```bash
# tráfico de fondo para que el dashboard tenga datos
./load-testing/stress.sh -d 600 -r 4 &

# 1) el error humano: valor no numérico en el ConfigMap (el pod sigue sano)
kubectl -n agro patch configmap gestion-cosechas-config --type merge \
  -p '{"data":{"CATALOGO_CULTIVOS_TIMEOUT_MS":"dos-mil"}}'

# 2) el detonante: cualquier reinicio del pod
kubectl -n agro delete pod -l app.kubernetes.io/name=gestion-cosechas
```

## Diagnóstico

```bash
kubectl -n agro get pods                                   # RESTARTS creciendo, 0/1
kubectl -n agro describe pod -l app.kubernetes.io/name=gestion-cosechas
kubectl -n agro logs -l app.kubernetes.io/name=gestion-cosechas --previous | grep -i "failed to convert"
kubectl -n agro get configmap gestion-cosechas-config -o jsonpath='{.data}'
```

Lo que devolvió en nuestro cluster (extracto de [evidencias/casuistica-1.txt](evidencias/casuistica-1.txt)):

```
Last State:     Terminated
  Reason:       Error
  Exit Code:    143
Restart Count:  6
Warning  BackOff  ...  Back-off restarting failed container gestion-cosechas

... Error creating bean with name 'webClientConfig': Unsatisfied dependency expressed
through field 'timeoutMs': Failed to convert value of type 'java.lang.String' to
required type 'int'; For input string: "dos-mil"

{"CATALOGO_CULTIVOS_TIMEOUT_MS":"dos-mil", ...}
```

Ruta de razonamiento: la alerta `PodAgroEnCrashLoop` dice *qué* pod → `logs --previous`
muestra el log del contenedor **que murió** (sin `--previous` vemos el intento actual, que
quizás aún no llegó al error) → el mensaje apunta al campo `timeoutMs` →
`@Value("${app.catalogo-cultivos.timeout-ms}")` → `CATALOGO_CULTIVOS_TIMEOUT_MS` → ConfigMap.

**Detalle observado — exit code 143 y no 1:** el contexto de Spring falló en segundos, pero
la JVM no terminó sola. Fue la `startupProbe` (24 intentos × 5 s = 2 min) la que, al no
obtener respuesta, hizo que el kubelet enviara SIGTERM (143 = 128 + 15). Sin startupProbe
el pod se habría quedado "Running" sin servir nunca; la probe convirtió un cuelgue silencioso
en un CrashLoopBackOff visible.

## Comando de resolución

```bash
kubectl -n agro patch configmap gestion-cosechas-config --type merge \
  -p '{"data":{"CATALOGO_CULTIVOS_TIMEOUT_MS":"2000"}}'
kubectl -n agro rollout restart deployment/gestion-cosechas
kubectl -n agro rollout status  deployment/gestion-cosechas
```

(Alternativa GitOps: `kubectl apply -f k8s/gestion-cosechas/configmap.yaml`, porque el
archivo del repo sigue teniendo el valor correcto.) Tras el rollout el target vuelve a UP y
las dos alertas pasan a *resolved* en 1–2 minutos.

## Lección técnica

1. **Un ConfigMap es una bomba de tiempo**: Kubernetes no valida su contenido ni reinicia
   pods al cambiarlo. El error aparece en el *próximo* reinicio, que puede ser horas después
   y a otra persona. Mitigaciones: versionar la config en Git y aplicarla con `kubectl apply`,
   usar ConfigMaps inmutables con nombre versionado (`gestion-cosechas-config-v2`) para que
   cambiar la config obligue a un rollout controlado, y validar tipos al arrancar
   (`@ConfigurationProperties` + `@Validated`) para fallar con un mensaje claro.
2. **`kubectl logs --previous`** es el comando clave de un CrashLoopBackOff: el log útil es el
   del contenedor que murió.
3. **La alerta de "servicio caído" no puede basarse solo en `up == 0`**: cuando el pod no está
   Ready sale de los Endpoints y el target *desaparece* en vez de valer 0. Por eso usamos
   `(sum(up{job="gestion-cosechas"}) or vector(0)) == 0`.
4. **La startupProbe hace visible lo invisible**: sin ella, una JVM colgada tras un contexto
   fallido parecería "Running".
