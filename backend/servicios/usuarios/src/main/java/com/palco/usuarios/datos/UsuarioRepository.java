package com.palco.usuarios.datos;

import java.util.Optional;

import jakarta.ejb.Local;

import com.palco.usuarios.dominio.Usuario;

/**
 * Dónde se guardan y se buscan las cuentas. El negocio conoce solo esta
 * interfaz: en el servidor la implementa {@link UsuarioRepositoryJpa} contra
 * la base, y en los tests un doble.
 *
 * Las cuentas que devuelve siguen administradas mientras dura la transacción
 * de quien llama: un cambio en la entidad (marcar el email verificado) se
 * guarda solo al confirmarla, sin un método aparte.
 */
@Local
public interface UsuarioRepository {

    Optional<Usuario> buscarPorId(String usuarioId);

    /** La cuenta con ese email, que tiene que llegar ya normalizado (sin espacios y en minúsculas). */
    Optional<Usuario> buscarPorEmail(String email);

    /** La cuenta con ese DNI, que tiene que llegar solo con dígitos. */
    Optional<Usuario> buscarPorDni(String dni);

    /** Guarda una cuenta nueva. El INSERT sale al cerrar la transacción. */
    void guardar(Usuario usuario);

    /**
     * Borra la cuenta (y, por la clave foránea, su código de verificación) y
     * manda el DELETE a la base en el momento: quien llama puede guardar
     * enseguida otra cuenta con el mismo email o DNI sin chocar con la
     * restricción única.
     */
    void borrar(Usuario usuario);
}
