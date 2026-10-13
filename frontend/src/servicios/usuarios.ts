import { pedir } from '../lib/api';
import type { Sesion } from '../lib/sesion';
import type { Usuario } from '../types';

// Una función por endpoint de ServicioDeUsuarios (/api/usuarios).

export interface DatosRegistro {
  nombre: string;
  apellido: string;
  dni: string;
  fechaNacimiento: string; // AAAA-MM-DD, como lo entrega el input de fecha
  email: string;
  telefono: string;
  password: string;
}

export interface RegistroPendiente {
  email: string;
  mensaje: string;
}

// Lo que responden login y verificacion.
interface RespuestaDeSesion {
  token: string;
  expiraEnSegundos: number;
  usuario: Usuario;
}

// El backend informa cuánto dura el token; acá se convierte en el momento en que vence.
function aSesion(respuesta: RespuestaDeSesion): Sesion {
  return {
    token: respuesta.token,
    expiraEn: Date.now() + respuesta.expiraEnSegundos * 1000,
    usuario: respuesta.usuario,
  };
}

export async function iniciarSesion(email: string, password: string): Promise<Sesion> {
  const respuesta = await pedir<RespuestaDeSesion>('/api/usuarios/login', {
    metodo: 'POST',
    cuerpo: { email, password },
  });
  return aSesion(respuesta);
}

// Crea la cuenta sin verificar: el backend manda un código de 6 dígitos al email.
export function registrar(datos: DatosRegistro): Promise<RegistroPendiente> {
  // El backend rechaza un email con espacios alrededor.
  return pedir<RegistroPendiente>('/api/usuarios/registro', {
    metodo: 'POST',
    cuerpo: { ...datos, email: datos.email.trim() },
  });
}

// Con el código correcto la cuenta queda activa y el backend ya devuelve la sesión.
export async function verificarEmail(email: string, codigo: string): Promise<Sesion> {
  const respuesta = await pedir<RespuestaDeSesion>('/api/usuarios/verificacion', {
    metodo: 'POST',
    cuerpo: { email, codigo },
  });
  return aSesion(respuesta);
}

export function reenviarCodigo(email: string): Promise<void> {
  return pedir<void>('/api/usuarios/verificacion/reenvio', { metodo: 'POST', cuerpo: { email } });
}

export function obtenerPerfil(): Promise<Usuario> {
  return pedir<Usuario>('/api/usuarios/yo', { conSesion: true });
}
