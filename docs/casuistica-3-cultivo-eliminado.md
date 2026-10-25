# Casuística 3 — Cultivo eliminado del catálogo: todo en verde, pero se pierde la cosecha

## Contexto narrativo

Estamos en pleno pico de la campaña de **palta Hass**: los camiones llegan al acopio con
fruta que ya fue cosechada y debe pesarse, registrarse, pagarse y facturarse el mismo día,
porque la palta madura y se pierde.

Un operador limpia el catálogo maestro y, creyendo que es un duplicado, **elimina la Palta
Hass** (`DELETE /api/cultivos/3`). No hay ningún error técnico: el catálogo responde con un
`204`, ambos servicios siguen sanos y ninguna probe falla. Pero desde ese instante, cada
cosecha de palta que `gestion-cosechas` intenta registrar recibe un `404` del catálogo y es
rechazada. El productor ya entregó la fruta; el sistema no la reconoce, así que **no se
registra, no se paga ni se factura**.

Esta casuística se eligió porque simula una pérdida de negocio real que ninguna métrica de
infraestructura puede ver.

## Síntoma

| Dónde | Qué se ve |
|---|---|
| `kubectl -n agro get pods` | todo Running y Ready |
| Prometheus `/targets` | ambos servicios **UP** |
| Grafana (servicios) | UP / UP en verde, 0 reinicios nuevos, p95 normal |
| Grafana (negocio) | `Cultivos en catalogo` baja de 3 a **2**, `Kg de cosecha SIN registrar` en rojo, el panel de kg rechazados muestra el motivo `cultivo_inexistente` |
| Errores B → A | **0**: el catálogo responde bien, solo dice que el cultivo no existe |
| Alertas (FIRING) | `CosechaSinRegistrarEnAcopio` (motivo `cultivo_inexistente`) y `CultivoEliminadoDelCatalogo` |
| Cliente | `POST /api/cosechas` con `cultivoId=3` → HTTP **404** `El cultivo id=3 no existe en catalogo-cultivos` |

El contraste con la casuística 1 es la clave: aquí el dashboard técnico está **todo verde** y
solo las métricas y alertas de negocio revelan la pérdida.

## Comando de activación

```bash
./load-testing/stress.sh -d 900 -m solo-palta -r 3 &   # tráfico solo de palta (cultivoId=3)
curl -X DELETE http://localhost:30081/api/cultivos/3    # HTTP 204
```

Las alertas pasaron a FIRING unos 96 segundos después de borrar el cultivo.

## Diagnóstico

```bash
# 1) ¿Es técnico? No: pods Running, targets UP, errores B->A = 0.
kubectl -n agro get pods
# 2) ¿Qué cultivos hay? Falta el id 3.
curl -s http://localhost:30081/api/cultivos
# 3) Reproducir el error con una cosecha real
curl -s -X POST http://localhost:30082/api/cosechas -H "Content-Type: application/json" \
  -d '{"cultivoId":3,"hectareasCosechadas":2,"cantidadKg":24000}'
# 4) El log de B lo dice con claridad
kubectl -n agro logs deploy/gestion-cosechas | grep "no existe"
```

Lo que lo hizo diagnosticable fue una decisión de diseño del Servicio B: **distingue un 404
(el cultivo no existe) de un fallo de comunicación (503)** y lo etiqueta en la métrica
`gestioncosechas_kg_rechazados_total{motivo=...}`. Si todo se hubiera reportado como "error al
consultar el catálogo", habríamos perseguido un problema de red inexistente.

## Comando de resolución

```bash
kubectl -n agro rollout restart deployment/catalogo-cultivos
curl -s http://localhost:30081/api/cultivos/3          # vuelve la Palta Hass con id 3
curl -s -X POST http://localhost:30082/api/cosechas -H "Content-Type: application/json" \
  -d '{"cultivoId":3,"hectareasCosechadas":2,"cantidadKg":24000}'   # HTTP 201
```

**Por qué no basta con crear la palta de nuevo:** el catálogo guarda sus datos en memoria y
asigna ids con un contador, así que `POST /api/cultivos` crearía la palta con el **id 4**. Las
cosechas siguen refiriéndose al id 3 y seguirían fallando. El reinicio del pod vuelve a
sembrar los tres cultivos con sus ids originales. En un sistema real esto sería una
restauración desde la base de datos o un "deshacer" del borrado.

## Lección técnica

1. **"Todo en verde" no significa "todo bien".** Un sistema puede estar técnicamente sano y
   perdiendo dinero. La observabilidad tiene que incluir métricas de negocio (kg sin registrar,
   ingreso estimado, cultivos disponibles) además de las de infraestructura (UP, CPU, latencia).
2. **Las alertas de negocio se expresan en unidades del negocio.** Medir kg sin registrar en 5
   minutos dice mucho más a un gerente del acopio que "tasa de errores 404 > 0.5/s".
3. **Etiquetar las métricas por causa** (`motivo`) convierte "algo falla" en "falla por esto" y
   acorta el diagnóstico de horas a minutos.
4. **Los datos maestros son críticos.** Un borrado accidental no debería ser posible o debería
   ser reversible: borrado lógico (`activo=false`), permisos, confirmación y auditoría.
5. **Un catálogo en memoria es frágil.** Sirve para la demo, pero con una base de datos y
   backups la restauración conservaría los ids y no habría que reiniciar nada.
