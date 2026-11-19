import fs from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { test as base, expect, type APIRequestContext, type Page } from '@playwright/test';

// Ayudas comunes de las pruebas E2E. Lo que una prueba necesita tener de antemano (un evento propio,
// una cuenta nueva, una compra ya emitida) se arma por la API: es más rápido y así cada prueba no
// depende de las pantallas de las otras. Lo que se prueba se hace siempre en el navegador.
//
// Variables de entorno (todas opcionales):
//   PALCO_E2E_URL   dirección de la app                      (http://localhost:5173, el `npm run dev`)
//   PALCO_E2E_API   dirección de la API para armar los datos  (la de la app si se definió; si no,
//                   http://localhost:8080). Contra el compose es la misma: nginx pasa /api al backend.
//   PALCO_LOG       ruta del server.log del backend, de donde sale el código de verificación de las
//                   cuentas nuevas (el de backend/servidor/target/server, el que arma dev.sh)

const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

const URL_API = (process.env.PALCO_E2E_API ?? process.env.PALCO_E2E_URL ?? 'http://localhost:8080').replace(/\/$/, '');

const LOG = process.env.PALCO_LOG
  ? path.resolve(process.env.PALCO_LOG)
  : path.join(REPO, 'backend', 'servidor', 'target', 'server', 'standalone', 'log', 'server.log');

/** La contraseña de las cuentas de prueba de las migraciones de Usuarios (está en el README) y de las que crean estas pruebas. */
export const PASSWORD = 'palco1234';

/** Cuentas de prueba de las migraciones de Usuarios, una por rol. */
export const CUENTAS = {
  comprador: 'titular@palco.test',
  organizador: 'organizador@palco.test',
  puerta: 'puerta@palco.test',
} as const;

/** Una foto PNG de 1×1 píxel: alcanza para probar la subida sin cargar archivos en el repo. */
export const FOTO_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
  'base64',
);

/** Un sufijo distinto en cada llamada, para que los títulos y los emails no choquen con los de otra corrida. */
export const sufijo = () => randomUUID().slice(0, 8);

/** Un DNI de 8 dígitos al azar: una persona tiene una sola cuenta y una sola entrada por evento. */
export const dniAlAzar = () => String(30_000_000 + Math.floor(Math.random() * 60_000_000));

/** El DNI como lo muestra la app: 30123456 → 30.123.456. */
export const formatearDni = (dni: string) => dni.replace(/(\d+)(\d{3})(\d{3})$/, '$1.$2.$3');

/** Una fecha y hora a tantos días de hoy, en el formato del backend (hora de Argentina, sin zona). */
export function fechaDentroDe(dias: number): string {
  const dia = new Date(Date.now() + dias * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
  return `${dia}T21:00:00`;
}

export interface Sector {
  id: string;
  nombre: string;
  precio: number;
  cupo: number;
}

export interface Evento {
  id: string;
  slug: string;
  titulo: string;
  imagenUrl: string | null;
  cancelado: boolean;
  sectores: Sector[];
}

export interface Cuenta {
  nombre: string;
  apellido: string;
  dni: string;
  email: string;
  token: string;
}

interface Venta {
  id: string;
  estado: string;
  entradas: { id: string; estado: string; codigoQr: string | null }[];
}

interface Opciones {
  cuerpo?: unknown;
  token?: string;
  clave?: string;
}

/** Hasta dónde llega hoy el log: lo que el servidor escriba después es lo nuevo. */
export function marcaDelLog(): number {
  if (!fs.existsSync(LOG)) {
    throw new Error(`No está el log del servidor en ${LOG}. Definí PALCO_LOG con la ruta del server.log del backend.`);
  }
  return fs.statSync(LOG).size;
}

/** Lo que el servidor escribió en el log desde la marca. */
function logDesde(marca: number): string {
  const tamano = fs.statSync(LOG).size;
  // Si el log rotó (cambio de día), el archivo nuevo es más chico que la marca: se lee entero.
  const desde = tamano < marca ? 0 : marca;
  const fd = fs.openSync(LOG, 'r');
  try {
    const buffer = Buffer.alloc(tamano - desde);
    fs.readSync(fd, buffer, 0, buffer.length, desde);
    return buffer.toString('utf8');
  } finally {
    fs.closeSync(fd);
  }
}

/**
 * El código de verificación que le mandó el servidor a ese email. Sin SMTP configurado, el email
 * queda en el log en un renglón "Email para <email> — Tu código de Palco: ... el email es 123456."
 */
export async function codigoDeVerificacion(email: string, marca: number): Promise<string> {
  const patron = new RegExp(`Email para ${email.replace(/[.+]/g, '\\$&')} .*el email es (\\d{6})\\.`);
  const limite = Date.now() + 15_000;
  while (Date.now() < limite) {
    const encontrado = patron.exec(logDesde(marca));
    if (encontrado) return encontrado[1];
    await new Promise((listo) => setTimeout(listo, 300));
  }
  throw new Error(`El código de verificación de ${email} no apareció en ${LOG}.`);
}

/**
 * Los pedidos a la API con los que cada prueba arma sus datos. Recuerda los eventos que crea y, al
 * terminar la prueba, los cancela: así no quedan en el catálogo de quien esté usando el servidor.
 */
export class Api {
  private readonly eventosCreados = new Set<string>();
  private tokenDelOrganizador: string | null = null;

  constructor(private readonly http: APIRequestContext) {}

  private async pedir<T>(metodo: string, ruta: string, { cuerpo, token, clave }: Opciones = {}): Promise<T> {
    const headers: Record<string, string> = {};
    if (token) headers.Authorization = `Bearer ${token}`;
    if (clave) headers['Idempotency-Key'] = clave;
    const respuesta = await this.http.fetch(ruta, { method: metodo, headers, data: cuerpo });
    if (!respuesta.ok()) {
      throw new Error(`${metodo} ${ruta} respondió ${respuesta.status()}: ${await respuesta.text()}`);
    }
    return (respuesta.status() === 204 ? undefined : await respuesta.json()) as T;
  }

  /** El token de una cuenta existente. */
  async token(email: string, password = PASSWORD): Promise<string> {
    const sesion = await this.pedir<{ token: string }>('POST', '/api/usuarios/login', { cuerpo: { email, password } });
    return sesion.token;
  }

  private async organizador(): Promise<string> {
    this.tokenDelOrganizador ??= await this.token(CUENTAS.organizador);
    return this.tokenDelOrganizador;
  }

  /**
   * Un evento propio de la prueba, de la cuenta organizadora de prueba, con dos sectores: Campo a
   * $20.000 y Platea a $35.000. Comprar siempre en uno nuevo evita el tope de entradas por persona y
   * evento. Con `puerta`, queda asignado a la cuenta de puerta de prueba.
   */
  async crearEvento(prefijo: string, { categoria = 'Teatro', puerta = false } = {}): Promise<Evento> {
    const token = await this.organizador();
    const evento = await this.pedir<Evento>('POST', '/api/eventos', {
      token,
      cuerpo: {
        titulo: `${prefijo} ${sufijo()}`,
        categoria,
        venue: 'Sala de pruebas, Rosario',
        fechaISO: fechaDentroDe(60),
        descripcion: 'Evento creado por una prueba E2E.',
        sectores: [
          { nombre: 'Campo', precio: 20_000, cupo: 100 },
          { nombre: 'Platea', precio: 35_000, cupo: 50 },
        ],
      },
    });
    this.eventosCreados.add(evento.id);
    if (puerta) {
      await this.pedir('PUT', `/api/v1/validacion/puertas/${evento.id}/${CUENTAS.puerta}`, { token });
    }
    return evento;
  }

  /** Anota un evento que creó la prueba desde la pantalla, para cancelarlo al terminar si quedó en pie. */
  recordarEvento(eventoId: string): void {
    this.eventosCreados.add(eventoId);
  }

  /** Los eventos del catálogo (sin los cancelados) que coinciden con la búsqueda. */
  buscarEventos(texto: string): Promise<Evento[]> {
    return this.pedir<Evento[]>('GET', `/api/eventos?q=${encodeURIComponent(texto)}`);
  }

  /** La foto de un evento tal como la sirve la API. */
  foto(url: string) {
    return this.http.get(url);
  }

  /**
   * Una cuenta de comprador nueva, con el email ya verificado. Así la prueba no comparte compras en
   * curso ni entradas con nadie. El código de verificación sale del log del servidor.
   */
  async cuentaNueva(): Promise<Cuenta> {
    for (let intento = 1; ; intento++) {
      const datos = { nombre: 'Lucía', apellido: 'Navegador', dni: dniAlAzar(), email: `e2e.${sufijo()}@palco.test` };
      const marca = marcaDelLog();
      const registro = await this.http.post('/api/usuarios/registro', {
        data: { ...datos, fechaNacimiento: '1995-05-17', password: PASSWORD },
      });
      // El DNI salió al azar: si justo ya tiene cuenta, se prueba con otro.
      if (registro.status() === 400 && intento < 3 && (await registro.text()).includes('DNI')) continue;
      if (registro.status() !== 201) {
        throw new Error(`El registro respondió ${registro.status()}: ${await registro.text()}`);
      }
      const codigo = await codigoDeVerificacion(datos.email, marca);
      const sesion = await this.pedir<{ token: string }>('POST', '/api/usuarios/verificacion', {
        cuerpo: { email: datos.email, codigo },
      });
      return { ...datos, token: sesion.token };
    }
  }

  /** Una compra pagada de una entrada a nombre de la cuenta, esperando a que se emita (la emisión es asincrónica). */
  async comprar(cuenta: Cuenta, evento: Evento, sectorId: string): Promise<Venta> {
    const venta = await this.pedir<Venta>('POST', '/api/v1/ventas', {
      token: cuenta.token,
      clave: randomUUID(),
      cuerpo: {
        eventoId: evento.id,
        items: [{ sectorId, cantidad: 1 }],
        titulares: [{ nombre: `${cuenta.nombre} ${cuenta.apellido}`, dni: cuenta.dni }],
      },
    });
    await this.pedir('POST', `/api/v1/ventas/${venta.id}/pagos`, {
      token: cuenta.token,
      clave: randomUUID(),
      cuerpo: { medioDePago: 'tarjeta', token: 'tok_test' },
    });
    const ver = () => this.pedir<Venta>('GET', `/api/v1/ventas/${venta.id}`, { token: cuenta.token });
    await expect
      .poll(async () => (await ver()).estado, { message: `la venta ${venta.id} no se emitió`, timeout: 30_000 })
      .toBe('EMITIDA');
    return ver();
  }

  /** Cancela los eventos que creó la prueba. Uno que la prueba ya canceló responde 409 y se deja así. */
  async limpiar(): Promise<void> {
    if (this.eventosCreados.size === 0) return;
    const token = await this.organizador();
    for (const id of this.eventosCreados) {
      const respuesta = await this.http.post(`/api/eventos/${id}/cancelacion`, {
        headers: { Authorization: `Bearer ${token}` },
        data: { motivo: 'Fin de la prueba E2E.' },
      });
      if (!respuesta.ok() && respuesta.status() !== 409) {
        console.warn(`No se pudo cancelar el evento de prueba ${id}: ${respuesta.status()}`);
      }
    }
  }
}

/**
 * El `test` de Playwright con dos agregados:
 *  - `api`: los pedidos para armar los datos de la prueba, contra la API del backend.
 *  - una comprobación automática al final de cada prueba: que la página no haya tirado ninguna
 *    excepción de JavaScript sin capturar. Un error así suele terminar en una pantalla en blanco.
 */
export const test = base.extend<{ api: Api; sinExcepciones: void }>({
  api: async ({ playwright }, usar) => {
    const http = await playwright.request.newContext({ baseURL: URL_API, ignoreHTTPSErrors: true });
    const api = new Api(http);
    await usar(api);
    await api.limpiar();
    await http.dispose();
  },
  sinExcepciones: [
    async ({ page }, usar) => {
      const excepciones: string[] = [];
      page.on('pageerror', (error) => excepciones.push(error.message));
      await usar();
      expect(excepciones, 'excepciones de JavaScript sin capturar en la página').toEqual([]);
    },
    { auto: true },
  ],
});

export { expect };

/** El menú principal del encabezado. */
export const menu = (page: Page) => page.getByRole('navigation', { name: 'Principal' });

/** Ingresa por la pantalla de login y espera a que el encabezado muestre la sesión abierta. */
export async function ingresar(page: Page, email: string, password = PASSWORD): Promise<void> {
  if (!page.url().includes('/ingresar')) await page.goto('/ingresar');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Contraseña', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Ingresar', exact: true }).click();
  await expect(menu(page).getByRole('button', { name: /salir$/ })).toBeVisible();
}

/** Cierra la sesión desde el encabezado. */
export async function salir(page: Page): Promise<void> {
  await menu(page).getByRole('button', { name: /salir$/ }).click();
  await expect(menu(page).getByRole('link', { name: 'Ingresar' })).toBeVisible();
}
