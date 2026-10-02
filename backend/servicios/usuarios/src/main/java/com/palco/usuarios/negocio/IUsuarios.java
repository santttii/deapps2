package com.palco.usuarios.negocio;

import jakarta.ejb.Local;

import com.palco.usuarios.api.Dtos.LoginDto;
import com.palco.usuarios.api.Dtos.RegistroDto;
import com.palco.usuarios.api.Dtos.RegistroPendienteDto;
import com.palco.usuarios.api.Dtos.SesionDto;
import com.palco.usuarios.api.Dtos.UsuarioDto;
import com.palco.usuarios.api.Dtos.VerificacionDto;

/**
 * Interfaz de ServicioDeUsuarios (Entrega 1): identidad, autenticación y roles.
 * Es el servicio reutilizable del taller de integración: Ventas, Reventa,
 * Validación y el panel del organizador dependen de él.
 */
@Local
public interface IUsuarios {

    /** Crea la cuenta sin verificar y manda el código de 6 dígitos al email. */
    RegistroPendienteDto registrar(RegistroDto registro);

    /** Valida el código; si es correcto activa la cuenta y devuelve la sesión. */
    SesionDto verificarEmail(VerificacionDto verificacion);

    /** Manda un código nuevo (como mucho uno cada 30 segundos). */
    void reenviarCodigo(String email);

    /** Email + contraseña → sesión con token. */
    SesionDto iniciarSesion(LoginDto login);

    /** Datos del usuario dueño del token. */
    UsuarioDto obtener(String usuarioId);
}
