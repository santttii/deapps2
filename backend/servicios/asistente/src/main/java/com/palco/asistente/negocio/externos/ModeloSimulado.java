package com.palco.asistente.negocio.externos;

import java.util.logging.Logger;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;

import com.palco.asistente.negocio.Configuracion;
import com.palco.asistente.negocio.Texto;
import com.palco.common.ReglasDeNegocio;

/**
 * Modelo simulado: responde con reglas fijas sobre Palco, sin llamar a ningún proveedor. Sirve
 * para desarrollar, probar y demostrar el gateway sin una clave de API.
 *
 * Con la propiedad {@code palco.asistente.simulado.demora-ms} (se lee en cada pedido) tarda
 * lo que se le indique: así se demuestra el timeout de 5 segundos y el fallback sin redesplegar.
 */
@ApplicationScoped
@Typed(ModeloSimulado.class)
public class ModeloSimulado implements ModeloDeLenguaje {

    static final String PROPIEDAD_DEMORA = "palco.asistente.simulado.demora-ms";
    private static final Logger LOG = Logger.getLogger(ModeloSimulado.class.getName());
    // Un imperativo al empezar la frase (o después de dos puntos): "Comprá 6 entradas", "publicá mi entrada".
    // Al normalizar, "comprá" queda "compra".
    private static final Pattern PEDIDO_DE_ACCION =
            Pattern.compile("(^|[:.]\\s*)(compra|publica|cancela|transferi|reserva)\\b");

    @Override
    public String nombre() {
        return "simulado-1";
    }

    @Override
    public String completar(String instrucciones, String pregunta) {
        esperarSiSePidio();
        String p = Texto.normalizar(pregunta);

        if (p.contains("[tarjeta oculta]") || p.contains("[dni oculto]")) {
            return "Por seguridad no proceso datos de tarjetas ni documentos: no los compartas en el chat.";
        }
        if (PEDIDO_DE_ACCION.matcher(p).find()) {
            return "Soy un asistente de solo lectura: no puedo comprar, publicar ni cancelar. "
                    + "Eso lo hacés vos desde la app, en el evento o en Mis entradas.";
        }
        if (Texto.contieneAlguna(p, "revend", "reventa") && Texto.contieneAlguna(p, "hasta cuando", "cuando", "hora")) {
            return "Podés revender hasta " + ReglasDeNegocio.HORAS_LIMITE_REVENTA + " horas antes del evento.";
        }
        // Antes que el tope de la reventa: "¿cuál es el máximo de entradas?" tiene la palabra "máximo".
        if (Texto.contieneAlguna(p, "cuantas entradas", "cuantas puedo", "maximo de entradas", "limite de entradas")) {
            return "Hasta " + ReglasDeNegocio.MAX_ENTRADAS_POR_ORDEN + " entradas por compra y "
                    + ReglasDeNegocio.MAX_ENTRADAS_POR_EVENTO + " por evento, sumando todas tus compras. "
                    + "Cada persona (DNI) puede tener una sola entrada por evento.";
        }
        if (Texto.contieneAlguna(p, "maximo", "tope", "mas cara", "cuanto la puedo vender", "a cuanto")) {
            return "Como máximo podés pedir el precio original de la entrada más un " + ReglasDeNegocio.TOPE_REVENTA_PORCENTAJE
                    + " %, redondeado hacia abajo.";
        }
        if (Texto.contieneAlguna(p, "minimo", "mas barata")) {
            return "Como mínimo, el " + ReglasDeNegocio.MINIMO_REVENTA_PORCENTAJE + " % del precio original de la entrada.";
        }
        if (Texto.contieneAlguna(p, "tiempo", "minutos", "cuanto tengo para pagar")) {
            return "Tenés " + ReglasDeNegocio.MINUTOS_RESERVA
                    + " minutos de reserva para pagar; después los lugares se liberan.";
        }
        if (Texto.contieneAlguna(p, "cargo", "comision", "me cobran")) {
            return "Es el cargo de servicio: el " + ReglasDeNegocio.TASA_SERVICIO_PORCENTAJE + " % del subtotal.";
        }
        if (Texto.contieneAlguna(p, "nombre de otra", "a nombre de", "otra persona")) {
            return "Cada entrada es nominativa. Para pasársela a otra persona, publicala en la reventa oficial.";
        }
        if (Texto.contieneAlguna(p, "sacar", "retirar")) {
            return "Sí: desde Mis entradas podés retirar la publicación y la entrada vuelve a estar válida.";
        }
        if (Texto.contieneAlguna(p, "me compraron", "sigue sirviendo", "la mia sigue")) {
            return "No: cuando te la compran queda vendida y su QR deja de servir en la puerta.";
        }
        // Cancelar mi compra no es lo mismo que se cancele el evento ("se canceló", "lo cancelaron", "suspendido").
        boolean seCanceloElEvento = Texto.contieneAlguna(p, "se cancelo", "cancelaron", "cancelado", "cancelada", "suspend");
        if (Texto.contieneAlguna(p, "cancel") && !seCanceloElEvento) {
            return "Podés cancelar una compra mientras no la pagaste: en el checkout, con Cancelar compra. "
                    + "Si ya está paga no se cancela; si no podés ir, publicala en la reventa oficial.";
        }
        if (Texto.contieneAlguna(p, "cancel", "reembols", "mi plata", "devuelv")) {
            return "Si se cancela el evento, la compra se anula y se te devuelve el dinero. Te avisamos por email.";
        }
        if (Texto.contieneAlguna(p, "llevar", "puerta", "entrar al")) {
            return "Llevá el QR de tu entrada y el DNI del titular: en la puerta los comparan.";
        }
        if (Texto.contieneAlguna(p, "factura")) {
            return "La factura se emite sola después de pagar. La ves en Mis entradas, con el botón Ver factura: "
                    + "tiene su número y el CAE de ARCA.";
        }
        if (Texto.contieneAlguna(p, "mis entradas", "que evento tengo", "listas", "mis compras")) {
            return "Tus compras y entradas, con su estado y su QR, están en Mis entradas.";
        }
        return "Solo puedo responder dudas sobre Palco: compras, entradas, reventa y la puerta del evento.";
    }

    private void esperarSiSePidio() {
        long demora = Configuracion.leer(PROPIEDAD_DEMORA, Long.class, 0L);
        if (demora > 0) {
            LOG.info("Modelo simulado: demora pedida de " + demora + " ms.");
            try {
                Thread.sleep(demora);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
