package com.palco.eventos.negocio;

/**
 * Los "tipos" de problema propios de Eventos. Cada uno sale en el error como
 * {@code "type": "/problemas/<tipo>"} y es parte del contrato de la API: quien
 * llama decide según el tipo, no según el texto.
 */
public final class TiposDeProblema {

    /** 409: el evento ya empezó, así que no se reservan ni se venden más lugares. */
    public static final String EVENTO_FINALIZADO = "evento-finalizado";

    private TiposDeProblema() {
    }
}
