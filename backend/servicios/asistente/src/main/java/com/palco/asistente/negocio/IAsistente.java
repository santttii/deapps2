package com.palco.asistente.negocio;

import jakarta.ejb.Local;

/** El asistente de compradores: responde dudas sobre Palco. Solo lee: no compra ni publica. */
@Local
public interface IAsistente {

    Respuesta responder(String usuarioId, String pregunta);
}
