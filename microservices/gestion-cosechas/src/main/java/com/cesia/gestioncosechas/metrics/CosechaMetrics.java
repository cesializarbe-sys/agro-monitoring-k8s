package com.cesia.gestioncosechas.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Centraliza las metricas de negocio del servicio gestion-cosechas.
 * Cumple con los 3 tipos fundamentales que pide la tarea: Counter, Gauge y Timer.
 */
@Component
public class CosechaMetrics {

    public static final String MOTIVO_CATALOGO_NO_DISPONIBLE = "catalogo_no_disponible";
    public static final String MOTIVO_CULTIVO_INEXISTENTE = "cultivo_inexistente";

    private final MeterRegistry registry;
    private final Counter cosechasRegistradasCounter;
    private final Counter erroresCatalogoCounter;
    private final Counter ingresoEstimadoCounter;
    private final AtomicInteger cosechasRegistradas = new AtomicInteger(0);
    private final Timer procesamientoTimer;

    public CosechaMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.cosechasRegistradasCounter = Counter.builder("gestioncosechas_cosechas_registradas_total")
                .description("Numero total de cosechas registradas exitosamente")
                .register(registry);

        this.erroresCatalogoCounter = Counter.builder("gestioncosechas_catalogo_errores_total")
                .description("Numero de veces que la consulta al catalogo-cultivos fallo o hizo timeout")
                .register(registry);

        this.ingresoEstimadoCounter = Counter.builder("gestioncosechas_ingreso_estimado_soles_total")
                .description("Suma del ingreso estimado (S/) de las cosechas registradas")
                .register(registry);

        registry.gauge("gestioncosechas_cosechas_registradas_actual", cosechasRegistradas);

        this.procesamientoTimer = Timer.builder("gestioncosechas_procesamiento_timer")
                .description("Tiempo de procesamiento de una solicitud de registro de cosecha, incluye la consulta al catalogo")
                .publishPercentileHistogram()
                .register(registry);
    }

    public void registrarCosechaExitosa(int totalActual, String estadoRendimiento, double ingresoEstimado) {
        cosechasRegistradasCounter.increment();
        cosechasRegistradas.set(totalActual);
        ingresoEstimadoCounter.increment(ingresoEstimado);
        Counter.builder("gestioncosechas_cosechas_por_estado_total")
                .description("Cosechas registradas segun cumplieron o no el rendimiento esperado")
                .tag("estado", estadoRendimiento)
                .register(registry)
                .increment();
    }

    /**
     * Una cosecha rechazada es una perdida de negocio: el productor entrego
     * producto pero no quedo registrado (no se paga, no se factura).
     */
    public void registrarCosechaRechazada(String motivo, double cantidadKg) {
        Counter.builder("gestioncosechas_cosechas_rechazadas_total")
                .description("Cosechas que no pudieron registrarse, por motivo")
                .tag("motivo", motivo)
                .register(registry)
                .increment();
        Counter.builder("gestioncosechas_kg_rechazados_total")
                .description("Kilogramos de cosecha que no pudieron registrarse, por motivo")
                .tag("motivo", motivo)
                .register(registry)
                .increment(cantidadKg);
    }

    public void registrarErrorCatalogo() {
        erroresCatalogoCounter.increment();
    }

    public void actualizarCosechasRegistradas(int total) {
        cosechasRegistradas.set(total);
    }

    public Timer getProcesamientoTimer() {
        return procesamientoTimer;
    }
}
