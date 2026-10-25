package com.cesia.gestioncosechas.model;

public class CultivoDTO {

    private Long id;
    private String nombre;
    private String tipo;
    private String temporada;
    private double rendimientoEsperadoKgHa;
    private double precioMercadoKg;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public String getTipo() {
        return tipo;
    }

    public void setTipo(String tipo) {
        this.tipo = tipo;
    }

    public String getTemporada() {
        return temporada;
    }

    public void setTemporada(String temporada) {
        this.temporada = temporada;
    }

    public double getRendimientoEsperadoKgHa() {
        return rendimientoEsperadoKgHa;
    }

    public void setRendimientoEsperadoKgHa(double rendimientoEsperadoKgHa) {
        this.rendimientoEsperadoKgHa = rendimientoEsperadoKgHa;
    }

    public double getPrecioMercadoKg() {
        return precioMercadoKg;
    }

    public void setPrecioMercadoKg(double precioMercadoKg) {
        this.precioMercadoKg = precioMercadoKg;
    }
}
