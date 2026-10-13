package com.palco.pagos.negocio;

import java.util.logging.Logger;

import javax.sql.DataSource;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;

import com.palco.common.datos.BaseDeDatos;
import com.palco.pagos.pasarela.PasarelaDePagos;

/**
 * Al desplegar, crea o actualiza las tablas del esquema "pagos" (db/migracion)
 * y deja en el log qué pasarela quedó activa.
 *
 * Singleton con {@code @Startup}: el contenedor crea una sola instancia al
 * desplegar y corre esto una vez, antes de atender pedidos.
 */
@Startup
@Singleton
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class MigracionesPagos {

    private static final Logger LOG = Logger.getLogger(MigracionesPagos.class.getName());

    @Resource(lookup = BaseDeDatos.JNDI)
    DataSource dataSource;

    @Inject
    PasarelaDePagos pasarela;

    @PostConstruct
    void migrar() {
        BaseDeDatos.migrar(dataSource, "pagos");
        // La pasarela se crea recién la primera vez que alguien la usa. Se la usa acá, al
        // desplegar, para que una configuración equivocada impida el despliegue con un
        // mensaje claro, en lugar de aparecer como un 500 en el primer cobro.
        LOG.info("Pasarela de pagos activa: " + pasarela.nombre());
    }
}
