package com.cesia.gestioncosechas.model;

import java.time.LocalDate;

public class Cosecha {

    private Long id;
    private Long cultivoId;
    private String nombreCultivo;
    private double hectareasCosechadas;
    private double cantidadKg;
    private LocalDate fecha;
    private String estadoRendimiento; // CUMPLIO, POR_DEBAJO, SIN_VALIDAR
    private double ingresoEstimado;

    public Cosecha() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCultivoId() {
        return cultivoId;
    }

    public void setCultivoId(Long cultivoId) {
        this.cultivoId = cultivoId;
    }

    public String getNombreCultivo() {
        return nombreCultivo;
    }

    public void setNombreCultivo(String nombreCultivo) {
        this.nombreCultivo = nombreCultivo;
    }

    public double getHectareasCosechadas() {
        return hectareasCosechadas;
    }

    public void setHectareasCosechadas(double hectareasCosechadas) {
        this.hectareasCosechadas = hectareasCosechadas;
    }

    public double getCantidadKg() {
        return cantidadKg;
    }

    public void setCantidadKg(double cantidadKg) {
        this.cantidadKg = cantidadKg;
    }

    public LocalDate getFecha() {
        return fecha;
    }

    public void setFecha(LocalDate fecha) {
        this.fecha = fecha;
    }

    public String getEstadoRendimiento() {
        return estadoRendimiento;
    }

    public void setEstadoRendimiento(String estadoRendimiento) {
        this.estadoRendimiento = estadoRendimiento;
    }

    public double getIngresoEstimado() {
        return ingresoEstimado;
    }

    public void setIngresoEstimado(double ingresoEstimado) {
        this.ingresoEstimado = ingresoEstimado;
    }
}
