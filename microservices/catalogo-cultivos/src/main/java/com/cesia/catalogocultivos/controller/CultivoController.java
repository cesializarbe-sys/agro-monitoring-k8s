package com.cesia.catalogocultivos.controller;

import com.cesia.catalogocultivos.metrics.CultivoMetrics;
import com.cesia.catalogocultivos.model.Cultivo;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/cultivos")
public class CultivoController {

    private final Map<Long, Cultivo> cultivos = new ConcurrentHashMap<>();
    private final AtomicLong secuenciaId = new AtomicLong(1);
    private final CultivoMetrics metrics;

    @Value("${app.delay-ms:0}")
    private int delayArtificialMs;

    public CultivoController(CultivoMetrics metrics) {
        this.metrics = metrics;
        sembrarDatosIniciales();
    }

    @PostConstruct
    public void inicializarDelay() {
        metrics.actualizarDelayArtificial(delayArtificialMs);
    }

    private void aplicarDelayArtificial() {
        if (delayArtificialMs > 0) {
            try {
                Thread.sleep(delayArtificialMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void sembrarDatosIniciales() {
        crearInterno(new Cultivo(null, "Papa Huayro", "Tuberculo", "Campaña grande", 18000, 1.8));
        crearInterno(new Cultivo(null, "Maiz Amarillo Duro", "Cereal", "Campaña chica", 9000, 0.95));
        crearInterno(new Cultivo(null, "Palta Hass", "Frutal", "Todo el año", 12000, 4.2));
    }

    @GetMapping
    public ResponseEntity<Collection<Cultivo>> listar() {
        return metrics.getProcesamientoTimer().record(() -> {
            aplicarDelayArtificial();
            metrics.registrarConsulta();
            return ResponseEntity.ok(cultivos.values());
        });
    }

    @GetMapping("/{id}")
    public ResponseEntity<Cultivo> obtenerPorId(@PathVariable Long id) {
        return metrics.getProcesamientoTimer().record(() -> {
            aplicarDelayArtificial();
            metrics.registrarConsulta();
            Cultivo cultivo = cultivos.get(id);
            if (cultivo == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(cultivo);
        });
    }

    @PostMapping
    public ResponseEntity<Cultivo> crear(@RequestBody Cultivo nuevo) {
        Cultivo creado = metrics.getProcesamientoTimer().record(() -> crearInterno(nuevo));
        metrics.registrarCreacion();
        return ResponseEntity.status(HttpStatus.CREATED).body(creado);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Cultivo> actualizar(@PathVariable Long id, @RequestBody Cultivo datos) {
        return metrics.getProcesamientoTimer().record(() -> {
            Cultivo existente = cultivos.get(id);
            if (existente == null) {
                return ResponseEntity.notFound().build();
            }
            existente.setNombre(datos.getNombre());
            existente.setTipo(datos.getTipo());
            existente.setTemporada(datos.getTemporada());
            existente.setRendimientoEsperadoKgHa(datos.getRendimientoEsperadoKgHa());
            existente.setPrecioMercadoKg(datos.getPrecioMercadoKg());
            return ResponseEntity.ok(existente);
        });
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> eliminar(@PathVariable Long id) {
        return metrics.getProcesamientoTimer().record(() -> {
            Cultivo eliminado = cultivos.remove(id);
            metrics.actualizarCultivosRegistrados(cultivos.size());
            if (eliminado == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.noContent().build();
        });
    }

    private Cultivo crearInterno(Cultivo nuevo) {
        Long id = secuenciaId.getAndIncrement();
        nuevo.setId(id);
        cultivos.put(id, nuevo);
        metrics.actualizarCultivosRegistrados(cultivos.size());
        return nuevo;
    }
}
