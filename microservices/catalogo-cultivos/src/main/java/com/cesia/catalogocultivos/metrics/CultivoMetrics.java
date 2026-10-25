package com.cesia.catalogocultivos.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Centraliza las metricas de negocio del servicio catalogo-cultivos.
 * Cumple con los 3 tipos fundamentales que pide la tarea: Counter, Gauge y Timer.
 */
@Component
public class CultivoMetrics {

    private final Counter consultasCounter;
    private final Counter creacionesCounter;
    private final AtomicInteger cultivosRegistrados = new AtomicInteger(0);
    private final AtomicInteger delayArtificialMs = new AtomicInteger(0);
    private final Timer procesamientoTimer;

    public CultivoMetrics(MeterRegistry registry) {
        this.consultasCounter = Counter.builder("catalogocultivos_consultas_total")
                .description("Numero total de consultas recibidas por el catalogo de cultivos")
                .register(registry);

        this.creacionesCounter = Counter.builder("catalogocultivos_cultivos_creados_total")
                .description("Numero total de cultivos creados en el catalogo")
                .register(registry);

        registry.gauge("catalogocultivos_cultivos_registrados_actual", cultivosRegistrados);

        registry.gauge("catalogocultivos_delay_artificial_ms", delayArtificialMs);

        this.procesamientoTimer = Timer.builder("catalogocultivos_procesamiento_timer")
                .description("Tiempo de procesamiento de operaciones sobre el catalogo")
                .publishPercentileHistogram()
                .register(registry);
    }

    public void registrarConsulta() {
        consultasCounter.increment();
    }

    public void registrarCreacion() {
        creacionesCounter.increment();
    }

    public void actualizarCultivosRegistrados(int total) {
        cultivosRegistrados.set(total);
    }

    public void actualizarDelayArtificial(int delayMs) {
        delayArtificialMs.set(delayMs);
    }

    public Timer getProcesamientoTimer() {
        return procesamientoTimer;
    }
}
