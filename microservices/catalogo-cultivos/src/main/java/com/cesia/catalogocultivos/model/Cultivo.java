package com.cesia.catalogocultivos.model;

public class Cultivo {

    private Long id;
    private String nombre;
    private String tipo;
    private String temporada;
    private double rendimientoEsperadoKgHa;
    private double precioMercadoKg;

    public Cultivo() {
    }

    public Cultivo(Long id, String nombre, String tipo, String temporada,
                    double rendimientoEsperadoKgHa, double precioMercadoKg) {
        this.id = id;
        this.nombre = nombre;
        this.tipo = tipo;
        this.temporada = temporada;
        this.rendimientoEsperadoKgHa = rendimientoEsperadoKgHa;
        this.precioMercadoKg = precioMercadoKg;
    }

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
