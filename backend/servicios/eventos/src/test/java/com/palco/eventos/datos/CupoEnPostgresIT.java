package com.palco.eventos.datos;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PersistenceUnitTransactionType;
import jakarta.persistence.PessimisticLockException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.function.Executable;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.datos.BaseDeDatos;
import com.palco.eventos.dominio.Categoria;
import com.palco.eventos.dominio.DevolucionDeCupo;
import com.palco.eventos.dominio.Evento;
import com.palco.eventos.dominio.Imagen;
import com.palco.eventos.dominio.Sector;
import com.palco.eventos.negocio.TiposDeProblema;

/**
 * Los mismos casos que {@link CupoEnH2Test}, contra un PostgreSQL de verdad: la
 * imagen del servicio {@code db} del docker-compose, levantada en un contenedor
 * con Testcontainers, con el esquema que crean las migraciones de Flyway
 * (incluidos los seis eventos de prueba). El cupo de un sector no pasa del
 * máximo ni baja de cero porque el UPDATE lleva la condición adentro, y acá la
 * que la evalúa es la base del sistema levantado.
 *
 * El bloqueo de la edición es lo que más cambia entre las dos bases: en
 * PostgreSQL un {@code FOR UPDATE} no bloquea el lado opcional de un left join,
 * por eso el repositorio bloquea los sectores en una consulta aparte. Este test
 * comprueba que mientras dura la edición ni una venta ni otra edición pasan,
 * con los bloqueos de fila de PostgreSQL.
 *
 * Lo corre failsafe en "mvn verify" (ver backend/pom.xml). Sin Docker se
 * saltea con un aviso.
 */
@EnabledIfSystemProperty(named = "palco.pruebas.integracion", matches = "true",
        disabledReason = "Lo corre failsafe en mvn verify (-Dpalco.pruebas.integracion=true)")
class CupoEnPostgresIT {

    private static final String EVENTO = "ev-prueba";
    private static final String SECTOR = "sec-prueba";
    private static final int CUPO = 3;
    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 23, 12, 0);

    /** Un contenedor para todos los tests de la clase; cada test migra un esquema nuevo. */
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private PGSimpleDataSource base;
    private EntityManagerFactory fabrica;
    private EntityManager em;
    private EventoRepositoryJpa eventos;
    private SectorRepositoryJpa sectores;
    private DevolucionDeCupoRepositoryJpa devoluciones;

    @BeforeAll
    static void levantarPostgres() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Sin Docker no hay PostgreSQL en contenedor: se saltea (los mismos casos corren en CupoEnH2Test)");
        POSTGRES.start();
    }

    @AfterAll
    static void bajarPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void crearBase() throws SQLException {
        base = new PGSimpleDataSource();
        base.setUrl(POSTGRES.getJdbcUrl());
        base.setUser(POSTGRES.getUsername());
        base.setPassword(POSTGRES.getPassword());
        // Cada test arranca de cero, como con la base nueva de H2: se borra el esquema y Flyway lo vuelve a crear.
        try (Connection conexion = base.getConnection(); Statement sql = conexion.createStatement()) {
            sql.execute("drop schema if exists eventos cascade");
        }
        BaseDeDatos.migrar(base, "eventos");

        fabrica = new PersistenceConfiguration("eventos-postgresql")
                .transactionType(PersistenceUnitTransactionType.RESOURCE_LOCAL)
                .managedClass(Evento.class)
                .managedClass(Sector.class)
                .managedClass(DevolucionDeCupo.class)
                .managedClass(Imagen.class)
                // El conversor guarda la etiqueta ("Música") y no el nombre del enumerado, como en el servidor.
                .managedClass(Categoria.ConversorJpa.class)
                .property(PersistenceConfiguration.JDBC_URL, POSTGRES.getJdbcUrl())
                .property(PersistenceConfiguration.JDBC_USER, POSTGRES.getUsername())
                .property(PersistenceConfiguration.JDBC_PASSWORD, POSTGRES.getPassword())
                // Las tablas las crea Flyway, igual que en el servidor.
                .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "none")
                .property("hibernate.default_schema", "eventos")
                .property("jakarta.persistence.validation.mode", "none")
                .createEntityManagerFactory();
        em = fabrica.createEntityManager();
        eventos = new EventoRepositoryJpa();
        eventos.em = em;
        sectores = new SectorRepositoryJpa();
        sectores.em = em;
        devoluciones = new DevolucionDeCupoRepositoryJpa();
        devoluciones.em = em;

        enTransaccion(() -> {
            Evento evento = new Evento(EVENTO, "show-de-prueba", "Show de prueba", Categoria.MUSICA, "Estadio",
                    LocalDateTime.of(2027, 3, 1, 21, 0), "Descripción", null, "org-1");
            evento.agregarSector(new Sector(SECTOR, evento, "Campo", 50_000, CUPO, 1));
            eventos.guardar(evento);
            return null;
        });
        em.clear();
    }

    @AfterEach
    void cerrar() {
        em.close();
        fabrica.close();
    }

    @Test
    void losEventosInicialesSonDeLaCuentaOrganizadoraYCadaUnoVeSoloLosSuyos() {
        // V6 les puso dueño a los seis de V2; el de prueba es de "org-1".
        var deLaOrganizadora = eventos.listarDelOrganizador("user-organizador");
        assertEquals(6, deLaOrganizadora.size());
        assertTrue(deLaOrganizadora.stream().allMatch(e -> "user-organizador".equals(e.getOrganizadorId())));
        assertEquals(List.of(EVENTO), eventos.listarDelOrganizador("org-1").stream().map(Evento::getId).toList());
        assertTrue(eventos.listarDelOrganizador("user-otro-organizador").isEmpty());
        assertEquals("org-1", eventos.buscarPorId(EVENTO).orElseThrow().getOrganizadorId());
        assertTrue(eventos.buscarPorId("ev-9").isEmpty());
    }

    @Test
    void unaImagenSeGuardaEnLaBaseYSeLeeIgual() {
        ImagenRepositoryJpa imagenes = new ImagenRepositoryJpa();
        imagenes.em = em;
        byte[] contenido = new byte[300_000];
        new Random(7).nextBytes(contenido);
        String id = UUID.randomUUID() + ".jpg";

        enTransaccion(() -> {
            imagenes.guardar(new Imagen(id, "image/jpeg", contenido, AHORA));
            return null;
        });
        em.clear();

        Imagen leida = imagenes.buscar(id).orElseThrow();
        assertEquals("image/jpeg", leida.getTipo());
        assertEquals(300_000, leida.getTamano());
        assertArrayEquals(contenido, leida.getContenido());
        assertTrue(imagenes.buscar("otra.jpg").isEmpty());
    }

    /** Corre el paso en una transacción propia, como lo haría el contenedor con cada pedido. */
    private <T> T enTransaccion(Supplier<T> paso) {
        em.getTransaction().begin();
        T resultado = paso.get();
        em.getTransaction().commit();
        return resultado;
    }

    /** Busca el sector y le suma la cantidad, en una transacción, como lo hace el negocio. */
    private boolean sumar(int cantidad) {
        return enTransaccion(() -> sectores.sumarVendidas(sectores.buscarPorId(SECTOR).orElseThrow(), cantidad));
    }

    /** Lo que guardó la base, leído por JDBC y no por Hibernate. */
    private int vendidasEnLaBase() throws SQLException {
        try (Connection conexion = base.getConnection();
                PreparedStatement consulta = conexion.prepareStatement(
                        "select vendidas from eventos.sector where id = ?")) {
            consulta.setString(1, SECTOR);
            try (ResultSet fila = consulta.executeQuery()) {
                assertTrue(fila.next());
                return fila.getInt(1);
            }
        }
    }

    @Test
    void reservarHastaElCupoSePuedeYUnLugarMasNo() throws Exception {
        assertTrue(sumar(2));
        assertTrue(sumar(1));
        assertEquals(CUPO, vendidasEnLaBase());

        assertFalse(sumar(1));
        assertEquals(CUPO, vendidasEnLaBase());
    }

    @Test
    void unaReservaQuePasaElCupoNoCambiaNadaAunqueQuedenLugares() throws Exception {
        assertTrue(sumar(2));

        // Queda 1 lugar y se piden 2: no se reserva ninguno, no queda a medias.
        assertFalse(sumar(2));
        assertEquals(2, vendidasEnLaBase());
    }

    @Test
    void devolverMasDeLasVendidasNoCambiaNadaYDevolverLasVendidasSi() throws Exception {
        assertTrue(sumar(2));

        assertFalse(sumar(-3));
        assertEquals(2, vendidasEnLaBase());

        assertTrue(sumar(-2));
        assertEquals(0, vendidasEnLaBase());
    }

    @Test
    void despuesDelCambioElSectorTraeLasVendidasDeLaBase() {
        Sector sector = enTransaccion(() -> {
            Sector cargado = sectores.buscarPorId(SECTOR).orElseThrow();
            assertEquals(0, cargado.getVendidas());
            assertTrue(sectores.sumarVendidas(cargado, 2));
            return cargado;
        });

        assertEquals(2, sector.getVendidas());
        assertEquals(1, sector.disponibles());
    }

    @Test
    void unaDevolucionRegistradaSeEncuentraPorSuClave() {
        assertTrue(sumar(2));
        enTransaccion(() -> {
            assertTrue(sectores.sumarVendidas(sectores.buscarPorId(SECTOR).orElseThrow(), -2));
            devoluciones.registrar(new DevolucionDeCupo("devolucion:V-1:" + SECTOR, SECTOR, 2, AHORA));
            return null;
        });
        em.clear();

        DevolucionDeCupo guardada = enTransaccion(() -> devoluciones.buscar("devolucion:V-1:" + SECTOR)).orElseThrow();
        assertEquals(SECTOR, guardada.getSectorId());
        assertEquals(2, guardada.getCantidad());
        assertTrue(enTransaccion(() -> devoluciones.buscar("devolucion:V-2:" + SECTOR)).isEmpty());
    }

    @Test
    void registrarDosVecesLaMismaClaveEsDevolucionYaAplicadaYNoRestaDosVeces() throws Exception {
        // Dos pedidos iguales a la vez: los dos pasaron la búsqueda y llegan al INSERT. La base deja uno.
        assertTrue(sumar(2));
        enTransaccion(() -> {
            assertTrue(sectores.sumarVendidas(sectores.buscarPorId(SECTOR).orElseThrow(), -1));
            devoluciones.registrar(new DevolucionDeCupo("devolucion:V-1:" + SECTOR, SECTOR, 1, AHORA));
            return null;
        });
        em.clear();

        em.getTransaction().begin();
        assertTrue(sectores.sumarVendidas(sectores.buscarPorId(SECTOR).orElseThrow(), -1));
        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> devoluciones.registrar(new DevolucionDeCupo("devolucion:V-1:" + SECTOR, SECTOR, 1, AHORA)));
        // En el servidor, el Conflicto deshace la transacción entera (@ApplicationException(rollback = true)).
        em.getTransaction().rollback();
        em.clear();

        assertEquals(TiposDeProblema.DEVOLUCION_YA_APLICADA, error.tipo());
        assertEquals(1, vendidasEnLaBase());
    }

    @Test
    void mientrasDuraLaEdicionNiUnaVentaNiOtraEdicionDelEventoPasan() {
        // La edición (o la cancelación) lee el evento bloqueado y sigue abierta.
        em.getTransaction().begin();
        Evento enEdicion = eventos.buscarPorIdParaCambiar(EVENTO).orElseThrow();
        assertEquals(1, enEdicion.getSectores().size());

        // Una venta del mismo evento no puede tocar el sector: espera, y acá se cansa de esperar.
        esperaElBloqueo(() -> enOtroPedido(otro -> {
            SectorRepositoryJpa venta = new SectorRepositoryJpa();
            venta.em = otro;
            venta.sumarVendidas(venta.buscarPorId(SECTOR).orElseThrow(), 1);
        }));
        // Otra edición tampoco puede leerlo para cambiarlo: no compara contra un estado que está por cambiar.
        esperaElBloqueo(() -> enOtroPedido(otro -> {
            EventoRepositoryJpa otraEdicion = new EventoRepositoryJpa();
            otraEdicion.em = otro;
            otraEdicion.buscarPorIdParaCambiar(EVENTO);
        }));
        em.getTransaction().commit();

        // Terminada la edición, la venta pasa.
        assertTrue(sumar(1));
        assertTrue(enTransaccion(() -> eventos.buscarPorIdParaCambiar("ev-9")).isEmpty());
    }

    /** El paso falla porque se cansó de esperar un bloqueo, y no por otra cosa (un SQL mal escrito, por ejemplo). */
    private static void esperaElBloqueo(Executable paso) {
        PersistenceException error = assertThrows(PersistenceException.class, paso);
        assertTrue(error instanceof LockTimeoutException || error instanceof PessimisticLockException,
                error.toString());
    }

    /**
     * Corre el paso en otra transacción y otra conexión, como un pedido que llega
     * mientras tanto. Espera un bloqueo 200 ms, para que el test sea rápido: en
     * PostgreSQL es {@code lock_timeout} (con {@code SET LOCAL}, solo para esta
     * transacción), que por defecto no tiene límite.
     */
    private void enOtroPedido(Consumer<EntityManager> paso) {
        EntityManager otro = fabrica.createEntityManager();
        try {
            otro.getTransaction().begin();
            otro.createNativeQuery("set local lock_timeout = '200ms'").executeUpdate();
            paso.accept(otro);
            otro.getTransaction().commit();
        } finally {
            if (otro.getTransaction().isActive()) {
                otro.getTransaction().rollback();
            }
            otro.close();
        }
    }

    @Test
    void unSectorQueNoExisteNoSeEncuentra() {
        assertTrue(enTransaccion(() -> sectores.buscarPorId("sec-no-existe")).isEmpty());
    }

    @Test
    void elListadoFiltraPorCategoriaYOrdenaPorFecha() {
        // Los seis eventos de las migraciones más el de este test.
        List<Evento> todos = enTransaccion(() -> eventos.listar(null));
        List<Evento> musica = enTransaccion(() -> eventos.listar(Categoria.MUSICA));

        assertEquals(7, todos.size());
        for (int i = 1; i < todos.size(); i++) {
            assertFalse(todos.get(i).getFecha().isBefore(todos.get(i - 1).getFecha()), "orden por fecha");
        }
        assertFalse(musica.isEmpty());
        assertTrue(musica.stream().allMatch(e -> e.getCategoria() == Categoria.MUSICA));
        assertTrue(musica.stream().anyMatch(e -> e.getId().equals(EVENTO)));
        // Cada evento aparece una vez aunque tenga varios sectores.
        assertEquals(todos.size(), todos.stream().map(Evento::getId).distinct().count());
    }

    @Test
    void elDetalleTraeLosSectoresEnOrden() {
        Evento evento = enTransaccion(() -> eventos.buscarPorSlug("atletico-norte-racing-sur")).orElseThrow();

        assertFalse(evento.getSectores().isEmpty());
        for (int i = 1; i < evento.getSectores().size(); i++) {
            assertTrue(evento.getSectores().get(i).getOrden() > evento.getSectores().get(i - 1).getOrden());
        }
        assertTrue(enTransaccion(() -> eventos.buscarPorSlug("no-existe")).isEmpty());
    }

    @Test
    void existeSlugMiraLosEventosGuardados() {
        assertTrue(enTransaccion(() -> eventos.existeSlug("show-de-prueba")));
        assertFalse(enTransaccion(() -> eventos.existeSlug("show-de-prueba-2")));
    }

    @Test
    void losEventosDelOrganizadorSonSoloLosSuyosOrdenadosPorFecha() {
        List<Evento> deOrg1 = enTransaccion(() -> eventos.listarDelOrganizador("org-1"));
        // Los seis de las migraciones quedan a nombre de la cuenta organizadora de prueba (V6).
        List<Evento> deLaCuentaDePrueba = enTransaccion(() -> eventos.listarDelOrganizador("user-organizador"));

        assertEquals(List.of(EVENTO), deOrg1.stream().map(Evento::getId).toList());
        assertEquals(6, deLaCuentaDePrueba.size());
        for (int i = 1; i < deLaCuentaDePrueba.size(); i++) {
            assertFalse(deLaCuentaDePrueba.get(i).getFecha().isBefore(deLaCuentaDePrueba.get(i - 1).getFecha()),
                    "orden por fecha");
        }
        assertEquals(1, deOrg1.get(0).getSectores().size());
        assertTrue(enTransaccion(() -> eventos.listarDelOrganizador("org-sin-eventos")).isEmpty());
    }
}
