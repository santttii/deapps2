package com.palco.notificaciones.dominio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.palco.common.ReglasDeNegocio;
import com.palco.common.datos.BaseDeDatos;
import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.common.mensajeria.PagoConfirmadoMensaje.Entrada;
import com.palco.common.mensajeria.PagoConfirmadoMensaje.Titular;

/**
 * Corre las migraciones de db/migracion sobre H2 en modo PostgreSQL, con las
 * mismas opciones que el datasource de WildFly en desarrollo, y guarda en
 * registro_auditoria el cuerpo más largo que el contrato admite.
 */
class MigracionesEnH2Test {

    /** Ventas acepta nombres de titular de hasta 200 caracteres. */
    private static final int LARGO_NOMBRE = 200;
    /** Eventos acepta nombres de sector de hasta 120 caracteres. */
    private static final int LARGO_SECTOR = 120;

    private JdbcDataSource base;

    @BeforeEach
    void crearBase() {
        base = new JdbcDataSource();
        // Una base nueva por test: el nombre al azar evita que se compartan entre tests.
        base.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH");
        BaseDeDatos.migrar(base, "notificaciones");
    }

    @Test
    void laColumnaCuerpoQuedaComoVarcharDelLargoQueDeclaraLaEntidad() throws Exception {
        try (Connection conexion = base.getConnection();
                PreparedStatement consulta = conexion.prepareStatement(
                        "select data_type, character_maximum_length, is_nullable from information_schema.columns "
                                + "where table_schema = 'notificaciones' and table_name = 'registro_auditoria' "
                                + "and column_name = 'cuerpo'");
                ResultSet fila = consulta.executeQuery()) {
            assertTrue(fila.next(), "no existe la columna cuerpo");
            // Con DATABASE_TO_LOWER, H2 devuelve el tipo en minúsculas.
            assertEquals("character varying", fila.getString(1).toLowerCase());
            assertEquals(RegistroDeAuditoria.LARGO_MAXIMO_CUERPO, fila.getLong(2));
            assertEquals("no", fila.getString(3).toLowerCase());
        }
    }

    @Test
    void guardaUnMensajeValidoDeSeisEntradasConNombresLargosLlenosDeComillas() throws Exception {
        PagoConfirmadoMensaje mensaje = mensajeMasLargoPosible();
        String cuerpo = mensaje.aJson();
        // El mensaje es válido: es el mismo control que hace el MDB de auditoría al recibirlo.
        PagoConfirmadoMensaje.desdeJson(cuerpo);
        assertTrue(cuerpo.length() > 4000 && cuerpo.length() <= RegistroDeAuditoria.LARGO_MAXIMO_CUERPO, "el cuerpo tiene " + cuerpo.length() + " caracteres");

        try (Connection conexion = base.getConnection()) {
            try (PreparedStatement insert = conexion.prepareStatement(
                    "insert into notificaciones.registro_auditoria (mensaje_id, tipo_evento, venta_id, "
                            + "comprador_id, evento_id, monto_total, cuerpo, recibido_en) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, "ID:" + UUID.randomUUID());
                insert.setString(2, "PAGO_CONFIRMADO");
                insert.setString(3, mensaje.ventaId());
                insert.setString(4, mensaje.compradorId());
                insert.setString(5, mensaje.eventoId());
                insert.setLong(6, mensaje.montoTotal());
                insert.setString(7, cuerpo);
                insert.setTimestamp(8, Timestamp.from(mensaje.timestamp()));
                assertEquals(1, insert.executeUpdate());
            }
            try (PreparedStatement consulta = conexion.prepareStatement(
                    "select cuerpo from notificaciones.registro_auditoria where venta_id = ?")) {
                consulta.setString(1, mensaje.ventaId());
                try (ResultSet fila = consulta.executeQuery()) {
                    assertTrue(fila.next());
                    assertEquals(cuerpo, fila.getString(1));
                }
            }
        }
    }

    /** 6 entradas con nombre de 200 comillas y sector de 120: JSON escribe cada comilla como \". */
    private static PagoConfirmadoMensaje mensajeMasLargoPosible() {
        String nombre = "\"".repeat(LARGO_NOMBRE);
        String sector = "\"".repeat(LARGO_SECTOR);
        List<Entrada> entradas = new ArrayList<>();
        for (int i = 1; i <= ReglasDeNegocio.MAX_ENTRADAS_POR_ORDEN; i++) {
            entradas.add(new Entrada("T-" + "0".repeat(37) + i, sector, new Titular(nombre, "3012345" + i)));
        }
        return new PagoConfirmadoMensaje("V-" + "a".repeat(38), "user-" + "b".repeat(35), "ev-" + "c".repeat(37),
                entradas, 363000, Instant.parse("2026-10-16T21:30:00Z"));
    }
}
