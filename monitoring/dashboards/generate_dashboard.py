import json, sys

DS = {"type": "prometheus", "uid": "prometheus"}
API = 'namespace="agro",uri=~"/api/.*"'
panels, pid = [], [0]


def nid():
    pid[0] += 1
    return pid[0]


def row(title, y):
    panels.append({"type": "row", "title": title, "id": nid(), "collapsed": False,
                   "gridPos": {"h": 1, "w": 24, "x": 0, "y": y}, "panels": []})


def target(expr, legend="", ref="A"):
    return {"datasource": DS, "expr": expr, "legendFormat": legend, "refId": ref, "range": True}


def stat(title, expr, x, y, w=4, h=4, unit="none", steps=None, mappings=None, desc="", decimals=None):
    defaults = {"unit": unit, "color": {"mode": "thresholds"},
                "thresholds": {"mode": "absolute", "steps": steps or [{"color": "green", "value": None}]},
                "mappings": mappings or []}
    if decimals is not None:
        defaults["decimals"] = decimals
    panels.append({"type": "stat", "title": title, "description": desc, "id": nid(), "datasource": DS,
                   "gridPos": {"h": h, "w": w, "x": x, "y": y},
                   "fieldConfig": {"defaults": defaults, "overrides": []},
                   "options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                               "colorMode": "background", "graphMode": "none", "textMode": "value",
                               "justifyMode": "center", "orientation": "auto"},
                   "targets": [target(expr)]})


def ts(title, targets, x, y, w=12, h=8, unit="none", desc="", stack=False):
    panels.append({"type": "timeseries", "title": title, "description": desc, "id": nid(), "datasource": DS,
                   "gridPos": {"h": h, "w": w, "x": x, "y": y},
                   "fieldConfig": {"defaults": {"unit": unit, "custom": {
                       "drawStyle": "line", "lineWidth": 2, "fillOpacity": 15, "showPoints": "never",
                       "spanNulls": True, "stacking": {"mode": "normal" if stack else "none", "group": "A"}}},
                       "overrides": []},
                   "options": {"legend": {"displayMode": "table", "placement": "bottom", "calcs": ["lastNotNull", "max"]},
                               "tooltip": {"mode": "multi", "sort": "desc"}},
                   "targets": [target(e, l, chr(65 + i)) for i, (e, l) in enumerate(targets)]})


UPDOWN = [{"type": "value", "options": {"0": {"text": "CAIDO", "color": "red"},
                                         "1": {"text": "UP", "color": "green"}}}]
RED_GREEN = [{"color": "red", "value": None}, {"color": "green", "value": 1}]

# --- Estado de servicios ------------------------------------------------------
row("Estado de servicios", 0)
stat("catalogo-cultivos", 'sum(up{namespace="agro",job="catalogo-cultivos"}) or vector(0)', 0, 1,
     steps=RED_GREEN, mappings=UPDOWN, desc="Instancias scrapeadas con exito (up).")
stat("gestion-cosechas", 'sum(up{namespace="agro",job="gestion-cosechas"}) or vector(0)', 4, 1,
     steps=RED_GREEN, mappings=UPDOWN, desc="Instancias scrapeadas con exito (up).")
stat("Reinicios de pods (1h)", 'sum(increase(kube_pod_container_status_restarts_total{namespace="agro"}[1h])) or vector(0)',
     8, 1, decimals=0, steps=[{"color": "green", "value": None}, {"color": "orange", "value": 1}, {"color": "red", "value": 3}],
     desc="Un numero creciente indica CrashLoopBackOff o liveness fallando.")
stat("Alertas agro FIRING", 'count(ALERTS{alertstate="firing",sistema="agro"}) or vector(0)', 12, 1, decimals=0,
     steps=[{"color": "green", "value": None}, {"color": "red", "value": 1}])
stat("Cultivos en catalogo", 'sum(catalogocultivos_cultivos_registrados_actual{namespace="agro"})', 16, 1, decimals=0,
     steps=[{"color": "red", "value": None}, {"color": "orange", "value": 1}, {"color": "green", "value": 3}],
     desc="Gauge: cultivos maestros disponibles. Si baja, las cosechas de ese cultivo se rechazan.")
stat("Delay artificial catalogo", 'max(catalogocultivos_delay_artificial_ms{namespace="agro"})', 20, 1, unit="ms",
     steps=[{"color": "green", "value": None}, {"color": "orange", "value": 500}, {"color": "red", "value": 2000}],
     desc="Valor de RESPONSE_DELAY_MS. Si supera el timeout de gestion-cosechas (2000 ms) hay timeouts.")

# --- Trafico y latencia -------------------------------------------------------
row("Trafico y latencia", 5)
ts("Tasa de requests por servicio", [(f'sum by (job) (rate(http_server_requests_seconds_count{{{API}}}[1m]))', "{{job}}")],
   0, 6, unit="reqps")
ts("Latencia por servicio (p50 / p95)", [
    (f'histogram_quantile(0.95, sum by (job, le) (rate(http_server_requests_seconds_bucket{{{API}}}[1m])))', "p95 {{job}}"),
    (f'histogram_quantile(0.50, sum by (job, le) (rate(http_server_requests_seconds_bucket{{{API}}}[1m])))', "p50 {{job}}")],
   12, 6, unit="s", desc="Calculado desde el histograma http_server_requests_seconds.")
ts("Respuestas por codigo HTTP", [(f'sum by (job, status) (rate(http_server_requests_seconds_count{{{API}}}[1m]))',
                                   "{{job}} {{status}}")], 0, 14, unit="reqps", stack=True)
ts("Errores / timeouts gestion-cosechas -> catalogo",
   [('sum(rate(gestioncosechas_catalogo_errores_total{namespace="agro"}[1m])) or vector(0)', "errores/s")],
   12, 14, unit="short", desc="gestioncosechas_catalogo_errores_total: timeout o fallo de conexion al consultar el catalogo.")

# --- Negocio agro ---------------------------------------------------------------
row("Negocio agro", 22)
stat("Cosechas registradas (en memoria)", 'sum(gestioncosechas_cosechas_registradas_actual{namespace="agro"})', 0, 23, w=6,
     decimals=0, steps=[{"color": "blue", "value": None}])
stat("Ingreso estimado registrado (rango)",
     'sum(increase(gestioncosechas_ingreso_estimado_soles_total{namespace="agro"}[$__range])) or vector(0)', 6, 23, w=6,
     unit="prefix:S/ ", decimals=0, steps=[{"color": "green", "value": None}],
     desc="Suma de cantidadKg x precioMercadoKg de las cosechas registradas en el rango seleccionado.")
stat("Kg de cosecha SIN registrar (rango)",
     'sum(increase(gestioncosechas_kg_rechazados_total{namespace="agro"}[$__range])) or vector(0)', 12, 23, w=6,
     unit="masskg", decimals=0, steps=[{"color": "green", "value": None}, {"color": "orange", "value": 1000}, {"color": "red", "value": 5000}],
     desc="Producto que llego al acopio pero no quedo registrado: perdida directa de negocio.")
stat("% cosechas por debajo del rendimiento (10m)",
     'sum(increase(gestioncosechas_cosechas_por_estado_total{namespace="agro",estado="POR_DEBAJO"}[10m])) / sum(increase(gestioncosechas_cosechas_por_estado_total{namespace="agro"}[10m]))',
     18, 23, w=6, unit="percentunit", decimals=1,
     steps=[{"color": "green", "value": None}, {"color": "orange", "value": 0.3}, {"color": "red", "value": 0.5}])
ts("Cosechas por estado de rendimiento (por minuto)",
   [('sum by (estado) (rate(gestioncosechas_cosechas_por_estado_total{namespace="agro"}[2m])) * 60', "{{estado}}")],
   0, 27, unit="short", stack=True)
ts("Kg de cosecha rechazados por motivo (por minuto)",
   [('sum by (motivo) (rate(gestioncosechas_kg_rechazados_total{namespace="agro"}[2m])) * 60', "{{motivo}}")],
   12, 27, unit="masskg", stack=True)

# --- Recursos -------------------------------------------------------------------
row("Recursos de los pods", 35)
ts("CPU por pod", [('max by (pod) (rate(container_cpu_usage_seconds_total{namespace="agro",pod!=""}[1m]))', "{{pod}}")],
   0, 36, unit="cores", desc="Limite configurado: 1 core por pod (request 250m). max by (pod): el cgroup del pod incluye a sus contenedores.")
ts("Memoria por pod", [('max by (pod) (container_memory_working_set_bytes{namespace="agro",pod!=""})', "{{pod}}")],
   12, 36, unit="bytes", desc="Limite configurado: 512Mi por pod.")

dashboard = {
    "uid": "agro-monitoring", "title": "Agro Monitoring - Cultivos y Cosechas",
    "tags": ["agro", "kubernetes", "spring-boot"], "timezone": "browser", "schemaVersion": 39, "version": 1,
    "editable": True, "refresh": "10s", "time": {"from": "now-30m", "to": "now"},
    "annotations": {"list": []}, "templating": {"list": []}, "panels": panels,
}
json.dump(dashboard, open(sys.argv[1], "w", encoding="utf-8"), indent=2, ensure_ascii=False)
print(f"{len([p for p in panels if p['type'] != 'row'])} paneles")
