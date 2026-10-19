package com.palco.reventa.datos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import com.palco.reventa.dominio.EstadoPublicacion;
import com.palco.reventa.dominio.Publicacion;

/**
 * La migración y los UPDATE condicionales de las publicaciones contra H2 en
 * modo PostgreSQL, la base de WildFly en desarrollo. Lo importante: de dos
 * compras a la vez de la misma publicación, una sola la toma (y una sola
 * cobra); y mientras la toma vale, el vendedor no la retira.
 */
class PublicacionesEnH2Test {

    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 11, 20, 15, 0);
    private static final LocalDateTime VENCIDAS_HASTA = AHORA.minusMinutes(2);

    private EntityManagerFactory fabrica;
    private EntityManager em;
    private PublicacionRepositoryJpa repositorio;

    @BeforeEach
    void crearBase() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
        JdbcDataSource base = new JdbcDataSource();
        base.setURL(url);
        BaseDeDatos.migrar(base, "reventa");

        fabrica = new PersistenceConfiguration("reventa-h2")
                .transactionType(PersistenceUnitTransactionType.RESOURCE_LOCAL)
                .managedClass(Publicacion.class)
                .property(PersistenceConfiguration.JDBC_URL, url)
                .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "none")
                .property("hibernate.default_schema", "reventa")
                .property("jakarta.persistence.validation.mode", "none")
                .createEntityManagerFactory();
        em = fabrica.createEntityManager();
        repositorio = con(em);
        enTransaccion(em, () -> {
            repositorio.guardar(new Publicacion("R-1", "T-1", "U-vendedor", "ev-1", "sec-1", "Campo", 50_000, 55_000,
                    AHORA.minusDays(1)));
            repositorio.guardar(new Publicacion("R-2", "T-2", "U-vendedor", "ev-1", "sec-1", "Campo", 50_000, 40_000,
                    AHORA.minusDays(1)));
            return null;
        });
    }

    @AfterEach
    void cerrar() {
        em.close();
        fabrica.close();
    }

    private static PublicacionRepositoryJpa con(EntityManager em) {
        PublicacionRepositoryJpa repositorio = new PublicacionRepositoryJpa();
        repositorio.em = em;
        return repositorio;
    }

    private static <T> T enTransaccion(EntityManager em, Supplier<T> paso) {
        em.getTransaction().begin();
        T resultado = paso.get();
        em.getTransaction().commit();
        return resultado;
    }

    private Publicacion leer(String id) {
        em.clear();
        return repositorio.buscarPorId(id).orElseThrow();
    }

    @Test
    void lasActivasDelEventoSalenDeLaMasBarataALaMasCaraConElTope() {
        var activas = repositorio.buscarActivasPorEvento("ev-1");
        assertEquals(2, activas.size());
        assertEquals("R-2", activas.get(0).getId());
        assertEquals(55_000, activas.get(0).getPrecioMaximo());
        assertTrue(repositorio.buscarActivasPorEvento("ev-otro").isEmpty());
    }

    @Test
    void elVendedorNoTomaSuPropiaPublicacionYUnReintentoConLaMismaClavePasa() {
        assertFalse(enTransaccion(em, () -> repositorio.tomar("R-1", "U-vendedor", "k1", AHORA, VENCIDAS_HASTA)));
        assertTrue(enTransaccion(em, () -> repositorio.tomar("R-1", "U-a", "k1", AHORA, VENCIDAS_HASTA)));
        // El mismo comprador con la misma clave: es el reintento y pasa.
        assertTrue(enTransaccion(em, () -> repositorio.tomar("R-1", "U-a", "k1", AHORA, VENCIDAS_HASTA)));
        // Otro comprador, u otra clave, mientras la toma vale: no pasa.
        assertFalse(enTransaccion(em, () -> repositorio.tomar("R-1", "U-b", "k2", AHORA, VENCIDAS_HASTA)));
        assertFalse(enTransaccion(em, () -> repositorio.tomar("R-1", "U-a", "k2", AHORA, VENCIDAS_HASTA)));
    }

    @Test
    void unaTomaVencidaLaPuedeRetomarOtroYLiberarLaDejaLibre() {
        assertTrue(enTransaccion(em, () -> repositorio.tomar("R-1", "U-a", "k1", AHORA.minusMinutes(5),
                VENCIDAS_HASTA.minusMinutes(5))));
        assertTrue(enTransaccion(em, () -> repositorio.tomar("R-1", "U-b", "k2", AHORA, VENCIDAS_HASTA)));
        // Liberar con una clave que no es la de la toma no cambia nada.
        assertFalse(enTransaccion(em, () -> repositorio.liberar("R-1", "k1")));
        assertTrue(enTransaccion(em, () -> repositorio.liberar("R-1", "k2")));
        assertEquals(null, leer("R-1").getClaveCompra());
        assertEquals(EstadoPublicacion.ACTIVA, leer("R-1").getEstado());
    }

    @Test
    void conUnaTomaVigenteElVendedorNoLaRetiraYSinTomaSi() {
        enTransaccion(em, () -> repositorio.tomar("R-1", "U-a", "k1", AHORA, VENCIDAS_HASTA));
        assertFalse(enTransaccion(em, () -> repositorio.retirar("R-1", "U-vendedor", AHORA, VENCIDAS_HASTA)));
        assertFalse(enTransaccion(em, () -> repositorio.retirar("R-2", "U-otro", AHORA, VENCIDAS_HASTA)));
        assertTrue(enTransaccion(em, () -> repositorio.retirar("R-2", "U-vendedor", AHORA, VENCIDAS_HASTA)));
        assertEquals(EstadoPublicacion.RETIRADA, leer("R-2").getEstado());
        // Una retirada no se compra.
        assertFalse(enTransaccion(em, () -> repositorio.tomar("R-2", "U-a", "k1", AHORA, VENCIDAS_HASTA)));
    }

    @Test
    void venderlaLaCierraConLaVentaYElPago() {
        enTransaccion(em, () -> repositorio.tomar("R-1", "U-a", "k1", AHORA, VENCIDAS_HASTA));
        assertTrue(enTransaccion(em, () -> repositorio.marcarVendida("R-1", "U-a", "k1", "V-1", "P-1", AHORA)));
        assertFalse(enTransaccion(em, () -> repositorio.marcarVendida("R-1", "U-a", "k1", "V-1", "P-1", AHORA)));

        Publicacion vendida = leer("R-1");
        assertEquals(EstadoPublicacion.VENDIDA, vendida.getEstado());
        assertEquals("V-1", vendida.getVentaId());
        assertEquals("P-1", vendida.getPagoId());
        assertEquals(1, repositorio.buscarActivasPorEvento("ev-1").size());
        assertEquals(2, repositorio.buscarPorVendedor("U-vendedor").size());
    }

    @Test
    void dosComprasALaVezTomanLaPublicacionUnaSolaVez() throws Exception {
        EntityManager otro = fabrica.createEntityManager();
        try {
            // La primera compra hace su UPDATE y todavía no confirma: la fila queda bloqueada.
            em.getTransaction().begin();
            boolean primera = repositorio.tomar("R-1", "U-a", "k1", AHORA, VENCIDAS_HASTA);

            // La segunda espera el bloqueo; cuando la primera confirma, ya la ve tomada y no cambia nada.
            CompletableFuture<Boolean> segunda = CompletableFuture.supplyAsync(
                    () -> enTransaccion(otro, () -> con(otro).tomar("R-1", "U-b", "k2", AHORA, VENCIDAS_HASTA)));
            assertFalse(terminoEn(segunda, 300), "el segundo UPDATE no esperó al primero");
            em.getTransaction().commit();

            assertTrue(primera);
            assertFalse(segunda.get(10, TimeUnit.SECONDS));
            assertEquals("U-a", leer("R-1").getCompradorId());
            assertNotEquals("k2", leer("R-1").getClaveCompra());
        } finally {
            otro.close();
        }
    }

    private static boolean terminoEn(CompletableFuture<?> tarea, long milisegundos) {
        try {
            tarea.get(milisegundos, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (Exception e) {
            return true;
        }
    }
}
