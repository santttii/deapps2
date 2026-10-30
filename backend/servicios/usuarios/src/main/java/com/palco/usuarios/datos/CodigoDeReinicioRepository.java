package com.palco.usuarios.datos;

import java.util.Optional;

import jakarta.ejb.Local;

import com.palco.usuarios.dominio.CodigoDeReinicio;

/**
 * Dónde se guarda el código vigente de cada cuenta que pidió volver a poner su
 * contraseña (uno por cuenta: la clave es el id del usuario). El negocio conoce
 * solo esta interfaz: en el servidor la implementa
 * {@link CodigoDeReinicioRepositoryJpa} contra la base, y en los tests un doble.
 *
 * Como en {@link CodigoVerificacionRepository}, el código que devuelve sigue
 * administrado durante la transacción de quien llama.
 */
@Local
public interface CodigoDeReinicioRepository {

    /** El código vigente de esa cuenta, si tiene uno. */
    Optional<CodigoDeReinicio> buscarPorUsuario(String usuarioId);

    /** Guarda el código de una cuenta que no tenía. El INSERT sale al cerrar la transacción. */
    void guardar(CodigoDeReinicio codigo);

    /**
     * Manda a la base en el momento los cambios del código (el intento fallido
     * sumado), para que quede escrito aunque la respuesta sea un 400.
     */
    void guardarCambios(CodigoDeReinicio codigo);

    /** Borra el código cuando ya se usó. */
    void borrar(CodigoDeReinicio codigo);
}
