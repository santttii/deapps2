package com.palco.ventas.datos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceUnitTransactionType;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.palco.common.datos.BaseDeDatos;
import com.palco.ventas.dominio.Entrada;
import com.palco.ventas.dominio.EstadoVenta;
import com.palco.ventas.dominio.TitularDeVenta;
import com.palco.ventas.dominio.Venta;
import com.palco.ventas.dominio.VentaItem;

/**
 * La marca de devolución de cupo pendiente, contra una base de verdad: H2 en
 * modo PostgreSQL con el esquema de las migraciones (incluida V3). Comprueba
 * que la pone el mismo UPDATE que cierra la venta, que el barrido la encuentra
 * con sus renglones y que al sacarla la venta deja de aparecer.
 */
class DevolucionPendienteEnH2Test {

    private static final LocalDateTime ABIERTA = LocalDateTime.of(2026, 10, 23, 12, 0, 0);
    private static final LocalDateTime VENCIDA = ABIERTA.plusMinutes(11);

    private EntityManagerFactory fabrica;
    private EntityManager em;
    private VentaRepositoryJpa ventas;

    @BeforeEach
    void crearBase() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
        JdbcDataSource base = new JdbcDataSource();
        base.setURL(url);
        BaseDeDatos.migrar(base, "ventas");

        fabrica = new PersistenceConfiguration("ventas-h2")
                .transactionType(PersistenceUnitTransactionType.RESOURCE_LOCAL)
                .managedClass(Venta.class)
                .managedClass(VentaItem.class)
                .managedClass(Entrada.class)
                .managedClass(TitularDeVenta.class)
                .property(PersistenceConfiguration.JDBC_URL, url)
                .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "none")
                .property("hibernate.default_schema", "ventas")
                .property("jakarta.persistence.validation.mode", "none")
                .createEntityManagerFactory();
        em = fabrica.createEntityManager();
        ventas = new VentaRepositoryJpa();
        ventas.em = em;
    }

    @AfterEach
    void cerrar() {
        em.close();
        fabrica.close();
    }

    private <T> T enTransaccion(Supplier<T> paso) {
        em.getTransaction().begin();
        try {
            T resultado = paso.get();
            em.getTransaction().commit();
            return resultado;
        } finally {
            if (em.getTransaction().isActive()) {
                em.getTransaction().rollback();
            }
            em.clear();
        }
    }

    private void compraAbierta(String id) {
        enTransaccion(() -> {
            Venta venta = new Venta(id, "U-comprador", "E-evento", UUID.randomUUID().toString(), ABIERTA,
                    ABIERTA.plusMinutes(10));
            venta.agregarItem("S-campo", "Campo", 55_000, 2);
            venta.agregarItem("S-platea", "Platea", 80_000, 1);
            ventas.guardar(venta);
            return null;
        });
    }

    private List<String> pendientes() {
        return enTransaccion(() -> ventas.buscarDevolucionesPendientes()).stream().map(Venta::getId).toList();
    }

    @Test
    void unaCompraAbiertaNoTieneDevolucionPendiente() {
        compraAbierta("V-11111111");

        assertTrue(pendientes().isEmpty());
    }

    @Test
    void cancelarPoneLaMarcaYElBarridoEncuentraLaVentaConSusRenglones() {
        compraAbierta("V-11111111");
        assertTrue(enTransaccion(() -> ventas.cancelar("V-11111111")));

        List<Venta> pendientes = enTransaccion(() -> ventas.buscarDevolucionesPendientes());

        assertEquals(1, pendientes.size());
        Venta venta = pendientes.get(0);
        assertEquals(EstadoVenta.CANCELADA, venta.getEstado());
        assertTrue(venta.isDevolucionPendiente());
        // Con los renglones cargados: el barrido los necesita para saber qué devolver.
        assertEquals(List.of("S-campo", "S-platea"), venta.getItems().stream().map(VentaItem::getSectorId).toList());
    }

    @Test
    void vencerTambienPoneLaMarca() {
        compraAbierta("V-11111111");
        assertTrue(enTransaccion(() -> ventas.vencer("V-11111111", VENCIDA)));

        assertEquals(List.of("V-11111111"), pendientes());
    }

    @Test
    void vencerUnPagoSinCobroTambienPoneLaMarca() {
        compraAbierta("V-11111111");
        assertTrue(enTransaccion(() -> ventas.guardarClaveDePago("V-11111111", "clave-pago", ABIERTA.plusMinutes(1))));
        assertTrue(enTransaccion(() -> ventas.vencerPagoSinCobro("V-11111111", "clave-pago", VENCIDA)));

        assertEquals(List.of("V-11111111"), pendientes());
    }

    @Test
    void sacarLaMarcaLaQuitaDelBarridoYRepetirloNoCambiaNada() {
        compraAbierta("V-11111111");
        compraAbierta("V-22222222");
        assertTrue(enTransaccion(() -> ventas.cancelar("V-11111111")));
        assertTrue(enTransaccion(() -> ventas.cancelar("V-22222222")));

        assertTrue(enTransaccion(() -> ventas.marcarCupoDevuelto("V-11111111")));
        assertFalse(enTransaccion(() -> ventas.marcarCupoDevuelto("V-11111111")));

        assertEquals(List.of("V-22222222"), pendientes());
    }

    @Test
    void unaCompraQueNoSePudoCerrarNoQuedaConLaMarca() {
        // El UPDATE no cambió la fila (la venta tenía un pago en curso): tampoco pone la marca.
        compraAbierta("V-11111111");
        assertTrue(enTransaccion(() -> ventas.guardarClaveDePago("V-11111111", "clave-pago", ABIERTA.plusMinutes(1))));
        assertFalse(enTransaccion(() -> ventas.cancelar("V-11111111")));

        assertTrue(pendientes().isEmpty());
    }
}
