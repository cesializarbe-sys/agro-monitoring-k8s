package com.cesia.gestioncosechas.controller;

import com.cesia.gestioncosechas.client.CatalogoCultivosClient;
import com.cesia.gestioncosechas.client.CultivoNoEncontradoException;
import com.cesia.gestioncosechas.metrics.CosechaMetrics;
import com.cesia.gestioncosechas.model.Cosecha;
import com.cesia.gestioncosechas.model.CultivoDTO;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/cosechas")
public class CosechaController {

    private final Map<Long, Cosecha> cosechas = new ConcurrentHashMap<>();
    private final AtomicLong secuenciaId = new AtomicLong(1);
    private final CatalogoCultivosClient catalogoCultivosClient;
    private final CosechaMetrics metrics;

    public CosechaController(CatalogoCultivosClient catalogoCultivosClient, CosechaMetrics metrics) {
        this.catalogoCultivosClient = catalogoCultivosClient;
        this.metrics = metrics;
    }

    @GetMapping
    public ResponseEntity<Collection<Cosecha>> listar() {
        return metrics.getProcesamientoTimer().record(() -> ResponseEntity.ok(cosechas.values()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Cosecha> obtenerPorId(@PathVariable Long id) {
        return metrics.getProcesamientoTimer().record(() -> {
            Cosecha cosecha = cosechas.get(id);
            if (cosecha == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(cosecha);
        });
    }

    /**
     * Registra una cosecha. Antes de guardarla, consulta al Servicio A (catalogo-cultivos)
     * para validar que el cultivo exista y para calcular si la cosecha cumplio
     * el rendimiento esperado. Esta es la decision de negocio real que depende
     * de la comunicacion entre servicios.
     */
    @PostMapping
    public ResponseEntity<?> registrar(@RequestBody Cosecha nueva) {
        return metrics.getProcesamientoTimer().record(() -> {
            Optional<CultivoDTO> cultivoOpt;
            try {
                cultivoOpt = catalogoCultivosClient.obtenerCultivo(nueva.getCultivoId());
            } catch (CultivoNoEncontradoException e) {
                metrics.registrarCosechaRechazada(CosechaMetrics.MOTIVO_CULTIVO_INEXISTENTE, nueva.getCantidadKg());
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of(
                                "error", e.getMessage(),
                                "cultivoId", nueva.getCultivoId()
                        ));
            }

            if (cultivoOpt.isEmpty()) {
                metrics.registrarCosechaRechazada(CosechaMetrics.MOTIVO_CATALOGO_NO_DISPONIBLE, nueva.getCantidadKg());
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of(
                                "error", "No se pudo validar el cultivo contra catalogo-cultivos",
                                "cultivoId", nueva.getCultivoId()
                        ));
            }

            CultivoDTO cultivo = cultivoOpt.get();
            double rendimientoReal = nueva.getHectareasCosechadas() > 0
                    ? nueva.getCantidadKg() / nueva.getHectareasCosechadas()
                    : 0;

            nueva.setId(secuenciaId.getAndIncrement());
            nueva.setNombreCultivo(cultivo.getNombre());
            nueva.setFecha(nueva.getFecha() != null ? nueva.getFecha() : LocalDate.now());
            nueva.setEstadoRendimiento(
                    rendimientoReal >= cultivo.getRendimientoEsperadoKgHa() ? "CUMPLIO" : "POR_DEBAJO"
            );
            nueva.setIngresoEstimado(nueva.getCantidadKg() * cultivo.getPrecioMercadoKg());

            cosechas.put(nueva.getId(), nueva);
            metrics.registrarCosechaExitosa(cosechas.size(), nueva.getEstadoRendimiento(), nueva.getIngresoEstimado());

            return ResponseEntity.status(HttpStatus.CREATED).body(nueva);
        });
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> eliminar(@PathVariable Long id) {
        return metrics.getProcesamientoTimer().record(() -> {
            Cosecha eliminada = cosechas.remove(id);
            metrics.actualizarCosechasRegistradas(cosechas.size());
            if (eliminada == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.noContent().build();
        });
    }
}
