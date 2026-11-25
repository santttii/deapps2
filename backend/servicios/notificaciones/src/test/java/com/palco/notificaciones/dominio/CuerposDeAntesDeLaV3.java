package com.palco.notificaciones.dominio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;

import com.palco.notificaciones.Ejemplos;

/**
 * Lo que tiene que hacer la migración V3 (DEF-37), escrito una vez para
 * {@link MigracionesEnH2Test} y {@link MigracionesEnPostgresIT}: una base como
 * estaba hasta el 23/11, con V1 y V2 y filas de auditoría con el DNI de los
 * titulares, queda sin ningún DNI y con el resto del cuerpo igual.
 */
final class CuerposDeAntesDeLaV3 {

    static final String ESQUEMA = "notificaciones";

    /** Un titular escrito a mano con el DNI después del nombre: la otra alternativa del patrón de la V3. */
    private static final String DNI_DESPUES_DEL_NOMBRE = """
            {"compradorId":"user-titular","entradas":[\
            {"entradaId":"T-9c41d07e","sector":"Platea B","titular":{"nombre":"Ana Gómez","dni":"30123456"}}],\
            "eventoId":"ev-1","montoTotal":30250,"timestamp":"2026-10-16T21:30:00Z","ventaId":"V-3f9a1c2b"}""";

    /** Un nombre con comillas que imitan el campo: JSON las escribe como \" y la V3 no las toca. */
    private static final String DNI_DENTRO_DE_UN_NOMBRE = """
            {"compradorId":"user-titular","entradas":[\
            {"entradaId":"T-9c41d07e","sector":"Platea B","titular":{"nombre":"Ana \\"dni\\":\\"30123456\\", Gómez"}}],\
            "eventoId":"ev-1","montoTotal":30250,"timestamp":"2026-10-16T21:30:00Z","ventaId":"V-3f9a1c2b"}""";

    private CuerposDeAntesDeLaV3() {
    }

    /** Migra hasta la V2 y guarda filas como las que dejaba la auditoría hasta el 23/11. */
    static void migrarHastaLaV2YGuardarFilasConDni(DataSource base) throws SQLException {
        Flyway.configure()
                .dataSource(base)
                .schemas(ESQUEMA)
                .createSchemas(true)
                .locations("classpath:db/migracion")
                .target("2")
                .load()
                .migrate();
        try (Connection conexion = base.getConnection()) {
            insertar(conexion, "ID:1", "PAGO_CONFIRMADO", Ejemplos.PAGO_CONFIRMADO_CON_DNI);
            insertar(conexion, "ID:2", "REVENTA_CONFIRMADA", DNI_DESPUES_DEL_NOMBRE);
            insertar(conexion, "ID:3", "PAGO_CONFIRMADO", DNI_DENTRO_DE_UN_NOMBRE);
            insertar(conexion, "ID:4", "COMPRA_ANULADA", Ejemplos.compraAnulada(true).aJson());
        }
    }

    /** Después de la V3: sin DNI en ninguna fila, y lo demás como estaba. */
    static void comprobarQueQuedaronSinDni(DataSource base) throws SQLException {
        Map<String, String> cuerpos = cuerpos(base);

        // Las dos entradas pierden el DNI y la fila queda igual que la de un mensaje nuevo.
        assertEquals(Ejemplos.pagoConfirmado().aJson(), cuerpos.get("ID:1"));
        assertEquals(DNI_DESPUES_DEL_NOMBRE.replace(",\"dni\":\"30123456\"", ""), cuerpos.get("ID:2"));
        assertEquals(DNI_DENTRO_DE_UN_NOMBRE, cuerpos.get("ID:3"));
        assertEquals(Ejemplos.compraAnulada(true).aJson(), cuerpos.get("ID:4"));
    }

    private static Map<String, String> cuerpos(DataSource base) throws SQLException {
        Map<String, String> cuerpos = new HashMap<>();
        try (Connection conexion = base.getConnection();
                PreparedStatement consulta = conexion.prepareStatement(
                        "select mensaje_id, cuerpo from " + ESQUEMA + ".registro_auditoria");
                ResultSet fila = consulta.executeQuery()) {
            while (fila.next()) {
                cuerpos.put(fila.getString(1), fila.getString(2));
            }
        }
        assertEquals(4, cuerpos.size(), cuerpos.toString());
        return cuerpos;
    }

    private static void insertar(Connection conexion, String mensajeId, String tipoEvento, String cuerpo)
            throws SQLException {
        try (PreparedStatement insert = conexion.prepareStatement(
                "insert into " + ESQUEMA + ".registro_auditoria (mensaje_id, tipo_evento, venta_id, comprador_id,"
                        + " evento_id, monto_total, cuerpo, recibido_en) values (?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, mensajeId);
            insert.setString(2, tipoEvento);
            insert.setString(3, "V-3f9a1c2b");
            insert.setString(4, "user-titular");
            insert.setString(5, "ev-1");
            insert.setLong(6, 60500);
            insert.setString(7, cuerpo);
            insert.setTimestamp(8, Timestamp.from(Instant.parse("2026-10-16T21:30:05Z")));
            assertEquals(1, insert.executeUpdate());
        }
    }
}
