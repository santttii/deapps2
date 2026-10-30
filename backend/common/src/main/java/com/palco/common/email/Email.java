package com.palco.common.email;

import com.palco.common.Textos;

/**
 * Un email por mandar: a quién, con qué asunto y qué dice. El cuerpo es texto
 * plano, sin HTML: los avisos de Palco son cortos (un código de 6 dígitos, el
 * detalle de una compra) y así no hay que escapar nada ni mantener plantillas.
 *
 * El asunto no puede llevar saltos de línea: una cabecera SMTP termina en el
 * primer salto, y un asunto armado con texto que escribió alguien podría
 * agregar cabeceras propias (inyección de cabeceras). El constructor los
 * aplasta con {@link Textos#enUnaLinea}; el cuerpo no hace falta, que va
 * después de las cabeceras.
 */
public record Email(String para, String asunto, String cuerpo) {

    public Email {
        if (para == null || para.isBlank()) {
            throw new IllegalArgumentException("El email necesita un destinatario.");
        }
        para = para.strip();
        asunto = asunto == null ? "" : Textos.enUnaLinea(asunto).strip();
        cuerpo = cuerpo == null ? "" : cuerpo;
    }
}
