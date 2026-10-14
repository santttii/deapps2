package com.palco.usuarios.seguridad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * La cuenta de V3 (otro.titular@palco.test) se carga igual que las de V2: mismas columnas, rol user,
 * email e identidad verificados y la contraseña palco1234. Se comprueba leyendo el SQL de las
 * migraciones, que es lo que ejecuta Flyway al desplegar.
 */
class CuentasDePruebaTest {

    private static final String COLUMNAS = "insert into usuario (id, nombre, apellido, dni, email, password_hash, rol, "
            + "email_verificado, identidad_verificada, creado) values";

    /** Una fila de los insert: ('id', 'nombre', 'apellido', 'dni', 'email', 'hash', 'rol', bool, bool, 'fecha'). */
    private static final Pattern FILA = Pattern.compile(
            "\\('([^']*)', '([^']*)', '([^']*)', '([^']*)', '([^']*)',\\s*'([^']*)',\\s*'([^']*)', (true|false), (true|false), '([^']*)'\\)");

    private static String leer(String archivo) throws IOException {
        try (InputStream in = CuentasDePruebaTest.class.getResourceAsStream("/db/migracion/" + archivo)) {
            assertNotNull(in, "falta la migración " + archivo);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String sinEspacios(String sql) {
        return sql.replaceAll("\\s+", " ");
    }

    private static List<String[]> filas(String sql) {
        List<String[]> filas = new ArrayList<>();
        Matcher m = FILA.matcher(sql);
        while (m.find()) {
            String[] fila = new String[10];
            for (int i = 0; i < 10; i++) {
                fila[i] = m.group(i + 1);
            }
            filas.add(fila);
        }
        return filas;
    }

    @Test
    void laSegundaCompradoraSeCargaComoLasDeV2() throws IOException {
        String v2 = leer("V2__usuarios_de_prueba.sql");
        String v3 = leer("V3__segundo_comprador_de_prueba.sql");

        assertTrue(sinEspacios(v2).contains(COLUMNAS), "V2 cambió de columnas: revisar este test");
        assertTrue(sinEspacios(v3).contains(COLUMNAS), "V3 tiene que insertar las mismas columnas que V2");

        List<String[]> deV2 = filas(v2);
        List<String[]> deV3 = filas(v3);
        assertEquals(3, deV2.size());
        assertEquals(1, deV3.size());

        String[] otra = deV3.get(0);
        assertEquals("otro.titular@palco.test", otra[4]);
        assertEquals("user", otra[6]);
        assertEquals("true", otra[7], "email_verificado");
        assertEquals("true", otra[8], "identidad_verificada");
        assertTrue(Contrasenas.verificar("palco1234", otra[5]), "la contraseña tiene que ser palco1234");

        String[] titular = deV2.get(0);
        assertEquals("titular@palco.test", titular[4]);
        assertEquals(titular[5], otra[5], "usa el mismo hash que titular@palco.test");
        assertEquals(titular[9], otra[9], "misma fecha de creación que las de V2");

        for (String[] cuenta : deV2) {
            assertFalse(cuenta[0].equals(otra[0]), "id repetido: " + otra[0]);
            assertFalse(cuenta[3].equals(otra[3]), "DNI repetido: " + otra[3]);
            assertFalse(cuenta[4].equals(otra[4]), "email repetido: " + otra[4]);
        }
        assertTrue(otra[0].length() <= 40 && otra[3].length() <= 8, "respeta los largos de V1");
    }
}
