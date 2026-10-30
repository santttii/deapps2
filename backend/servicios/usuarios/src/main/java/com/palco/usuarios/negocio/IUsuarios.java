package com.palco.usuarios.negocio;

import jakarta.ejb.Local;

import com.palco.usuarios.api.Dtos.LoginDto;
import com.palco.usuarios.api.Dtos.ReinicioDto;
import com.palco.usuarios.api.Dtos.RegistroDto;
import com.palco.usuarios.api.Dtos.RegistroPendienteDto;
import com.palco.usuarios.api.Dtos.SesionDto;
import com.palco.usuarios.api.Dtos.TokenDeServicioDto;
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

    /**
     * Valida el código; si es correcto completa el registro (email e identidad
     * verificados) y devuelve la sesión.
     */
    SesionDto verificarEmail(VerificacionDto verificacion);

    /** Manda un código nuevo (como mucho uno cada 30 segundos). */
    void reenviarCodigo(String email);

    /** Email + contraseña → sesión con token. */
    SesionDto iniciarSesion(LoginDto login);

    /**
     * Manda por email un código de 6 dígitos para volver a poner la contraseña.
     * No dice nada de la cuenta: si el email no existe, no está verificado o ya
     * pidió uno hace unos segundos, no hace nada y no es un error. Quien llama
     * contesta siempre lo mismo.
     */
    void pedirReinicioDePassword(String email);

    /**
     * Cambia la contraseña con el código que llegó por email. Un código
     * incorrecto suma un intento y es 400; vencido o con demasiados intentos,
     * también. Al cambiarla se olvidan los intentos de login fallidos, así
     * quien quedó bloqueado puede entrar con la nueva.
     */
    void reiniciarPassword(ReinicioDto reinicio);

    /** Datos del usuario dueño del token. */
    UsuarioDto obtener(String usuarioId);

    /**
     * Credenciales de cliente: si el secreto es el que comparten los servicios,
     * devuelve un token corto de rol {@code servicio} para que se llamen entre sí.
     */
    TokenDeServicioDto emitirTokenDeServicio(String secreto);
}
