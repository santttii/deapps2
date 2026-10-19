package com.palco.ventas.negocio;

import jakarta.ejb.Stateless;
import jakarta.inject.Inject;

import com.palco.common.ErroresDeNegocio;
import com.palco.ventas.api.Dtos.EntradaDto;
import com.palco.ventas.datos.EntradaRepository;
import com.palco.ventas.dominio.Entrada;
import com.palco.ventas.dominio.EstadoEntrada;

/**
 * El uso de una entrada en la puerta. Ventas es la dueña de las entradas, así
 * que el cambio a {@code USADA} se hace acá; ServicioDeValidación lo pide por
 * REST cada vez que el personal de puerta escanea un código.
 *
 * Stateless: cada escaneo es independiente y no recuerda nada del anterior.
 * Lo que tiene que durar (que la entrada ya se usó) queda en la base, así que
 * cualquier instancia del pool atiende cualquier escaneo, de cualquier puerta.
 * No es parte de {@link IVentas}: esa interfaz es la conversación de una
 * compra, que vive en un bean stateful, y usar una entrada no tiene nada que
 * ver con esa conversación.
 *
 * Corre en una transacción (la de defecto de un Session Bean): la búsqueda, el
 * UPDATE condicional y la relectura ven la misma base. El uso único no depende
 * de esa transacción sino de la condición del UPDATE (ver
 * {@link EntradaRepository#marcarUsada}).
 */
@Stateless
public class UsoDeEntradas {

    @Inject
    EntradaRepository entradas;

    /**
     * Usa la entrada con ese código en el evento que controla la puerta y la
     * devuelve ya {@code USADA}, con el titular y el sector para contrastar
     * con el documento.
     *
     * Lanza 404 si ningún código coincide; 409 {@code entrada-de-otro-evento}
     * si es de otro evento (y no la toca: tiene que seguir sirviendo en el
     * suyo); 409 {@code entrada-ya-usada} si otro escaneo la usó antes, aunque
     * haya sido en el mismo instante; y 409 {@code entrada-no-habilitada} si
     * está publicada o vendida en la reventa, o anulada.
     */
    public EntradaDto marcarUsada(String codigoQr, String eventoId) {
        Entrada entrada = entradas.buscarPorCodigoQr(codigoQr)
                .orElseThrow(() -> new ErroresDeNegocio.NoEncontrado("No existe una entrada con ese código."));
        // El evento de una entrada no cambia nunca, así que leerlo antes del UPDATE es seguro.
        if (!entrada.getVenta().getEventoId().equals(eventoId)) {
            throw new ErroresDeNegocio.Conflicto(TiposDeProblema.ENTRADA_DE_OTRO_EVENTO,
                    "La entrada es de otro evento.");
        }
        // No se mira el estado leído para decidir: puede haber cambiado. Decide el UPDATE condicional.
        boolean laUsoEsteEscaneo = entradas.marcarUsada(entrada.getId());
        Entrada actual = entradas.buscarPorId(entrada.getId()).orElseThrow(
                () -> new ErroresDeNegocio.NoEncontrado("No existe una entrada con ese código."));
        if (laUsoEsteEscaneo) {
            return ServicioDeVentas.aDto(actual);
        }
        if (actual.getEstado() == EstadoEntrada.USADA) {
            throw new ErroresDeNegocio.Conflicto(TiposDeProblema.ENTRADA_YA_USADA, "La entrada ya se usó.");
        }
        throw new ErroresDeNegocio.Conflicto(TiposDeProblema.ENTRADA_NO_HABILITADA,
                "La entrada no sirve para entrar: está " + actual.getEstado().name() + ".");
    }
}
