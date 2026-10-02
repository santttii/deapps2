package com.palco.common;

import java.util.Map;

import jakarta.ejb.ApplicationException;

/**
 * Excepciones que lanzan los Session Beans cuando se rompe una regla de
 * negocio. La capa REST las traduce a un código HTTP (ver
 * {@code ManejadoresDeErrores}); así el negocio no depende de JAX-RS.
 *
 * {@code @ApplicationException(rollback = true)}: el contenedor deshace la
 * transacción y la excepción llega tal cual al llamador (sin envolverla).
 */
public final class ErroresDeNegocio {

    private ErroresDeNegocio() {
    }

    /** Credenciales incorrectas o sesión inválida → 401. */
    @ApplicationException(rollback = true)
    public static class NoAutenticado extends RuntimeException {
        public NoAutenticado(String mensaje) {
            super(mensaje);
        }
    }

    /** No existe lo que se pidió → 404. */
    @ApplicationException(rollback = true)
    public static class NoEncontrado extends RuntimeException {
        public NoEncontrado(String mensaje) {
            super(mensaje);
        }
    }

    /** La operación choca con el estado actual (sin cupo, ya usada…) → 409. */
    @ApplicationException(rollback = true)
    public static class Conflicto extends RuntimeException {
        public Conflicto(String mensaje) {
            super(mensaje);
        }
    }

    /** Datos que pasan la validación de formato pero no tienen sentido → 400. */
    @ApplicationException(rollback = true)
    public static class DatosInvalidos extends RuntimeException {
        private final Map<String, String> campos;

        public DatosInvalidos(String campo, String mensaje) {
            super(mensaje);
            this.campos = Map.of(campo, mensaje);
        }

        public Map<String, String> campos() {
            return campos;
        }
    }
}
