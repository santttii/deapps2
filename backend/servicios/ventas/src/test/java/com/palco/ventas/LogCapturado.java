package com.palco.ventas;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Junta lo que una clase escribe en su log mientras dura un test, para poder
 * mirar esas líneas. Se usa con try-with-resources: al cerrarse deja de
 * escuchar. Es la misma clase que tienen los tests de Notificaciones: los
 * tests de un servicio no pueden usar clases de los tests de otro.
 */
public final class LogCapturado extends Handler implements AutoCloseable {

    private final Logger log;
    private final List<String> lineas = new ArrayList<>();

    /** Empieza a juntar lo que escribe en el log la clase indicada. */
    public LogCapturado(Class<?> clase) {
        this.log = Logger.getLogger(clase.getName());
        this.log.addHandler(this);
    }

    /** Las líneas escritas hasta ahora, en orden. */
    public List<String> lineas() {
        return lineas;
    }

    @Override
    public void publish(LogRecord registro) {
        lineas.add(registro.getMessage());
    }

    @Override
    public void flush() {
        // No hay nada que vaciar: las líneas quedan en memoria.
    }

    @Override
    public void close() {
        log.removeHandler(this);
    }
}
