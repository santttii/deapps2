package com.palco.common.datos;

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

    private BaseDeDatos() {
    }

    /**
     * Crea o actualiza las tablas del esquema del servicio corriendo los
     * scripts de {@code src/main/resources/db/migracion} (V1__..., V2__...).
     * Los scripts tienen que ser SQL compatible con PostgreSQL.
     */
    public static void migrar(DataSource dataSource, String esquema) {
        Flyway.configure(BaseDeDatos.class.getClassLoader())
                .dataSource(dataSource)
                .schemas(esquema)
                .createSchemas(true)
                .locations("classpath:db/migracion")
                .load()
                .migrate();
    }
}
