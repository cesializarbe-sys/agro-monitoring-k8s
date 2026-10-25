package com.cesia.gestioncosechas.client;

/**
 * El catalogo respondio, pero el cultivo no existe (HTTP 404).
 * Es un error de negocio, no una falla de comunicacion.
 */
public class CultivoNoEncontradoException extends RuntimeException {

    public CultivoNoEncontradoException(Long cultivoId) {
        super("El cultivo id=" + cultivoId + " no existe en catalogo-cultivos");
    }
}
