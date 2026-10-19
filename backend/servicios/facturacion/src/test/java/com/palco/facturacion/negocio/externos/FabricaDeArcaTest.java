package com.palco.facturacion.negocio.externos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Lo que la fábrica decide con la configuración, sin servidor. */
class FabricaDeArcaTest {

    @AfterEach
    void sacarLaPropiedad() {
        System.clearProperty(FabricaDeArca.PARSER_INSEGURO);
    }

    @Test
    void armaElClienteSoapConLaDireccionSinElWsdlYLosTiempos() {
        Arca arca = FabricaDeArca.crear("http://arca.interno:8080/legado/arca?wsdl", 2_000, 5_000);

        ArcaSoap soap = assertInstanceOf(ArcaSoap.class, arca);
        assertEquals("http://arca.interno:8080/legado/arca", soap.direccion());
        assertEquals(2_000, soap.milisegundosConexion());
        assertEquals(5_000, soap.milisegundosLectura());
    }

    @Test
    void porDefectoVaAlArcaSimuladoDelMismoServidor() {
        assertTrue(FabricaDeArca.WSDL_POR_DEFECTO.endsWith("/legado/arca?wsdl"), FabricaDeArca.WSDL_POR_DEFECTO);
        assertEquals(2_000, FabricaDeArca.ESPERA_CONEXION_POR_DEFECTO);
        assertEquals(5_000, FabricaDeArca.ESPERA_LECTURA_POR_DEFECTO);
    }

    @Test
    void unaDireccionQueNoEsUnaUrlImpideElDespliegueConUnMensajeClaro() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> FabricaDeArca.crear("no es una url", 2_000, 5_000));

        assertTrue(error.getMessage().contains("no es una url"), error.getMessage());
    }

    @Test
    void unTiempoDeEsperaEnCeroSeRechazaPorqueSeriaEsperarParaSiempre() {
        assertThrows(IllegalStateException.class,
                () -> FabricaDeArca.crear("http://localhost:8080/legado/arca?wsdl", 0, 5_000));
        assertThrows(IllegalStateException.class,
                () -> FabricaDeArca.crear("http://localhost:8080/legado/arca?wsdl", 2_000, -1));
    }

    @Test
    void conElParserInseguroDeCxfActivadoNoSeCreaElCliente() {
        System.setProperty(FabricaDeArca.PARSER_INSEGURO, "true");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> FabricaDeArca.crear("http://localhost:8080/legado/arca?wsdl", 2_000, 5_000));

        assertTrue(error.getMessage().contains(FabricaDeArca.PARSER_INSEGURO), error.getMessage());
    }
}
