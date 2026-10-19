package com.palco.facturacion.negocio.externos;

import java.util.ArrayList;
import java.util.List;

import com.palco.common.ErroresDeNegocio;
import com.palco.facturacion.negocio.TiposDeProblema;

/**
 * Un ARCA para los tests del negocio y del MDB, sin SOAP: numera desde 1,
 * guarda lo que se le pidió y se puede poner "caído" o "que rechace". El
 * cliente SOAP de verdad se prueba aparte, en {@code ArcaSoapTest}.
 */
public class ArcaEnMemoria implements Arca {

    public enum Modo { RESPONDE, NO_RESPONDE, RECHAZA }

    public Modo modo = Modo.RESPONDE;
    public final List<SolicitudDeCae> pedidos = new ArrayList<>();
    private long ultimo;

    @Override
    public ComprobanteAutorizado autorizar(SolicitudDeCae solicitud) {
        pedidos.add(solicitud);
        return switch (modo) {
            case NO_RESPONDE -> throw new ErroresDeNegocio.SinRespuesta("ARCA no respondió el pedido de CAE.");
            case RECHAZA -> throw new ErroresDeNegocio.ReglaIncumplida(TiposDeProblema.COMPROBANTE_RECHAZADO,
                    "ARCA rechazó el comprobante: importe inválido");
            case RESPONDE -> {
                ultimo++;
                yield new ComprobanteAutorizado(ultimo, String.format("7512345678%04d", ultimo),
                        solicitud.fecha().plusDays(10));
            }
        };
    }
}
