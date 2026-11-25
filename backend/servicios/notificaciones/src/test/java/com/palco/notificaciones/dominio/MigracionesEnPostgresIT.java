package com.palco.notificaciones.dominio;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.palco.common.datos.BaseDeDatos;

/**
 * La migración V3 contra un PostgreSQL de verdad: la imagen del servicio
 * {@code db} del docker-compose, levantada en un contenedor con
 * Testcontainers. Es el mismo caso de
 * {@link MigracionesEnH2Test#laV3LeSacaElDniALasFilasQueYaEstaban}: la V3 usa
 * {@code regexp_replace}, y la marca {@code 'g'} tiene que reemplazar todas las
 * apariciones en las dos bases.
 *
 * Lo corre failsafe en "mvn verify" (ver backend/pom.xml). Sin Docker se
 * saltea con un aviso.
 */
@EnabledIfSystemProperty(named = "palco.pruebas.integracion", matches = "true",
        disabledReason = "Lo corre failsafe en mvn verify (-Dpalco.pruebas.integracion=true)")
class MigracionesEnPostgresIT {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private PGSimpleDataSource base;

    @BeforeAll
    static void levantarPostgres() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Sin Docker no hay PostgreSQL en contenedor: se saltea (el mismo caso corre en MigracionesEnH2Test)");
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
        // Cada test arranca de cero: se borra el esquema y Flyway lo vuelve a crear.
        try (Connection conexion = base.getConnection(); Statement sql = conexion.createStatement()) {
            sql.execute("drop schema if exists " + CuerposDeAntesDeLaV3.ESQUEMA + " cascade");
        }
    }

    @Test
    void laV3LeSacaElDniALasFilasQueYaEstaban() throws Exception {
        CuerposDeAntesDeLaV3.migrarHastaLaV2YGuardarFilasConDni(base);

        BaseDeDatos.migrar(base, CuerposDeAntesDeLaV3.ESQUEMA);

        CuerposDeAntesDeLaV3.comprobarQueQuedaronSinDni(base);
    }
}
