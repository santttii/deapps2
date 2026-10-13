import { borrarSesion, leerSesion } from './sesion';

// Cliente HTTP del backend. Es el único lugar que llama a fetch: arma la URL, manda y lee JSON,
// agrega el token cuando hace falta y traduce los errores a mensajes para el usuario.

// Va || y no ??: un VITE_API_URL vacío también tiene que caer en el valor por defecto.
const URL_BASE = import.meta.env.VITE_API_URL || 'http://localhost:8080';

const MENSAJE_SIN_CONEXION = 'No pudimos conectar con el servidor. Probá de nuevo en un momento.';
const MENSAJE_INESPERADO = 'Ocurrió un error inesperado. Probá de nuevo en un momento.';
const MENSAJE_DATOS_INVALIDOS = 'No pudimos procesar los datos. Revisá el formulario.';
const MENSAJE_SIN_SESION = 'Tenés que iniciar sesión.';

// Estados en los que el mensaje del backend ya está escrito para el usuario. 402, 422 y 504 los
// usa Ventas: pago rechazado, regla de negocio y servicio externo que no respondió.
const ESTADOS_CON_MENSAJE_PROPIO = [401, 402, 403, 404, 409, 422, 504];

export class ErrorApi extends Error {
  estado: number; // código HTTP; 0 = no hubo una respuesta válida de la API
  campos: Record<string, string>; // errores por campo del formulario; {} si no aplica
  // Último tramo del "type" del backend ('cupo-agotado', 'pago-rechazado'); '' si no vino.
  // Las páginas deciden qué hacer por este tipo, no por el texto del mensaje.
  tipo: string;

  constructor(estado: number, mensaje: string, campos: Record<string, string> = {}, tipo = '') {
    super(mensaje);
    this.name = 'ErrorApi';
    this.estado = estado;
    this.campos = campos;
    this.tipo = tipo;
  }
}

export interface OpcionesPedido {
  metodo?: 'GET' | 'POST' | 'DELETE';
  cuerpo?: unknown; // se manda como JSON
  consulta?: Record<string, string | undefined>; // parámetros de la URL; los vacíos se omiten
  conSesion?: boolean; // manda Authorization: Bearer con el token guardado
  // Se manda en la cabecera Idempotency-Key: repetir un POST con la misma clave no lo hace dos veces.
  claveIdempotencia?: string;
}

// Formato de error del backend: Problem Details, {type, title, status, detail, errores}.
// Mientras no se redesplieguen todos los servicios también llega el formato anterior,
// {estado, mensaje, campos}, así que se leen los dos.
interface CuerpoDeError {
  type?: string; // por ejemplo '/problemas/cupo-agotado'
  detail?: string;
  errores?: Record<string, string>;
  mensaje?: string;
  campos?: Record<string, string>;
}

let avisoSesionPerdida: ((habiaSesionGuardada: boolean) => void) | null = null;

// SesionProvider se anota acá para enterarse cuando un pedido descubre que la sesión ya no sirve.
export function alPerderSesion(aviso: ((habiaSesionGuardada: boolean) => void) | null): void {
  avisoSesionPerdida = aviso;
}

function perderSesion(tokenRechazado: string | null): void {
  // Si mientras tanto se guardó otra sesión (un ingreso nuevo, acá o en otra pestaña), no se toca.
  const guardada = leerSesion();
  if (guardada && guardada.token !== tokenRechazado) return;
  const habiaSesionGuardada = borrarSesion();
  avisoSesionPerdida?.(habiaSesionGuardada);
}

// Comprueba que la sesión guardada siga vigente; si se borró o venció, la da por perdida, igual
// que pedir() cuando no encuentra el token. Es para lo que necesita sesión y no llama al backend
// (el cambio de página, la reventa local): sin esto seguiría adelante con la sesión que quedó en
// memoria. No detecta un token que el servidor ya no acepta: eso solo lo sabe el servidor.
export function comprobarSesion(): boolean {
  if (leerSesion()) return true;
  perderSesion(null);
  return false;
}

function armarUrl(ruta: string, consulta: OpcionesPedido['consulta'] = {}): string {
  const parametros = new URLSearchParams();
  for (const [nombre, valor] of Object.entries(consulta)) {
    if (valor) parametros.set(nombre, valor);
  }
  const texto = parametros.toString();
  return URL_BASE + ruta + (texto ? `?${texto}` : '');
}

// El backend rechaza un token que no acepta (vencido, o firmado antes de un reinicio del
// servidor) con una respuesta sin cabeceras CORS: el navegador la informa como error de red y
// no deja ver el 401. Un corte de red de verdad se ve igual. Para distinguirlos se prueba ese
// mismo token en GET /api/usuarios/yo (todos los servicios validan el token con la misma clave):
//   - si contesta bien, el token sirve y lo que falló fue la red: la sesión no se toca;
//   - si tampoco se puede leer la respuesta, se pregunta sin token al health de Usuarios: si
//     ese contesta, el servidor está vivo y lo que rechaza es el token.
// Antes solo se miraba el health, y un corte de red en medio de una compra cerraba la sesión.
async function tokenRechazado(token: string): Promise<boolean> {
  try {
    const perfil = await fetch(`${URL_BASE}/api/usuarios/yo`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    return perfil.status === 401;
  } catch {
    // Sin respuesta legible: o el token está rechazado o el servidor no responde.
  }
  try {
    const health = await fetch(`${URL_BASE}/api/usuarios/health`);
    return health.ok;
  } catch {
    return false;
  }
}

function errorDeRespuesta(estado: number, cuerpo: CuerpoDeError | null): ErrorApi {
  const mensaje = cuerpo?.detail ?? cuerpo?.mensaje;
  const campos = cuerpo?.errores ?? cuerpo?.campos ?? {};
  const tieneCampos = Object.keys(campos).length > 0;
  const tipo = cuerpo?.type?.split('/').pop() ?? '';
  if (mensaje && (ESTADOS_CON_MENSAJE_PROPIO.includes(estado) || (estado === 400 && tieneCampos))) {
    return new ErrorApi(estado, mensaje, campos, tipo);
  }
  // Un 400 sin campos es un cuerpo que el servidor no pudo leer: su mensaje es técnico.
  if (estado === 400) return new ErrorApi(estado, MENSAJE_DATOS_INVALIDOS, {}, tipo);
  if (estado === 401) return new ErrorApi(estado, MENSAJE_SIN_SESION, {}, tipo);
  return new ErrorApi(estado, MENSAJE_INESPERADO, {}, tipo);
}

export async function pedir<T>(ruta: string, opciones: OpcionesPedido = {}): Promise<T> {
  const { metodo = 'GET', cuerpo, consulta, conSesion = false, claveIdempotencia } = opciones;

  const cabeceras: Record<string, string> = {};
  if (cuerpo !== undefined) cabeceras['Content-Type'] = 'application/json';
  if (claveIdempotencia) cabeceras['Idempotency-Key'] = claveIdempotencia;

  // El token va solo en los pedidos que lo necesitan: con un token viejo el servidor rechaza
  // también los endpoints públicos, y se caería hasta el catálogo.
  let token: string | null = null;
  if (conSesion) {
    token = leerSesion()?.token ?? null;
    if (!token) {
      perderSesion(null);
      throw new ErrorApi(401, MENSAJE_SIN_SESION);
    }
    cabeceras.Authorization = `Bearer ${token}`;
  }

  let respuesta: Response;
  try {
    respuesta = await fetch(armarUrl(ruta, consulta), {
      method: metodo,
      headers: cabeceras,
      body: cuerpo === undefined ? undefined : JSON.stringify(cuerpo),
    });
  } catch {
    if (token && (await tokenRechazado(token))) {
      perderSesion(token);
      throw new ErrorApi(401, MENSAJE_SIN_SESION);
    }
    // Corte de red o servidor caído. El pedido pudo haber llegado igual: por eso iniciar la
    // compra y pagar se reintentan con la misma clave de idempotencia.
    throw new ErrorApi(0, MENSAJE_SIN_CONEXION);
  }

  if (respuesta.status === 204) return undefined as T;

  // No se puede asumir JSON: un error del servidor de aplicaciones llega como HTML.
  let datos: unknown = null;
  // Los errores en Problem Details llegan como application/problem+json.
  let esJson = /application\/(problem\+)?json/.test(respuesta.headers.get('Content-Type') ?? '');
  if (esJson) {
    try {
      datos = await respuesta.json();
    } catch {
      esJson = false;
    }
  }

  if (respuesta.ok) {
    // Un 200 que no es JSON no vino de la API (por ejemplo, VITE_API_URL apunta a otro servidor).
    if (!esJson) throw new ErrorApi(0, MENSAJE_SIN_CONEXION);
    return datos as T;
  }

  // El login no va con conSesion, así que su 401 (contraseña incorrecta) no cierra ninguna sesión.
  if (respuesta.status === 401 && token) perderSesion(token);
  throw errorDeRespuesta(respuesta.status, datos as CuerpoDeError | null);
}

export function mensajeDeError(error: unknown): string {
  return error instanceof ErrorApi ? error.message : MENSAJE_INESPERADO;
}
