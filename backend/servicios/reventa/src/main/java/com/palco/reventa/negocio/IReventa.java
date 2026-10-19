package com.palco.reventa.negocio;

import java.util.List;

import jakarta.ejb.Local;

import com.palco.reventa.api.Dtos.CompraDto;
import com.palco.reventa.api.Dtos.CompraHechaDto;
import com.palco.reventa.api.Dtos.NuevaPublicacionDto;
import com.palco.reventa.api.Dtos.PublicacionDto;

/**
 * La interfaz de ServicioDeReventa: lo que el recurso REST le puede pedir.
 * La implementa {@link ServicioDeReventa}.
 */
@Local
public interface IReventa {

    /** Las publicaciones ACTIVA de un evento, de la más barata a la más cara. */
    List<PublicacionDto> listarActivas(String eventoId);

    /** Las publicaciones del usuario, en todos los estados. */
    List<PublicacionDto> listarDelVendedor(String vendedorId);

    /** Publica una entrada propia VALIDA a ese precio. */
    PublicacionDto publicar(String vendedorId, NuevaPublicacionDto nueva);

    /** Retira una publicación propia ACTIVA; la entrada vuelve a VALIDA. */
    void retirar(String vendedorId, String publicacionId);

    /** Compra una publicación: cobra contra Pagos y la entrada pasa al comprador. */
    CompraHechaDto comprar(String compradorId, String publicacionId, CompraDto compra, String claveIdempotencia);
}
