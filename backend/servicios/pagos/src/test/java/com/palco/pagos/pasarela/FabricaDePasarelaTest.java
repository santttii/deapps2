package com.palco.pagos.pasarela;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Qué pasarela sale para cada valor de la configuración. No necesita servidor. */
class FabricaDePasarelaTest {

    @Test
    void conFakeCreaLaPasarelaSimulada() {
        assertInstanceOf(PagosFake.class, FabricaDePasarela.crear("fake"));
    }

    @Test
    void noImportanLasMayusculasNiLosEspaciosDeLaVariable() {
        assertInstanceOf(PagosFake.class, FabricaDePasarela.crear(" Fake "));
    }

    @Test
    void unProveedorQueNoExisteEsUnErrorYNoElSimulador() {
        // "mercadopago" todavía no tiene adaptador: tiene que fallar, no cobrar de mentira sin avisar.
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> FabricaDePasarela.crear("mercadopago"));

        assertTrue(error.getMessage().contains("palco.pagos.proveedor"), error.getMessage());
        assertTrue(error.getMessage().contains("mercadopago"), error.getMessage());
    }
}
