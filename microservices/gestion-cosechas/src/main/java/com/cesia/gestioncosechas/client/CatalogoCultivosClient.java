package com.cesia.gestioncosechas.client;

import com.cesia.gestioncosechas.metrics.CosechaMetrics;
import com.cesia.gestioncosechas.model.CultivoDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Optional;

/**
 * Encapsula la comunicacion del Servicio B hacia el Servicio A.
 * Si el Servicio A no responde dentro del timeout configurado,
 * el error se registra (log + metrica) y se responde de forma controlada
 * devolviendo Optional.empty() en lugar de propagar la excepcion.
 */
@Component
public class CatalogoCultivosClient {

    private static final Logger log = LoggerFactory.getLogger(CatalogoCultivosClient.class);

    private final WebClient webClient;
    private final CosechaMetrics metrics;

    @Value("${app.catalogo-cultivos.timeout-ms}")
    private int timeoutMs;

    public CatalogoCultivosClient(WebClient catalogoCultivosWebClient, CosechaMetrics metrics) {
        this.webClient = catalogoCultivosWebClient;
        this.metrics = metrics;
    }

    /**
     * La primera llamada de WebClient carga Netty, el resolver DNS y Jackson; con
     * 500m de CPU eso supera el timeout de 2 s y la primera cosecha se rechazaba.
     * Este listener corre antes de que el pod se marque Ready, asi que el trafico
     * real llega con el cliente ya caliente. Cada intento fallido tambien calienta
     * el codigo, por eso se reintenta. Los errores aqui no cuentan en metricas.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void precalentar() {
        for (int intento = 1; intento <= 10; intento++) {
            try {
                webClient.get()
                        .uri("/api/cultivos/{id}", 1)
                        .retrieve()
                        .bodyToMono(CultivoDTO.class)
                        .block(Duration.ofSeconds(5));
                log.info("WebClient hacia catalogo-cultivos precalentado (intento {})", intento);
                return;
            } catch (Exception e) {
                log.warn("Precalentamiento intento {} fallido: {}", intento, e.toString());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public Optional<CultivoDTO> obtenerCultivo(Long cultivoId) {
        try {
            CultivoDTO cultivo = webClient.get()
                    .uri("/api/cultivos/{id}", cultivoId)
                    .retrieve()
                    .bodyToMono(CultivoDTO.class)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .block();
            return Optional.ofNullable(cultivo);
        } catch (WebClientResponseException.NotFound e) {
            log.warn("El cultivo id={} no existe en catalogo-cultivos", cultivoId);
            throw new CultivoNoEncontradoException(cultivoId);
        } catch (Exception e) {
            log.error("No se pudo obtener el cultivo id={} desde catalogo-cultivos dentro de {} ms. Causa: {}",
                    cultivoId, timeoutMs, e.toString());
            metrics.registrarErrorCatalogo();
            return Optional.empty();
        }
    }
}
