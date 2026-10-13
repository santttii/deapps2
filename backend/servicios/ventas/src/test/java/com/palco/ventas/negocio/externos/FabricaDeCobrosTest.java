package com.palco.ventas.negocio.externos;

import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/** Quién cobra para cada valor de la configuración. No necesita servidor. */
class FabricaDeCobrosTest {

    private final Cobros pagos = new ClienteDePagos();
    private final Cobros simulador = new CobrosFake();

    @Test
    void conPagosCobraElServicioDePagos() {
        assertSame(pagos, FabricaDeCobros.elegir("pagos", pagos, simulador));
    }

    @Test
    void conFakeCobraElSimuladorLocal() {
        assertSame(simulador, FabricaDeCobros.elegir("fake", pagos, simulador));
    }

    @Test
    void noImportanLasMayusculasNiLosEspaciosDeLaVariable() {
        assertSame(simulador, FabricaDeCobros.elegir(" Fake ", pagos, simulador));
        assertSame(pagos, FabricaDeCobros.elegir(" PAGOS ", pagos, simulador));
    }

    @Test
    void unValorQueNoExisteNoCaeAlSimulador() {
        // Un valor mal escrito no puede dejar a Ventas cobrando de mentira sin que nadie lo pida.
        assertSame(pagos, FabricaDeCobros.elegir("fak", pagos, simulador));
        assertSame(pagos, FabricaDeCobros.elegir("", pagos, simulador));
    }
}
