import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useLocation } from 'react-router-dom';
import type { Usuario } from '../types';
import { ErrorApi, alPerderSesion, comprobarSesion } from '../lib/api';
import { CLAVE_SESION, borrarSesion, guardarSesion, leerSesion, type Sesion } from '../lib/sesion';
import { iniciarSesion, obtenerPerfil, verificarEmail as verificarEmailEnServidor } from '../servicios/usuarios';
import { useToast } from '../components/ToastProvider';

// Quién está logueado. Las páginas leen el usuario con useApp().usuarioActual; este contexto
// se usa directo solo para las acciones: ingresar, verificarEmail y salir.
interface SesionContextValue {
  usuario: Usuario | null;
  ingresar: (email: string, password: string) => Promise<void>;
  verificarEmail: (email: string, codigo: string) => Promise<void>;
  salir: () => void;
}

const SesionContext = createContext<SesionContextValue | null>(null);

const AVISO_SESION_VENCIDA = 'Tu sesión venció. Volvé a ingresar.';

export function SesionProvider({ children }: { children: ReactNode }) {
  const { mostrarToast } = useToast();
  const { pathname } = useLocation();
  // La sesión guardada se lee de forma síncrona: el primer render ya sabe quién es el usuario
  // y las rutas protegidas no necesitan un estado "cargando sesión".
  const [sesion, setSesion] = useState<Sesion | null>(leerSesion);

  // Primero se guarda y después se actualiza la pantalla: un pedido que salga enseguida
  // tiene que encontrar el token ya guardado.
  const abrir = useCallback((nueva: Sesion) => {
    guardarSesion(nueva);
    setSesion(nueva);
  }, []);

  const ingresar = useCallback(
    async (email: string, password: string) => {
      abrir(await iniciarSesion(email, password));
    },
    [abrir],
  );

  const verificarEmail = useCallback(
    async (email: string, codigo: string) => {
      abrir(await verificarEmailEnServidor(email, codigo));
    },
    [abrir],
  );

  // El backend no tiene endpoint de logout: salir es descartar el token.
  const salir = useCallback(() => {
    borrarSesion();
    setSesion(null);
  }, []);

  // 1. api.ts dio la sesión por perdida (401, token rechazado o sesión vencida).
  useEffect(() => {
    alPerderSesion((habiaSesionGuardada) => {
      setSesion(null);
      // Varios pedidos que fallan juntos avisan una sola vez: solo el primero encuentra algo guardado.
      if (habiaSesionGuardada) mostrarToast(AVISO_SESION_VENCIDA);
    });
    return () => alPerderSesion(null);
  }, [mostrarToast]);

  // 2. Si se ingresa o se sale en otra pestaña, esta se pone al día.
  useEffect(() => {
    function alCambiarEnOtraPestana(e: StorageEvent) {
      if (e.key === CLAVE_SESION || e.key === null) setSesion(leerSesion());
    }
    window.addEventListener('storage', alCambiarEnOtraPestana);
    return () => window.removeEventListener('storage', alCambiarEnOtraPestana);
  }, []);

  // 3. Al abrir la aplicación: limpia un registro vencido o confirma la sesión con el servidor.
  useEffect(() => {
    const guardada = leerSesion();
    if (!guardada) {
      borrarSesion();
      return;
    }
    obtenerPerfil()
      .then((usuario) => {
        const actual = leerSesion();
        if (actual?.token !== guardada.token) return; // hubo un ingreso más nuevo: no se pisa
        abrir({ ...actual, usuario });
      })
      .catch((error: unknown) => {
        // El 401 y el token rechazado ya los resolvió api.ts. Sin conexión se sigue con lo guardado.
        const usuarioBorrado = error instanceof ErrorApi && error.estado === 404;
        if (usuarioBorrado && leerSesion()?.token === guardada.token) {
          salir();
          mostrarToast(AVISO_SESION_VENCIDA);
        }
      });
  }, [abrir, salir, mostrarToast]);

  // 4. Al cambiar de página: si la sesión guardada se borró o venció con la página abierta, se
  // cierra también en pantalla. Las pantallas que todavía no llaman al backend no lo notarían.
  useEffect(() => {
    comprobarSesion();
  }, [pathname]);

  const value = useMemo(
    () => ({ usuario: sesion?.usuario ?? null, ingresar, verificarEmail, salir }),
    [sesion, ingresar, verificarEmail, salir],
  );

  return <SesionContext.Provider value={value}>{children}</SesionContext.Provider>;
}

export function useSesion(): SesionContextValue {
  const ctx = useContext(SesionContext);
  if (!ctx) throw new Error('useSesion debe usarse dentro de SesionProvider');
  return ctx;
}
