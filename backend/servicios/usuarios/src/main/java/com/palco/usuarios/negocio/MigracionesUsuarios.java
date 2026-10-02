package com.palco.usuarios.negocio;

import javax.sql.DataSource;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;

import com.palco.common.datos.BaseDeDatos;

/** Al desplegar, crea o actualiza las tablas del esquema "usuarios" (db/migracion). */
@Startup
@Singleton
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class MigracionesUsuarios {

    @Resource(lookup = BaseDeDatos.JNDI)
    DataSource dataSource;

    @PostConstruct
    void migrar() {
        BaseDeDatos.migrar(dataSource, "usuarios");
    }
}
