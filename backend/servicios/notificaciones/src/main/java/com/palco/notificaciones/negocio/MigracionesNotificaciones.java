package com.palco.notificaciones.negocio;

import javax.sql.DataSource;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;

import com.palco.common.datos.BaseDeDatos;

/** Al desplegar, crea o actualiza las tablas del esquema "notificaciones" (db/migracion). */
@Startup
@Singleton
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class MigracionesNotificaciones {

    @Resource(lookup = BaseDeDatos.JNDI)
    DataSource dataSource;

    @PostConstruct
    void migrar() {
        BaseDeDatos.migrar(dataSource, "notificaciones");
    }
}
