package com.palco.facturacion.mensajeria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Clock;

import jakarta.ejb.MessageDrivenContext;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;

import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import com.palco.common.mensajeria.DestinosJms;
import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.facturacion.Ejemplos;
import com.palco.facturacion.datos.FacturaRepositoryEnMemoria;
import com.palco.facturacion.negocio.ServicioDeFacturacionDePrueba;
import com.palco.facturacion.negocio.externos.ArcaEnMemoria;

/**
 * El consumidor de las compras confirmadas contra un broker de verdad:
 * ActiveMQ Artemis 2.54.0, el que trae WildFly 41, levantado en un contenedor
 * con Testcontainers (imagen {@code apache/artemis}). Lo que en
 * {@link FacturacionDeComprasMDBTest} hacen los dobles, acá lo hace el broker:
 * la suscripción durable con el clientId y el nombre del MDB, el selector por
 * {@code tipoEvento}, el mensaje de texto y la reentrega después de deshacer,
 * con el {@code JMSXDeliveryCount} que cuenta el broker.
 *
 * El MDB y el negocio son los del servidor; ARCA y el repositorio, los de
 * memoria de los tests unitarios (el repositorio contra PostgreSQL lo prueba
 * {@code FacturaEnPostgresIT}). La transacción que en el servidor abre el
 * contenedor la hace acá una sesión JMS transaccional: si el MDB pidió
 * {@code setRollbackOnly()}, el test la deshace y el broker reentrega; si no,
 * la confirma. La política de reintentos del servidor (una entrega cada 2 s,
 * cinco en total y después la DLQ) es configuración de WildFly: esa la prueba
 * el escenario 12 contra el docker-compose.
 *
 * Lo corre failsafe en "mvn verify" (ver backend/pom.xml). Sin Docker se
 * saltea con un aviso.
 */
@EnabledIfSystemProperty(named = "palco.pruebas.integracion", matches = "true",
        disabledReason = "Lo corre failsafe en mvn verify (-Dpalco.pruebas.integracion=true)")
class FacturacionDeComprasEnArtemisIT {

    /** La dirección del tópico en el servidor ({@code jms-topic add} en configurar.cli). */
    private static final String TOPICO = "topico.compra-confirmada";
    private static final int PUERTO = 61616;

    /** Un broker para todos los tests de la clase; cada test borra la suscripción al terminar. */
    private static final GenericContainer<?> ARTEMIS = new GenericContainer<>("apache/artemis:2.54.0")
            .withEnv("ANONYMOUS_LOGIN", "true")
            .withExposedPorts(PUERTO)
            .waitingFor(Wait.forLogMessage(".*AMQ221007.*\\n", 1));

    private final FacturaRepositoryEnMemoria repositorio = new FacturaRepositoryEnMemoria();
    private final ArcaEnMemoria arca = new ArcaEnMemoria();
    private final FacturacionDeComprasMDB mdb = new FacturacionDeComprasMDB();
    private ConnectionFactory fabrica;
    /** Si el MDB pidió deshacer la última entrega ({@code setRollbackOnly()}). */
    private boolean pidioDeshacer;

    @BeforeAll
    static void levantarArtemis() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Sin Docker no hay broker en contenedor: se saltea (el MDB con dobles corre en FacturacionDeComprasMDBTest)");
        ARTEMIS.start();
    }

    @AfterAll
    static void bajarArtemis() {
        ARTEMIS.stop();
    }

    @BeforeEach
    void prepararElMdb() {
        MessageDrivenContext contexto = mock(MessageDrivenContext.class);
        doAnswer(invocacion -> {
            pidioDeshacer = true;
            return null;
        }).when(contexto).setRollbackOnly();
        mdb.facturacion = ServicioDeFacturacionDePrueba.con(repositorio, arca, Clock.systemUTC());
        mdb.contexto = contexto;
        fabrica = new ActiveMQConnectionFactory("tcp://" + ARTEMIS.getHost() + ":" + ARTEMIS.getMappedPort(PUERTO));
    }

    @AfterEach
    void borrarLaSuscripcion() throws JMSException {
        // El broker es el mismo para toda la clase: sin esto, la suscripción durable y lo que tenga
        // guardado pasarían al test siguiente.
        try (Connection conexion = conexionDelMdb();
                Session sesion = conexion.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
            sesion.unsubscribe(FacturacionDeComprasMDB.SUSCRIPCION);
        }
    }

    /** Como publica Ventas: el JSON del contrato en un mensaje de texto, con el tipoEvento en la propiedad. */
    private void publicar(String tipoEvento, PagoConfirmadoMensaje compra) throws JMSException {
        try (Connection conexion = fabrica.createConnection();
                Session sesion = conexion.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
            TextMessage mensaje = sesion.createTextMessage(compra.aJson());
            mensaje.setStringProperty(DestinosJms.PROPIEDAD_TIPO_EVENTO, tipoEvento);
            sesion.createProducer(sesion.createTopic(TOPICO)).send(mensaje);
        }
    }

    /** La conexión del MDB: con su clientId, el broker la reconoce como dueña de la suscripción durable. */
    private Connection conexionDelMdb() throws JMSException {
        Connection conexion = fabrica.createConnection();
        conexion.setClientID(FacturacionDeComprasMDB.CLIENT_ID);
        conexion.start();
        return conexion;
    }

    /** La suscripción del MDB, con el nombre y el selector de su @MessageDriven. */
    private static MessageConsumer suscripcion(Session sesion) throws JMSException {
        return sesion.createDurableSubscriber(sesion.createTopic(TOPICO), FacturacionDeComprasMDB.SUSCRIPCION,
                DestinosJms.SELECTOR_COMPRA_CONFIRMADA, false);
    }

    /** Lo que hace el contenedor con cada entrega: le pasa el mensaje al MDB y confirma, o deshace si el MDB lo pidió. */
    private Message entregarAlMdb(MessageConsumer consumidor, Session sesion) throws JMSException {
        Message recibido = consumidor.receive(5_000);
        assertNotNull(recibido, "el broker no entregó ningún mensaje");
        pidioDeshacer = false;
        mdb.onMessage(recibido);
        if (pidioDeshacer) {
            sesion.rollback();
        } else {
            sesion.commit();
        }
        return recibido;
    }

    @Test
    void conElConsumidorDetenidoElBrokerLeGuardaLasComprasYElSelectorDejaAfueraLoQueNoEsUna() throws JMSException {
        // La suscripción la crea el MDB al desplegarse; después el consumidor se detiene.
        try (Connection conexion = conexionDelMdb();
                Session sesion = conexion.createSession(true, Session.SESSION_TRANSACTED)) {
            suscripcion(sesion).close();
        }
        publicar(DestinosJms.PAGO_CONFIRMADO, Ejemplos.pagoConfirmado("V-00000001"));
        // Un tipo que el selector no deja pasar: el MDB no tiene que verlo.
        publicar(DestinosJms.COMPRA_ANULADA, Ejemplos.pagoConfirmado("V-00000002"));
        publicar(DestinosJms.REVENTA_CONFIRMADA, Ejemplos.pagoConfirmado("V-00000003"));

        // Vuelve el consumidor: el broker le entrega lo que guardó mientras no estaba.
        try (Connection conexion = conexionDelMdb();
                Session sesion = conexion.createSession(true, Session.SESSION_TRANSACTED)) {
            MessageConsumer consumidor = suscripcion(sesion);
            entregarAlMdb(consumidor, sesion);
            entregarAlMdb(consumidor, sesion);
            assertNull(consumidor.receive(1_000), "el selector tenía que dejar afuera el COMPRA_ANULADA");
        }

        assertEquals(2, repositorio.cantidad());
        assertTrue(repositorio.buscarPorVenta("V-00000001").isPresent());
        assertTrue(repositorio.buscarPorVenta("V-00000002").isEmpty());
        assertTrue(repositorio.buscarPorVenta("V-00000003").isPresent());
    }

    @Test
    void conArcaSinResponderElMensajeVuelveAlBrokerYLaSegundaEntregaFactura() throws JMSException {
        try (Connection conexion = conexionDelMdb();
                Session sesion = conexion.createSession(true, Session.SESSION_TRANSACTED)) {
            MessageConsumer consumidor = suscripcion(sesion);
            publicar(DestinosJms.PAGO_CONFIRMADO, Ejemplos.pagoConfirmado());

            arca.modo = ArcaEnMemoria.Modo.NO_RESPONDE;
            Message primera = entregarAlMdb(consumidor, sesion);
            assertTrue(pidioDeshacer, "con ARCA sin responder el mensaje tiene que volver al broker");
            assertEquals(1, primera.getIntProperty("JMSXDeliveryCount"));
            assertEquals(0, repositorio.cantidad());

            // ARCA vuelve a responder: el broker reentrega el mismo mensaje y cuenta la entrega.
            arca.modo = ArcaEnMemoria.Modo.RESPONDE;
            Message segunda = entregarAlMdb(consumidor, sesion);
            assertFalse(pidioDeshacer);
            assertEquals(primera.getJMSMessageID(), segunda.getJMSMessageID());
            assertEquals(2, segunda.getIntProperty("JMSXDeliveryCount"));
            assertEquals(1, repositorio.cantidad());
            // Confirmada la entrega, el broker no la manda otra vez.
            assertNull(consumidor.receive(1_000));
        }
    }

    @Test
    void laMismaCompraPublicadaDosVecesSeFacturaUnaSolaVez() throws JMSException {
        try (Connection conexion = conexionDelMdb();
                Session sesion = conexion.createSession(true, Session.SESSION_TRANSACTED)) {
            MessageConsumer consumidor = suscripcion(sesion);
            publicar(DestinosJms.PAGO_CONFIRMADO, Ejemplos.pagoConfirmado());
            publicar(DestinosJms.PAGO_CONFIRMADO, Ejemplos.pagoConfirmado());

            entregarAlMdb(consumidor, sesion);
            entregarAlMdb(consumidor, sesion);

            // Las dos entregas se confirman (la segunda no tiene nada que hacer) y queda una factura.
            assertFalse(pidioDeshacer);
            assertEquals(1, repositorio.cantidad());
            assertEquals(1, arca.pedidos.size());
            assertNull(consumidor.receive(1_000));
        }
    }
}
