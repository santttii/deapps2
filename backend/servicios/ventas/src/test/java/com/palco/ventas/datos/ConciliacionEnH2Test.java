package com.palco.ventas.datos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
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
import com.palco.ventas.dominio.TitularDeVenta;
import com.palco.ventas.dominio.Venta;
import com.palco.ventas.dominio.VentaItem;

/**
 * Lo que la conciliación le pide a la base, contra una base de verdad: H2 en
 * modo PostgreSQL con el esquema que crean las migraciones de Flyway (también
 * la V2, que agrega {@code pago_intentado} y {@code venta_titular}). Las
 * consultas son las del repositorio, con Hibernate; las transacciones las
 * abre y las cierra el test, en el mismo orden en que lo haría el contenedor.
 *
 * El caso central es la falla a mitad de la compra: la copia de los titulares
 * se confirma en su propia transacción, la del pago se deshace después de
 * confirmar la venta y crear las entradas, y la conciliación termina la venta
 * con los datos que quedaron.
 */
class ConciliacionEnH2Test {

    private static final String VENTA_ID = "V-3f9a1c2b";
    private static final String CLAVE = "0b7e2a55-3c1d-4e8f-a9b0-c1d2e3f4a5b6";
    private static final String PAGO_ID = "P-598c58a0";
    /** Cuando se abrió la compra; la reserva vale hasta diez minutos después. */
    private static final LocalDateTime ABIERTA = LocalDateTime.of(2026, 10, 19, 21, 0, 0);
    /** Cuando se intentó pagar. */
    private static final LocalDateTime AL_PAGAR = ABIERTA.plusMinutes(1);

    private JdbcDataSource base;
    private EntityManagerFactory fabrica;
    private EntityManager em;
    private VentaRepositoryJpa ventas;
    private EntradaRepositoryJpa entradas;

    @BeforeEach
    void crearBase() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
        base = new JdbcDataSource();
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
        entradas = new EntradaRepositoryJpa();
        entradas.em = em;
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

    /** Una compra abierta de dos entradas en Campo, con el intento de pago anotado a las {@link #AL_PAGAR}. */
    private void compraConPagoSinResultado() {
        enTransaccion(() -> {
            Venta venta = new Venta(VENTA_ID, "U-comprador", "E-evento", UUID.randomUUID().toString(), ABIERTA,
                    ABIERTA.plusMinutes(10));
            venta.agregarItem("S-campo", "Campo", 55_000, 2);
            ventas.guardar(venta);
            return null;
        });
        assertTrue(enTransaccion(() -> ventas.guardarClaveDePago(VENTA_ID, CLAVE, AL_PAGAR)));
    }

    private void titularesCopiados() {
        enTransaccion(() -> {
            ventas.respaldarTitulares(VENTA_ID, List.of(
                    new TitularDeVenta(VENTA_ID, 1, "Ana Gómez", "30111222"),
                    new TitularDeVenta(VENTA_ID, 2, "Luis Pérez", "30333444")));
            return null;
        });
    }

    // ---- La marca del intento y la copia de los titulares ----

    @Test
    void anotarElIntentoDePagoGuardaTambienCuandoEmpezo() throws Exception {
        compraConPagoSinResultado();

        assertEquals(CLAVE, columna("clave_pago"));
        assertEquals(Timestamp.valueOf(AL_PAGAR), columnaFecha("pago_intentado"));

        // Un reintento con la misma clave vuelve a anotar el instante: el margen cuenta desde el último intento.
        LocalDateTime reintento = AL_PAGAR.plusMinutes(3);
        assertTrue(enTransaccion(() -> ventas.guardarClaveDePago(VENTA_ID, CLAVE, reintento)));
        assertEquals(Timestamp.valueOf(reintento), columnaFecha("pago_intentado"));
    }

    @Test
    void laCopiaDeLosTitularesSeGuardaUnaSolaVezYSeLeeEnElOrdenDelPedido() {
        compraConPagoSinResultado();
        titularesCopiados();
        // Un segundo intento de pago no la duplica ni la cambia.
        enTransaccion(() -> {
            ventas.respaldarTitulares(VENTA_ID, List.of(new TitularDeVenta(VENTA_ID, 1, "Otra Persona", "40111222")));
            return null;
        });

        List<String> leidos = enTransaccion(() -> ventas.buscarTitulares(VENTA_ID)).stream()
                .map(t -> t.getOrden() + "|" + t.getNombre() + "|" + t.getDni())
                .toList();

        assertEquals(List.of("1|Ana Gómez|30111222", "2|Luis Pérez|30333444"), leidos);
    }

    @Test
    void laCopiaDeLosTitularesCorreEnSuPropiaTransaccion() throws NoSuchMethodException {
        Method respaldar = VentaRepositoryJpa.class.getMethod("respaldarTitulares", String.class, List.class);

        // REQUIRES_NEW: se confirma antes del cobro y no se deshace si después se deshace el pago.
        assertEquals(TransactionAttributeType.REQUIRES_NEW,
                respaldar.getAnnotation(TransactionAttribute.class).value());
    }

    // ---- Qué ventas busca la conciliación ----

    @Test
    void laBusquedaTraeLasVentasMarcadasCuyoUltimoIntentoPasoElMargen() {
        compraConPagoSinResultado();

        // Un minuto después del intento, el cobro puede seguir en camino: todavía no se toca.
        assertTrue(enTransaccion(() -> ventas.buscarPagosSinResultado(AL_PAGAR.minusMinutes(1))).isEmpty());
        // Con el intento en el límite o antes, sí.
        List<Venta> encontradas = enTransaccion(() -> ventas.buscarPagosSinResultado(AL_PAGAR));
        assertEquals(1, encontradas.size());
        assertEquals(CLAVE, encontradas.get(0).getClavePago());
        assertEquals(AL_PAGAR, encontradas.get(0).getPagoIntentado());
        // Vienen con sus renglones cargados, para devolver el cupo fuera de la transacción.
        assertEquals(2, encontradas.get(0).getItems().get(0).getCantidad());
    }

    @Test
    void unaVentaSinIntentoDePagoNoEsDeLaConciliacion() {
        enTransaccion(() -> {
            Venta venta = new Venta(VENTA_ID, "U-comprador", "E-evento", UUID.randomUUID().toString(), ABIERTA,
                    ABIERTA.plusMinutes(10));
            venta.agregarItem("S-campo", "Campo", 55_000, 2);
            ventas.guardar(venta);
            return null;
        });

        // La vence el barrido de reservas, no la conciliación.
        assertTrue(enTransaccion(() -> ventas.buscarPagosSinResultado(AL_PAGAR.plusHours(1))).isEmpty());
    }

    // ---- La falla a mitad de la compra y la conciliación que la termina ----

    @Test
    void laFallaDespuesDelCobroDeshaceTodoYLaConciliacionTerminaLaVentaConLaCopia() throws Exception {
        compraConPagoSinResultado();
        // 1. Antes de cobrar, la copia de los titulares, en su transacción propia.
        titularesCopiados();

        // 2. La transacción del pago: Pagos aprobó, se confirma la venta y se crean las entradas, y después
        //    la falla de prueba. El contenedor la deshace; acá, el rollback lo pide el test.
        em.getTransaction().begin();
        Venta leida = ventas.buscarPorId(VENTA_ID).orElseThrow();
        assertTrue(ventas.confirmar(VENTA_ID, PAGO_ID, AL_PAGAR));
        VentaItem item = leida.getItems().get(0);
        entradas.guardar(List.of(new Entrada("T-00000001", item, "Ana Gómez", "30111222", 1),
                new Entrada("T-00000002", item, "Luis Pérez", "30333444", 2)));
        em.getTransaction().rollback();
        em.clear();

        // La venta no quedó CONFIRMADA ni tiene entradas, pero conserva la marca y la copia de los titulares.
        assertEquals("EN_CHECKOUT", columna("estado"));
        assertNull(columna("pago_id"));
        assertEquals(CLAVE, columna("clave_pago"));
        assertEquals(0, cantidad("select count(*) from ventas.entrada where venta_id = ?"));
        assertEquals(2, cantidad("select count(*) from ventas.venta_titular where venta_id = ?"));

        // 3. La conciliación encuentra la venta, Pagos le dice que el cobro se aprobó y la confirma.
        LocalDateTime alConciliar = AL_PAGAR.plusMinutes(3);
        Venta marcada = enTransaccion(() -> ventas.buscarPagosSinResultado(alConciliar.minusMinutes(2))).get(0);
        boolean confirmada = enTransaccion(() -> {
            Venta venta = ventas.buscarPorId(marcada.getId()).orElseThrow();
            List<TitularDeVenta> titulares = ventas.buscarTitulares(VENTA_ID);
            if (!ventas.confirmarConciliada(VENTA_ID, CLAVE, PAGO_ID, alConciliar)) {
                return false;
            }
            VentaItem renglon = venta.getItems().get(0);
            entradas.guardar(titulares.stream()
                    .map(t -> new Entrada("T-c000000" + t.getOrden(), renglon, t.getNombre(), t.getDni(), t.getOrden()))
                    .toList());
            return true;
        });

        assertTrue(confirmada);
        assertEquals("CONFIRMADA", columna("estado"));
        assertEquals(PAGO_ID, columna("pago_id"));
        assertEquals(2, cantidad("select count(*) from ventas.entrada where venta_id = ? and estado = 'PENDIENTE_EMISION'"));
        // Ya no es un pago sin resultado.
        assertTrue(enTransaccion(() -> ventas.buscarPagosSinResultado(alConciliar)).isEmpty());
    }

    @Test
    void confirmarLaVentaConciliadaPasaUnaSolaVez() throws Exception {
        compraConPagoSinResultado();

        assertTrue(enTransaccion(() -> ventas.confirmarConciliada(VENTA_ID, CLAVE, PAGO_ID, AL_PAGAR)));
        // Otra corrida, o un reintento que llega tarde: la venta ya no está EN_CHECKOUT.
        assertFalse(enTransaccion(() -> ventas.confirmarConciliada(VENTA_ID, CLAVE, PAGO_ID, AL_PAGAR)));
        assertFalse(enTransaccion(() -> ventas.vencerPagoSinCobro(VENTA_ID, CLAVE, AL_PAGAR)));
        assertEquals("CONFIRMADA", columna("estado"));
    }

    @Test
    void conOtraClaveAnotadaLaConciliacionNoConfirmaNiVence() throws Exception {
        compraConPagoSinResultado();

        assertFalse(enTransaccion(() -> ventas.confirmarConciliada(VENTA_ID, "otra-clave", PAGO_ID, AL_PAGAR)));
        assertFalse(enTransaccion(() -> ventas.vencerPagoSinCobro(VENTA_ID, "otra-clave", AL_PAGAR)));
        assertEquals("EN_CHECKOUT", columna("estado"));
    }

    // ---- Sin cobro: vencer ----

    @Test
    void sinCobroLaVentaVenceYPierdeLaMarcaUnaSolaVez() throws Exception {
        compraConPagoSinResultado();

        assertTrue(enTransaccion(() -> ventas.vencerPagoSinCobro(VENTA_ID, CLAVE, AL_PAGAR)));
        assertEquals("VENCIDA", columna("estado"));
        assertNull(columna("clave_pago"));

        // La segunda vez no cambia nada: el cupo lo devuelve uno solo.
        assertFalse(enTransaccion(() -> ventas.vencerPagoSinCobro(VENTA_ID, CLAVE, AL_PAGAR)));
        // Y una venta vencida ya no se puede confirmar ni pagar.
        assertFalse(enTransaccion(() -> ventas.confirmarConciliada(VENTA_ID, CLAVE, PAGO_ID, AL_PAGAR)));
        assertFalse(enTransaccion(() -> ventas.guardarClaveDePago(VENTA_ID, CLAVE, AL_PAGAR.plusMinutes(5))));
    }

    @Test
    void siLlegoUnReintentoDespuesDeLaPreguntaAPagosLaVentaNoSeVence() throws Exception {
        compraConPagoSinResultado();
        LocalDateTime intentadosHasta = AL_PAGAR;
        // Entre la pregunta a Pagos y el UPDATE, el comprador reintenta con la misma clave.
        assertTrue(enTransaccion(() -> ventas.guardarClaveDePago(VENTA_ID, CLAVE, AL_PAGAR.plusMinutes(4))));

        assertFalse(enTransaccion(() -> ventas.vencerPagoSinCobro(VENTA_ID, CLAVE, intentadosHasta)));
        assertEquals("EN_CHECKOUT", columna("estado"));
        assertEquals(CLAVE, columna("clave_pago"));
    }

    @Test
    void elBarridoDeReservasSigueSinTocarUnaVentaMarcada() throws Exception {
        compraConPagoSinResultado();

        assertTrue(enTransaccion(() -> ventas.buscarReservasVencidas(ABIERTA.plusHours(1))).isEmpty());
        assertFalse(enTransaccion(() -> ventas.vencer(VENTA_ID, ABIERTA.plusHours(1))));
        assertEquals("EN_CHECKOUT", columna("estado"));
    }

    @Test
    void dosCopiasDeLosTitularesConLaMismaPosicionLasRechazaLaBase() {
        compraConPagoSinResultado();
        titularesCopiados();

        // La red de seguridad si dos copias llegaran a escribirse a la vez: la clave primaria.
        assertThrows(RuntimeException.class, () -> enTransaccion(() -> {
            em.persist(new TitularDeVenta(VENTA_ID, 1, "Otra Persona", "40111222"));
            em.flush();
            return null;
        }));
    }

    // ---------------------------------------------------------------------------------------------

    private String columna(String nombre) throws Exception {
        try (Connection conexion = base.getConnection();
                PreparedStatement consulta = conexion.prepareStatement(
                        "select " + nombre + " from ventas.venta where id = ?")) {
            consulta.setString(1, VENTA_ID);
            try (ResultSet fila = consulta.executeQuery()) {
                assertTrue(fila.next());
                return fila.getString(1);
            }
        }
    }

    private Timestamp columnaFecha(String nombre) throws Exception {
        try (Connection conexion = base.getConnection();
                PreparedStatement consulta = conexion.prepareStatement(
                        "select " + nombre + " from ventas.venta where id = ?")) {
            consulta.setString(1, VENTA_ID);
            try (ResultSet fila = consulta.executeQuery()) {
                assertTrue(fila.next());
                return fila.getTimestamp(1);
            }
        }
    }

    private int cantidad(String sql) throws Exception {
        try (Connection conexion = base.getConnection(); PreparedStatement consulta = conexion.prepareStatement(sql)) {
            consulta.setString(1, VENTA_ID);
            try (ResultSet fila = consulta.executeQuery()) {
                assertTrue(fila.next());
                return fila.getInt(1);
            }
        }
    }
}
