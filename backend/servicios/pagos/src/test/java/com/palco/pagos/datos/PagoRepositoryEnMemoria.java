package com.palco.pagos.datos;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.PersistenceException;

import com.palco.common.ErroresDeNegocio;
import com.palco.pagos.dominio.Pago;

/**
 * Repositorio para los tests: guarda los pagos en una lista. Está escrito a
 * mano, y no con Mockito, porque necesita comportarse como la base: rechaza un
 * pago con una clave de idempotencia, un id externo o un id que ya existen, y
 * falla, como PostgreSQL, con un texto que tiene el carácter nulo (U+0000).
 * De eso depende lo que se quiere probar en {@code ServicioDePagos}.
 */
public class PagoRepositoryEnMemoria implements PagoRepository {

    private final List<Pago> pagos = new ArrayList<>();

    @Override
    public Optional<Pago> buscarPorId(String pagoId) {
        comoPostgreSql(pagoId);
        return pagos.stream().filter(p -> p.getId().equals(pagoId)).findFirst();
    }

    @Override
    public Optional<Pago> buscarPorClave(String claveIdempotencia) {
        comoPostgreSql(claveIdempotencia);
        return pagos.stream().filter(p -> p.getClaveIdempotencia().equals(claveIdempotencia)).findFirst();
    }

    @Override
    public void guardar(Pago nuevo) {
        comoPostgreSql(nuevo.getId(), nuevo.getVentaId(), nuevo.getMedioDePago(), nuevo.getClaveIdempotencia(),
                nuevo.getMotivo(), nuevo.getIdExterno());
        boolean repetido = pagos.stream().anyMatch(p -> p.getId().equals(nuevo.getId())
                || p.getClaveIdempotencia().equals(nuevo.getClaveIdempotencia())
                || p.getIdExterno().equals(nuevo.getIdExterno()));
        if (repetido) {
            throw new ErroresDeNegocio.Conflicto("pago-duplicado", "Ya existe un pago con esos datos.");
        }
        pagos.add(nuevo);
    }

    @Override
    public boolean marcarReembolsado(String pagoId, long monto, String motivo, LocalDateTime ahora) {
        comoPostgreSql(motivo);
        return buscarPorId(pagoId).map(p -> p.reembolsar(monto, motivo, ahora)).orElse(false);
    }

    public int cantidad() {
        return pagos.size();
    }

    /** PostgreSQL no acepta el carácter nulo en un texto, ni para buscarlo ni para guardarlo: el pedido es un 500. */
    private static void comoPostgreSql(String... textos) {
        for (String texto : textos) {
            if (texto != null && texto.indexOf('\u0000') >= 0) {
                throw new PersistenceException("ERROR: invalid byte sequence for encoding \"UTF8\": 0x00");
            }
        }
    }
}
