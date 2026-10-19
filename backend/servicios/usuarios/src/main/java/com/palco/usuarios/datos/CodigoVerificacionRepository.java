package com.palco.usuarios.datos;

import java.util.Optional;

import jakarta.ejb.Local;

import com.palco.usuarios.dominio.CodigoVerificacion;

/**
 * Dónde se guarda el código vigente de cada cuenta que todavía no verificó su
 * email (uno por cuenta: la clave es el id del usuario). El negocio conoce solo
 * esta interfaz: en el servidor la implementa {@link CodigoVerificacionRepositoryJpa}
 * contra la base, y en los tests un doble.
 *
 * Como en {@link UsuarioRepository}, el código que devuelve sigue administrado
 * durante la transacción de quien llama: renovarlo se guarda al confirmarla.
 */
@Local
public interface CodigoVerificacionRepository {

    /** El código vigente de esa cuenta, si tiene uno. */
    Optional<CodigoVerificacion> buscarPorUsuario(String usuarioId);

    /** Guarda el código de una cuenta que no tenía. El INSERT sale al cerrar la transacción. */
    void guardar(CodigoVerificacion codigo);

    /**
     * Manda a la base en el momento los cambios del código (el intento fallido
     * sumado). Se usa antes de lanzar un error que no deshace la transacción,
     * para que el intento quede escrito aunque la respuesta sea un 400.
     */
    void guardarCambios(CodigoVerificacion codigo);

    /** Borra el código cuando ya se usó. */
    void borrar(CodigoVerificacion codigo);
}
