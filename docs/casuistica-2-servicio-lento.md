# Casuística 2 — Servicio lento y timeouts en cascada

## Contexto narrativo

Es plena cosecha y el acopio recibe camiones todo el día. `catalogo-cultivos` empieza a
responder lento: puede ser una consulta pesada, un disco saturado o una dependencia externa
degradada. Para simularlo se usa la variable `RESPONSE_DELAY_MS`, que añade un retardo
artificial a `/api/cultivos`. El catálogo **sigue vivo y sano**, solo que tarda 3 segundos.

`gestion-cosechas` consulta al catálogo antes de registrar cada cosecha, y solo está dispuesto
a esperar **2 segundos** (`CATALOGO_CULTIVOS_TIMEOUT_MS=2000`). Como 3 s > 2 s, B se rinde en
cada request y rechaza la cosecha con un 503. Un problema de rendimiento en A se convierte en
una pérdida de negocio en B.

## Síntoma

| Dónde | Qué se ve |
|---|---|
| `kubectl -n agro get pods` | **Todo Running y Ready**; las probes no detectan nada (`/health` no lleva retardo) |
| Prometheus `/targets` | ambos servicios **UP** |
| Grafana | `Delay artificial = 3 s`, p95 de catalogo-cultivos ≈ 3.2 s, p95 de gestion-cosechas ≈ 2.1 s (el timeout), errores/timeouts ≈ 4 por segundo, `Kg de cosecha SIN registrar` en rojo, panel de kg rechazados por motivo (`catalogo_no_disponible`) creciendo |
| Alertas (FIRING) | `CatalogoCultivosLento`, `TimeoutsHaciaCatalogo` y `CosechaSinRegistrarEnAcopio` |
| Cliente | `POST /api/cosechas` → HTTP 503 `No se pudo validar el cultivo contra catalogo-cultivos` |

## Comando de activación

```bash
./load-testing/stress.sh -d 900 -r 3 &        # tráfico de fondo
kubectl -n agro set env deployment/catalogo-cultivos RESPONSE_DELAY_MS=3000
kubectl -n agro rollout status deployment/catalogo-cultivos
```

`set env` modifica el Deployment, así que Kubernetes hace un RollingUpdate: el pod nuevo ya
nace con el retardo. Desde el cambio hasta las alertas en FIRING pasaron unos 2 minutos.

## Diagnóstico

```bash
kubectl -n agro get pods                                   # todo sano -> el problema no es de proceso
curl -s -o /dev/null -w "%{time_total}s\n" http://localhost:30081/api/cultivos/1   # 3.28 s
kubectl -n agro exec deploy/catalogo-cultivos -- printenv RESPONSE_DELAY_MS        # 3000
kubectl -n agro logs deploy/gestion-cosechas | grep "dentro de 2000 ms"
```

Ruta de razonamiento:
1. La alerta `CatalogoCultivosLento` dice **quién** es lento (A, no B).
2. El panel de latencia muestra que B tiene un p95 pegado a ≈ 2 s: es su propio timeout, no
   su velocidad real. Cuando una latencia "se aplana" en un valor redondo, suele ser un timeout.
3. El log de B confirma `No se pudo obtener el cultivo id=1 desde catalogo-cultivos dentro de
   2000 ms`.
4. `curl` directo al catálogo mide 3.28 s: la causa está en A.
5. El gauge `catalogocultivos_delay_artificial_ms` y la variable de entorno muestran el origen.

Un matiz importante: las probes están verdes porque miden `/health`, no `/api/*`. La
disponibilidad que ven las probes y la que ve el negocio no son la misma.

## Comando de resolución

```bash
kubectl -n agro set env deployment/catalogo-cultivos RESPONSE_DELAY_MS=0
kubectl -n agro rollout status deployment/catalogo-cultivos
curl -s -o /dev/null -w "%{time_total}s\n" http://localhost:30081/api/cultivos/1   # 0.005 s
```

Tras el cambio la respuesta del catálogo bajó de 3.28 s a 5 ms. Las alertas tardaron unos 5
minutos en apagarse, porque `CosechaSinRegistrarEnAcopio` usa una ventana `increase(...[5m])`.

## Lección técnica

1. **Latencia es disponibilidad.** Un servicio que responde, pero más lento que el timeout de
   su cliente, está caído para ese cliente. Los timeouts de los consumidores definen cuánta
   lentitud es aceptable.
2. **Una dependencia lenta se propaga hacia arriba.** B no tiene ningún defecto, y aun así
   pierde el 100% de sus registros. Por eso monitoreamos el **p95**: el promedio y el p50 de
   B ocultan la mitad de la historia.
3. **Las probes no ven la lentitud.** Una readiness que consultara a A habría sacado a B del
   Service y empeorado el incidente. La lentitud se detecta con métricas y alertas, no con
   probes.
4. **Mitigaciones de diseño:** caché de los cultivos (cambian muy poco) en B, un circuit
   breaker para fallar rápido en vez de esperar 2 s por request, reintentos con backoff y
   timeouts decrecientes a lo largo de la cadena de llamadas.
5. **El orden de los timeouts importa:** el del cliente final debe ser mayor que la suma de
   los de las capas intermedias, o la primera capa se rinde antes de que las demás terminen.
