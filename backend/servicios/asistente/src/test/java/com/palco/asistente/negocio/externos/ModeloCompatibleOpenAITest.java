package com.palco.asistente.negocio.externos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

import org.junit.jupiter.api.Test;

import com.palco.common.ReglasDeNegocio;

/** Qué le manda el asistente a un proveedor con la API de chat de OpenAI y qué hace con cada respuesta. Sin red. */
class ModeloCompatibleOpenAITest {

    private static final Jsonb JSONB = JsonbBuilder.create();

    private static class ProveedorSinRed extends ModeloCompatibleOpenAI {
        final List<String> cuerpos = new ArrayList<>();
        final List<String> claves = new ArrayList<>();
        final List<String> urls = new ArrayList<>();
        Respuesta respuesta;
        String clave = "";

        @Override
        Respuesta enviar(String url, String clave, String cuerpo) {
            urls.add(url);
            claves.add(clave);
            cuerpos.add(cuerpo);
            return respuesta;
        }

        @Override
        String clave() {
            return clave;
        }
    }

    private static String completado(String texto) {
        return "{\"id\":\"chatcmpl-1\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\""
                + texto + "\"},\"finish_reason\":\"stop\"}],\"usage\":{\"total_tokens\":42}}";
    }

    private final ProveedorSinRed proveedor = new ProveedorSinRed();

    @Test
    @SuppressWarnings("unchecked")
    void mandaElModeloLasInstruccionesConLosDatosDePalcoYLaPreguntaAparte() {
        proveedor.respuesta = new ModeloCompatibleOpenAI.Respuesta(200, completado("Hasta 3 horas antes."));

        proveedor.completar("Sos el asistente de Palco.", "¿Hasta cuándo revendo?");

        Map<String, Object> pedido = JSONB.fromJson(proveedor.cuerpos.get(0), Map.class);
        assertEquals(ModeloCompatibleOpenAI.MODELO_POR_DEFECTO, pedido.get("model"));
        assertTrue(pedido.containsKey("max_tokens"));
        List<Map<String, String>> mensajes = (List<Map<String, String>>) pedido.get("messages");
        assertEquals(2, mensajes.size());
        assertEquals("system", mensajes.get(0).get("role"));
        assertTrue(mensajes.get(0).get("content").startsWith("Sos el asistente de Palco."));
        assertTrue(mensajes.get(0).get("content").contains("Datos de Palco"));
        // La pregunta va sola, en su propio mensaje: el usuario no escribe dentro de las instrucciones.
        assertEquals(Map.of("role", "user", "content", "¿Hasta cuándo revendo?"), mensajes.get(1));
        assertEquals(List.of(ModeloCompatibleOpenAI.URL_POR_DEFECTO), proveedor.urls);
    }

    @Test
    void devuelveElTextoDeLaPrimeraOpcion() {
        proveedor.respuesta = new ModeloCompatibleOpenAI.Respuesta(200, completado("Hasta 6 entradas por compra."));

        assertEquals("Hasta 6 entradas por compra.", proveedor.completar("instrucciones", "¿Cuántas entradas?"));
    }

    @Test
    void laClaveSeMandaSoloSiEstaConfigurada() {
        proveedor.respuesta = new ModeloCompatibleOpenAI.Respuesta(200, completado("ok"));

        proveedor.completar("instrucciones", "pregunta");
        proveedor.clave = "clave-de-prueba";
        proveedor.completar("instrucciones", "pregunta");

        assertEquals(List.of("", "clave-de-prueba"), proveedor.claves);
    }

    /** El gateway convierte cualquier falla del modelo en el fallback (AsistenteIATest). */
    @Test
    void unErrorDelProveedorEsUnaFalla() {
        proveedor.respuesta = new ModeloCompatibleOpenAI.Respuesta(401, "{\"error\":{\"message\":\"clave inválida\"}}");

        assertThrows(IllegalStateException.class, () -> proveedor.completar("instrucciones", "pregunta"));
    }

    @Test
    void unaRespuestaSinTextoEsUnaFalla() {
        proveedor.respuesta = new ModeloCompatibleOpenAI.Respuesta(200, "{\"choices\":[]}");
        assertThrows(IllegalStateException.class, () -> proveedor.completar("instrucciones", "pregunta"));

        proveedor.respuesta = new ModeloCompatibleOpenAI.Respuesta(200, "<html>no es JSON</html>");
        assertThrows(IllegalStateException.class, () -> proveedor.completar("instrucciones", "pregunta"));
    }

    @Test
    void elTextoSeLeeDeLaPrimeraOpcionYUnaRespuestaRaraNoLoTiene() {
        assertEquals("hola", ModeloCompatibleOpenAI.texto(completado("hola")));
        assertNull(ModeloCompatibleOpenAI.texto(null));
        assertNull(ModeloCompatibleOpenAI.texto("{}"));
        assertNull(ModeloCompatibleOpenAI.texto("{\"choices\":[{\"message\":null}]}"));
    }

    @Test
    void losDatosDePalcoSalenDeLasReglasDeNegocio() {
        String datos = ModeloCompatibleOpenAI.datosDePalco();

        assertTrue(datos.contains("hasta " + ReglasDeNegocio.MAX_ENTRADAS_POR_ORDEN + " entradas por compra"));
        assertTrue(datos.contains(ReglasDeNegocio.MINUTOS_RESERVA + " minutos"));
        assertTrue(datos.contains(ReglasDeNegocio.TASA_SERVICIO_PORCENTAJE + " % del subtotal"));
        assertTrue(datos.contains(ReglasDeNegocio.HORAS_LIMITE_REVENTA + " horas antes del evento"));
        assertTrue(datos.contains("más un " + ReglasDeNegocio.TOPE_REVENTA_PORCENTAJE + " %"));
    }

    @Test
    void elNombreDiceElProveedorYElModelo() {
        assertEquals("openai-compatible/" + ModeloCompatibleOpenAI.MODELO_POR_DEFECTO, proveedor.nombre());
    }
}
