package com.palco.pagos.datos;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.palco.common.ErroresDeNegocio;
import com.palco.pagos.dominio.Pago;

/**
 * Repositorio para los tests: guarda los pagos en una lista. Está escrito a
 * mano, y no con Mockito, porque necesita comportarse como la base: rechaza un
 * pago con una clave de idempotencia, un id externo o un id que ya existen.
 * De eso depende lo que se quiere probar en {@code ServicioDePagos}.
 */
public class PagoRepositoryEnMemoria implements PagoRepository {

    private final List<Pago> pagos = new ArrayList<>();

    @Override
    public Optional<Pago> buscarPorId(String pagoId) {
        return pagos.stream().filter(p -> p.getId().equals(pagoId)).findFirst();
    }

    @Override
    public Optional<Pago> buscarPorClave(String claveIdempotencia) {
        return pagos.stream().filter(p -> p.getClaveIdempotencia().equals(claveIdempotencia)).findFirst();
    }

    @Override
    public void guardar(Pago nuevo) {
        boolean repetido = pagos.stream().anyMatch(p -> p.getId().equals(nuevo.getId())
                || p.getClaveIdempotencia().equals(nuevo.getClaveIdempotencia())
                || p.getIdExterno().equals(nuevo.getIdExterno()));
        if (repetido) {
            throw new ErroresDeNegocio.Conflicto("pago-duplicado", "Ya existe un pago con esos datos.");
        }
        pagos.add(nuevo);
    }

    @Override
    public boolean marcarReembolsado(String pagoId, String motivo, LocalDateTime ahora) {
        return buscarPorId(pagoId).map(p -> p.reembolsar(motivo, ahora)).orElse(false);
    }

    public int cantidad() {
        return pagos.size();
    }
}
