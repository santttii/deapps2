package com.palco.validacion.dominio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.palco.validacion.dominio.PuertaAsignada.Clave;

/**
 * La clave de una asignación de puerta, con la que el repositorio la busca
 * ({@code em.find}). Es el par evento y email: dos claves con el mismo par son
 * la misma asignación, y basta que cambie uno de los dos para que sea otra.
 * JPA pide que la clase de la clave compare por valor, con {@code equals} y
 * {@code hashCode}.
 */
class PuertaAsignadaTest {

    private static final Clave PUERTA_UNO_EN_EV1 = new Clave("ev-1", "puerta1@palco.com");

    @Test
    void elMismoEventoYElMismoEmailSonLaMismaClave() {
        Clave otraVez = new Clave("ev-1", "puerta1@palco.com");

        assertEquals(PUERTA_UNO_EN_EV1, otraVez);
        assertEquals(PUERTA_UNO_EN_EV1.hashCode(), otraVez.hashCode());
        // Asignar dos veces la misma cuenta no agrega nada
        assertEquals(1, new HashSet<>(List.of(PUERTA_UNO_EN_EV1, otraVez)).size());
    }

    @Test
    void otraCuentaEnElMismoEventoEsOtraClave() {
        Clave otraCuenta = new Clave("ev-1", "puerta2@palco.com");

        assertNotEquals(PUERTA_UNO_EN_EV1, otraCuenta);
        // El hash también depende del email: si no, todas las puertas de un evento caerían en el mismo lugar
        assertNotEquals(PUERTA_UNO_EN_EV1.hashCode(), otraCuenta.hashCode());
    }

    @Test
    void laMismaCuentaEnOtroEventoEsOtraClave() {
        Clave otroEvento = new Clave("ev-2", "puerta1@palco.com");

        assertNotEquals(PUERTA_UNO_EN_EV1, otroEvento);
        assertNotEquals(PUERTA_UNO_EN_EV1.hashCode(), otroEvento.hashCode());
    }

    @Test
    void noEsIgualANadaQueNoSeaUnaClave() {
        assertFalse(PUERTA_UNO_EN_EV1.equals(null));
        assertFalse(PUERTA_UNO_EN_EV1.equals("ev-1 puerta1@palco.com"));
    }
}
