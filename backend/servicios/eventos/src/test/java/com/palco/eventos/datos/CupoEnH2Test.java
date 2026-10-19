package com.palco.eventos.datos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
import com.palco.eventos.dominio.Categoria;
import com.palco.eventos.dominio.Evento;
import com.palco.eventos.dominio.Sector;

/**
 * El cupo de un sector nunca pasa del máximo ni baja de cero porque el UPDATE
 * del repositorio lleva la condición adentro, y eso lo decide la base. Este
 * test lo comprueba contra una base de verdad: H2 en modo PostgreSQL, con las
 * mismas opciones que el datasource de WildFly en desarrollo y el esquema que
 * crean las migraciones de Flyway (incluidos los seis eventos de prueba). Las
 * consultas son las de los repositorios, con Hibernate; lo único que cambia
 * respecto del servidor es que la transacción la abre el test y no el
 * contenedor.
 */
class CupoEnH2Test {

    private static final String EVENTO = "ev-prueba";
    private static final String SECTOR = "sec-prueba";
    private static final int CUPO = 3;

    private JdbcDataSource base;
    private EntityManagerFactory fabrica;
    private EntityManager em;
    private EventoRepositoryJpa eventos;
    private SectorRepositoryJpa sectores;

    @BeforeEach
    void crearBase() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
        base = new JdbcDataSource();
        base.setURL(url);
        BaseDeDatos.migrar(base, "eventos");

        fabrica = new PersistenceConfiguration("eventos-h2")
                .transactionType(PersistenceUnitTransactionType.RESOURCE_LOCAL)
                .managedClass(Evento.class)
                .managedClass(Sector.class)
                // El conversor guarda la etiqueta ("Música") y no el nombre del enumerado, como en el servidor.
                .managedClass(Categoria.ConversorJpa.class)
                .property(PersistenceConfiguration.JDBC_URL, url)
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
    private int vendidasEnLaBase() throws Exception {
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
}
