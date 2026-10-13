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
 *
 * Cada excepción lleva un "tipo": el nombre corto del problema, que la capa
 * REST publica como {@code "type": "/problemas/<tipo>"}. Sirve para que un
 * cliente distinga dos errores con el mismo código HTTP (un 409 por cupo
 * agotado de un 409 por reserva vencida) sin leer el texto.
 */
public final class ErroresDeNegocio {

    private ErroresDeNegocio() {
    }

    /**
     * Lo que tienen en común todas: el mensaje para el usuario y el tipo de problema.
     * La anotación se hereda, así que cubre también a una subclase que se olvide de ponerla.
     */
    @ApplicationException(rollback = true)
    public abstract static class ErrorDeNegocio extends RuntimeException {
        private final String tipo;

        protected ErrorDeNegocio(String tipo, String mensaje) {
            super(mensaje);
            this.tipo = tipo;
        }

        /** Nombre corto del problema, sin prefijo: "cupo-agotado". */
        public String tipo() {
            return tipo;
        }
    }

    /** Datos que pasan la validación de formato pero no tienen sentido → 400. */
    @ApplicationException(rollback = true)
    public static class DatosInvalidos extends ErrorDeNegocio {
        private final Map<String, String> campos;

        public DatosInvalidos(String campo, String mensaje) {
            super("datos-invalidos", mensaje);
            this.campos = Map.of(campo, mensaje);
        }

        public Map<String, String> campos() {
            return campos;
        }
    }

    /** Credenciales incorrectas o sesión inválida → 401. */
    @ApplicationException(rollback = true)
    public static class NoAutenticado extends ErrorDeNegocio {
        public NoAutenticado(String mensaje) {
            super("no-autenticado", mensaje);
        }
    }

    /** La pasarela rechazó el cobro (fondos, tarjeta…) → 402. */
    @ApplicationException(rollback = true)
    public static class PagoRechazado extends ErrorDeNegocio {
        public PagoRechazado(String mensaje) {
            super("pago-rechazado", mensaje);
        }
    }

    /** El usuario está identificado pero no puede hacer esto → 403. */
    @ApplicationException(rollback = true)
    public static class SinPermiso extends ErrorDeNegocio {
        public SinPermiso(String mensaje) {
            this("sin-permiso", mensaje);
        }

        public SinPermiso(String tipo, String mensaje) {
            super(tipo, mensaje);
        }
    }

    /** No existe lo que se pidió → 404. */
    @ApplicationException(rollback = true)
    public static class NoEncontrado extends ErrorDeNegocio {
        public NoEncontrado(String mensaje) {
            super("no-encontrado", mensaje);
        }
    }

    /** La operación choca con el estado actual (sin cupo, ya usada…) → 409. */
    @ApplicationException(rollback = true)
    public static class Conflicto extends ErrorDeNegocio {
        public Conflicto(String mensaje) {
            this("conflicto", mensaje);
        }

        public Conflicto(String tipo, String mensaje) {
            super(tipo, mensaje);
        }
    }

    /** El pedido está bien formado pero rompe una regla de negocio (máximo de entradas, DNI repetido…) → 422. */
    @ApplicationException(rollback = true)
    public static class ReglaIncumplida extends ErrorDeNegocio {
        public ReglaIncumplida(String mensaje) {
            this("regla-incumplida", mensaje);
        }

        public ReglaIncumplida(String tipo, String mensaje) {
            super(tipo, mensaje);
        }
    }

    /** Otro servicio o la pasarela no respondió a tiempo → 504. */
    @ApplicationException(rollback = true)
    public static class SinRespuesta extends ErrorDeNegocio {
        public SinRespuesta(String mensaje) {
            super("sin-respuesta", mensaje);
        }
    }
}
