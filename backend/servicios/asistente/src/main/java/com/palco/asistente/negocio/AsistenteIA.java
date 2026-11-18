package com.palco.asistente.negocio;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

import jakarta.annotation.Resource;
import jakarta.ejb.Stateless;
import jakarta.enterprise.concurrent.ManagedExecutorService;
import jakarta.inject.Inject;

import com.palco.asistente.negocio.Respuesta.Origen;
import com.palco.asistente.negocio.externos.ModeloDeLenguaje;

/**
 * El gateway de IA (patrón Facade): el único punto por el que el sistema llega al modelo.
 *
 * <ol>
 *   <li>Si el asistente está apagado ({@code palco.asistente.habilitado=false}), responde el
 *       fallback sin llamar al modelo. Es el plan de apagado: se cambia sin redesplegar.</li>
 *   <li>Guardrails de entrada: largo, inyección de prompt, pedidos de datos de terceros, y
 *       enmascarar DNI, tarjetas y emails antes de que salgan hacia el modelo.</li>
 *   <li>El modelo, con un tiempo máximo de 5 segundos. Si no responde a tiempo o falla, fallback:
 *       la respuesta frecuente que corresponda o, si no hay, ofrecer soporte.</li>
 *   <li>Guardrails de salida: una respuesta con datos personales no se muestra.</li>
 *   <li>Cada consulta deja una línea de registro (evidencia), con la pregunta enmascarada.</li>
 * </ol>
 *
 * Stateless: cada pregunta llega completa y no se guarda conversación.
 */
@Stateless
public class AsistenteIA implements IAsistente {

    /** El tiempo máximo que se le da al modelo. */
    public static final Duration TIEMPO_MAXIMO = Duration.ofSeconds(5);

    static final String PROPIEDAD_HABILITADO = "palco.asistente.habilitado";

    static final String INSTRUCCIONES = """
            Sos el asistente de Palco, una plataforma de entradas nominativas con reventa oficial.
            Respondé en español, en una o dos oraciones, solo sobre compras, entradas, reventa y la puerta.
            No tenés acceso a datos de otros usuarios y no podés comprar, publicar ni cancelar.
            Si no sabés algo, decilo y sugerí hablar con soporte.""";

    static final String SOPORTE =
            "No pude responder eso ahora. Podés revisar las preguntas frecuentes o escribirle a soporte.";
    static final String BLOQUEO =
            "No puedo ayudarte con eso. Puedo responder dudas sobre compras, entradas, reventa y la puerta.";

    private static final Logger LOG = Logger.getLogger(AsistenteIA.class.getName());

    @Inject
    ModeloDeLenguaje modelo;

    @Resource
    ManagedExecutorService ejecutorDelContenedor;

    // Se pueden reemplazar en los tests.
    ExecutorService ejecutor;
    Duration tiempoMaximo = TIEMPO_MAXIMO;
    BooleanSupplier habilitado = () -> Configuracion.leer(PROPIEDAD_HABILITADO, Boolean.class, true);

    @Override
    public Respuesta responder(String usuarioId, String pregunta) {
        long inicio = System.nanoTime();
        Respuesta respuesta = resolver(pregunta, inicio);
        registrar(usuarioId, pregunta, respuesta);
        return respuesta;
    }

    private Respuesta resolver(String pregunta, long inicio) {
        if (!habilitado.getAsBoolean()) {
            return fallback(pregunta, "apagado", inicio);
        }
        Guardrails.Revision revision = Guardrails.revisarEntrada(pregunta);
        if (revision.bloqueada()) {
            return new Respuesta(BLOQUEO, Origen.BLOQUEADA, revision.motivo(), false, ms(inicio));
        }
        String texto;
        Future<String> pedido = ejecutor().submit(() -> modelo.completar(INSTRUCCIONES, revision.texto()));
        try {
            texto = pedido.get(tiempoMaximo.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pedido.cancel(true);
            return fallback(pregunta, "timeout", inicio);
        } catch (ExecutionException e) {
            LOG.warning("Asistente: el modelo falló: " + e.getCause());
            return fallback(pregunta, "error-del-modelo", inicio);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback(pregunta, "error-del-modelo", inicio);
        }
        if (texto == null || texto.isBlank() || Guardrails.salidaConDatosPersonales(texto)) {
            return new Respuesta(BLOQUEO, Origen.BLOQUEADA,
                    texto == null || texto.isBlank() ? "respuesta-vacia" : "salida-con-datos-personales", true, ms(inicio));
        }
        return new Respuesta(texto.strip(), Origen.MODELO, null, false, ms(inicio));
    }

    private Respuesta fallback(String pregunta, String motivo, long inicio) {
        return RespuestasFrecuentes.buscar(pregunta == null ? "" : pregunta)
                .map(frecuente -> new Respuesta(frecuente, Origen.FALLBACK, motivo, false, ms(inicio)))
                .orElseGet(() -> new Respuesta(SOPORTE, Origen.FALLBACK, motivo, true, ms(inicio)));
    }

    private ExecutorService ejecutor() {
        return ejecutor != null ? ejecutor : ejecutorDelContenedor;
    }

    private void registrar(String usuarioId, String pregunta, Respuesta r) {
        String enmascarada = pregunta == null ? "" : Guardrails.enmascarar(pregunta).replaceAll("\\s+", " ");
        if (enmascarada.length() > 120) {
            enmascarada = enmascarada.substring(0, 120) + "…";
        }
        LOG.info("Asistente: usuario=" + usuarioId + " modelo=" + modelo.nombre() + " origen=" + r.origen()
                + " motivo=" + r.motivo() + " ms=" + r.milisegundos() + " pregunta=\"" + enmascarada + "\"");
    }

    private static long ms(long inicio) {
        return (System.nanoTime() - inicio) / 1_000_000;
    }
}
