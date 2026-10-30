package com.palco.common.datos;

import java.sql.SQLException;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;

/**
 * Acceso a la base compartida. Cada servicio es dueño de su propio esquema
 * (eventos, usuarios, ventas…) y no lee ni escribe tablas de otro: si
 * necesita datos de otro servicio, se los pide por REST o JMS.
 *
 * La base concreta (H2 en desarrollo, PostgreSQL en Supabase) la decide el
 * datasource de WildFly; los servicios no se enteran.
 */
public final class BaseDeDatos {

    /** Datasource que configura el módulo {@code servidor}. */
    public static final String JNDI = "java:jboss/datasources/PalcoDS";

    /** Código estándar de SQL (SQLState) para "clave duplicada"; es el mismo en H2 y en PostgreSQL. */
    private static final String SQLSTATE_CLAVE_DUPLICADA = "23505";

    private BaseDeDatos() {
    }

    /**
     * Dice si la falla se debe a una clave repetida (primaria o única).
     * JPA envuelve el error de la base en varias excepciones, así que se
     * recorren las causas hasta encontrar la {@link SQLException}. Los
     * repositorios la usan para responder 409 solo en ese caso, y no ante
     * cualquier falla de la base.
     */
    public static boolean esClaveDuplicada(Throwable e) {
        for (Throwable causa = e; causa != null; causa = causa.getCause()) {
            if (causa instanceof SQLException sql && SQLSTATE_CLAVE_DUPLICADA.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    /**
     * El candado de las migraciones, compartido por todos los WAR del mismo
     * WildFly. Cada WAR tiene su propia copia de esta clase (otro class
     * loader), así que un {@code synchronized} sobre la clase no los ordena;
     * un literal de texto sí, porque la JVM guarda una sola instancia de cada
     * literal para todos los class loaders.
     */
    private static final Object CANDADO_DE_MIGRACIONES = "palco.migraciones";

    /**
     * Crea o actualiza las tablas del esquema del servicio corriendo los
     * scripts de {@code src/main/resources/db/migracion} (V1__..., V2__...).
     * Los scripts tienen que ser SQL compatible con PostgreSQL.
     *
     * Los servicios migran de a uno. Al desplegar, WildFly arranca los 8 WAR a
     * la vez, y en H2 un {@code CREATE} o {@code ALTER TABLE} bloquea la tabla
     * del sistema: con varias migraciones al mismo tiempo, alguna esperaba más
     * que el tiempo de espera ("Tiempo vencido intentando trabar la tabla
     * SYS") y su WAR no desplegaba. En PostgreSQL no hace falta, pero tampoco
     * molesta: es una sola vez por despliegue y cada migración tarda poco.
     */
    public static void migrar(DataSource dataSource, String esquema) {
        synchronized (CANDADO_DE_MIGRACIONES) {
            Flyway.configure(BaseDeDatos.class.getClassLoader())
                    .dataSource(dataSource)
                    .schemas(esquema)
                    .createSchemas(true)
                    .locations("classpath:db/migracion")
                    .load()
                    .migrate();
        }
    }
}
