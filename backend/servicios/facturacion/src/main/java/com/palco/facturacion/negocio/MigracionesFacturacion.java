package com.palco.facturacion.negocio;

import javax.sql.DataSource;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;

import com.palco.common.datos.BaseDeDatos;

/**
 * Al desplegar, crea o actualiza las tablas del esquema "facturacion"
 * (db/migracion).
 *
 * Singleton con {@code @Startup}: el contenedor crea una sola instancia al
 * desplegar y corre esto una vez, antes de atender pedidos y mensajes.
 */
@Startup
@Singleton
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class MigracionesFacturacion {

    @Resource(lookup = BaseDeDatos.JNDI)
    DataSource dataSource;

    @PostConstruct
    void migrar() {
        BaseDeDatos.migrar(dataSource, "facturacion");
    }
}
