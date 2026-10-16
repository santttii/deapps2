// Ayudas comunes de la prueba de integración del backend de Palco (scripts/pruebas/escenarios.mjs).
// Todo se hace por la API (fetch) y por jboss-cli; los ids salen de las respuestas.
// Solo usa módulos que trae Node (18 o más nuevo, por el fetch nativo): no hace falta npm install.
import { spawnSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const BASE = (process.env.PALCO_URL ?? 'http://localhost:8080').replace(/\/$/, '');
const WINDOWS = process.platform === 'win32';

/** La raíz del repo: dos carpetas arriba de este archivo (scripts/pruebas/). */
export const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const SERVIDOR = path.join(REPO, 'backend', 'servidor', 'target', 'server');
const CLI = process.env.JBOSS_CLI
  ?? path.join(SERVIDOR, 'bin', WINDOWS ? 'jboss-cli.bat' : 'jboss-cli.sh');
export const LOG = process.env.PALCO_LOG ?? path.join(SERVIDOR, 'standalone', 'log', 'server.log');

/**
 * JAVA_HOME tal como venga del entorno. En Git Bash puede estar escrita al modo de Git Bash (/c/...):
 * jboss-cli.bat solo entiende la de Windows (C:\...), así que se la convierte. Sin JAVA_HOME,
 * jboss-cli usa el Java del PATH.
 */
function javaHome() {
  const valor = process.env.JAVA_HOME;
  if (!valor || !WINDOWS) return valor;
  const gitBash = valor.match(/^\/([a-zA-Z])(\/.*)?$/);
  return gitBash ? `${gitBash[1].toUpperCase()}:${(gitBash[2] ?? '/').replace(/\//g, '\\')}` : valor;
}
const JAVA_HOME = javaHome();

export const BROKER = '/subsystem=messaging-activemq/server=default';
export const COLA = `${BROKER}/jms-queue=cola.emision-entradas`;
export const TOPICO = `${BROKER}/jms-topic=topico.compra-confirmada`;
export const DLQ = `${BROKER}/jms-queue=DLQ`;
/** La cola interna donde el broker guarda las copias del suscriptor durable de auditoría. */
export const COLA_DE_AUDITORIA = 'palco-auditoria.compras-confirmadas';
export const SUSCRIPCION_DE_AUDITORIA = `${BROKER}/runtime-queue=${COLA_DE_AUDITORIA}`;
export const MDB_EMISION = '/deployment=palco-ventas.war/subsystem=ejb3/message-driven-bean=EmisionDeEntradas';
export const MDB_AUDITORIA = '/deployment=palco-notificaciones.war/subsystem=ejb3/message-driven-bean=AuditoriaDeCompras';

export const uuid = () => randomUUID();
export const esperar = (ms) => new Promise((listo) => setTimeout(listo, ms));
export const hora = () => new Date().toLocaleTimeString('es-AR', { hour12: false });

// ------------------------------------------------------------------------------------- HTTP

/** Un pedido a la API. Devuelve el código, las cabeceras que importan, el cuerpo como texto y, si es JSON, convertido. */
export async function http(metodo, ruta, { token, clave, cuerpo, crudo, cabeceras = {} } = {}) {
  const h = { ...cabeceras };
  if (token) h.Authorization = `Bearer ${token}`;
  if (clave) h['Idempotency-Key'] = clave;
  let body;
  if (crudo !== undefined) {
    body = crudo;
    h['Content-Type'] ??= 'application/json';
  } else if (cuerpo !== undefined) {
    body = JSON.stringify(cuerpo);
    h['Content-Type'] = 'application/json';
  }
  const r = await fetch(BASE + ruta, { method: metodo, headers: h, body });
  const texto = await r.text();
  let json = null;
  try { json = texto ? JSON.parse(texto) : null; } catch { /* no es JSON */ }
  return {
    estado: r.status,
    tipo: r.headers.get('content-type') ?? '',
    location: r.headers.get('location'),
    texto,
    json,
  };
}

export async function login(email, password = 'palco1234') {
  const r = await http('POST', '/api/usuarios/login', { cuerpo: { email, password } });
  if (r.estado !== 200) throw new Error(`login de ${email}: ${r.estado} ${r.texto}`);
  return r.json.token;
}

// El secreto compartido de los servicios: el de la variable de entorno, el de backend/.env o el de desarrollo,
// en ese orden, igual que lo resuelve el servidor al arrancar con dev.sh.
function secretoDeServicios() {
  if (process.env.PALCO_SECRETO_SERVICIOS) return process.env.PALCO_SECRETO_SERVICIOS;
  const env = path.join(REPO, 'backend', '.env');
  const linea = fs.existsSync(env) ? fs.readFileSync(env, 'utf8').match(/^PALCO_SECRETO_SERVICIOS=(.+)$/m) : null;
  return linea ? linea[1].trim() : 'palco-desarrollo';
}

export async function tokenDeServicio() {
  const r = await http('POST', '/api/usuarios/servicios/token', { cuerpo: { secreto: secretoDeServicios() } });
  if (r.estado !== 200) throw new Error(`token de servicio: ${r.estado} ${r.texto}`);
  return r.json.token;
}

/** El sector, tal como lo publica Eventos (cupo y vendidas incluidos). */
export async function sector(sectorId) {
  const r = await http('GET', '/api/eventos');
  for (const evento of r.json) {
    const s = evento.sectores.find((x) => x.id === sectorId);
    if (s) return { ...s, eventoId: evento.id };
  }
  throw new Error(`No existe el sector ${sectorId}`);
}
export const vendidas = async (sectorId) => (await sector(sectorId)).vendidas;

// ------------------------------------------------------------------------------------- jboss-cli

/**
 * Corre comandos de jboss-cli contra el servidor en marcha y devuelve la salida. Van en un archivo
 * (--file) para no depender de cómo cada consola trata las barras, los paréntesis y las comillas.
 */
export function cli(comandos) {
  const lista = Array.isArray(comandos) ? comandos : [comandos];
  if (!fs.existsSync(CLI)) throw new Error(`No está jboss-cli en ${CLI}: armá el servidor o definí JBOSS_CLI.`);
  const env = { ...process.env, NOPAUSE: 'true' };
  if (JAVA_HOME) env.JAVA_HOME = JAVA_HOME;
  const archivo = path.join(os.tmpdir(), `palco-cli-${process.pid}-${Date.now()}.cli`);
  fs.writeFileSync(archivo, lista.join('\n') + '\n');
  try {
    // En Windows el .bat se corre con cmd.exe, con las comillas tal cual (la ruta del repo puede tener espacios).
    const r = WINDOWS
      ? spawnSync('cmd.exe', ['/d', '/s', '/c', `""${CLI}" --connect "--file=${archivo}""`],
        { encoding: 'utf8', windowsVerbatimArguments: true, env })
      : spawnSync(CLI, ['--connect', `--file=${archivo}`], { encoding: 'utf8', env });
    const salida = (r.stdout ?? '') + (r.stderr ?? '');
    if (r.status !== 0) throw new Error(`jboss-cli falló (${r.status}) con: ${lista.join(' ; ')}\n${salida}`);
    return salida;
  } finally {
    fs.rmSync(archivo, { force: true });
  }
}

/** Los resultados numéricos de varios read-attribute o count-messages, en el orden en que se pidieron. */
export function numeros(comandos) {
  const salida = cli(comandos);
  const hallados = [...salida.matchAll(/"result" => (\d+)L?/g)].map((m) => Number(m[1]));
  if (hallados.length !== comandos.length) {
    throw new Error(`Se esperaban ${comandos.length} números y llegaron ${hallados.length}:\n${salida}`);
  }
  return hallados;
}

/** Los contadores del broker que se comparan antes y después de cada prueba. */
export function contadores() {
  const [colaAgregados, colaPendientes, colaConsumidores, topicoAgregados, suscripciones, durables,
    auditoriaAgregados, auditoriaPendientes, dlq] = numeros([
    `${COLA}:read-attribute(name=messages-added)`,
    `${COLA}:read-attribute(name=message-count)`,
    `${COLA}:read-attribute(name=consumer-count)`,
    `${TOPICO}:read-attribute(name=messages-added)`,
    `${TOPICO}:read-attribute(name=subscription-count)`,
    `${TOPICO}:read-attribute(name=durable-subscription-count)`,
    `${SUSCRIPCION_DE_AUDITORIA}:read-attribute(name=messages-added)`,
    `${SUSCRIPCION_DE_AUDITORIA}:read-attribute(name=message-count)`,
    `${DLQ}:read-attribute(name=message-count)`,
  ]);
  return { colaAgregados, colaPendientes, colaConsumidores, topicoAgregados, suscripciones, durables,
    auditoriaAgregados, auditoriaPendientes, dlq };
}

export const verContadores = (c) => `cola: agregados=${c.colaAgregados} pendientes=${c.colaPendientes}`
  + ` consumidores=${c.colaConsumidores} | tópico: agregados=${c.topicoAgregados} suscripciones=${c.suscripciones}`
  + ` (durables=${c.durables}) | suscripción durable de auditoría: agregados=${c.auditoriaAgregados}`
  + ` pendientes=${c.auditoriaPendientes} | DLQ: pendientes=${c.dlq}`;

export const diferencia = (antes, despues) => `cola +${despues.colaAgregados - antes.colaAgregados}`
  + ` | tópico +${despues.topicoAgregados - antes.topicoAgregados}`
  + ` | suscripción durable +${despues.auditoriaAgregados - antes.auditoriaAgregados}`
  + ` | DLQ +${despues.dlq - antes.dlq}`;

// ------------------------------------------------------------------------------------- server.log

/** Dónde termina hoy el log: lo que se escriba después es "lo nuevo". */
export const marcaDelLog = () => fs.statSync(LOG).size;

/** Las líneas que el servidor escribió desde la marca. */
export function logDesde(marca) {
  const fd = fs.openSync(LOG, 'r');
  try {
    const largo = fs.fstatSync(fd).size - marca;
    if (largo <= 0) return [];
    const buffer = Buffer.alloc(largo);
    fs.readSync(fd, buffer, 0, largo, marca);
    return buffer.toString('utf8').split(/\r?\n/).filter((l) => l.length > 0);
  } finally {
    fs.closeSync(fd);
  }
}

/** Espera hasta que la condición se cumpla (la vuelve a probar cada 300 ms) o se acabe el tiempo. */
export async function hasta(condicion, { maximoMs = 15000, cadaMs = 300 } = {}) {
  const fin = Date.now() + maximoMs;
  for (;;) {
    const valor = await condicion();
    if (valor) return valor;
    if (Date.now() > fin) return null;
    await esperar(cadaMs);
  }
}

// ------------------------------------------------------------------------------------- resultados

export class Informe {
  constructor() { this.escenarios = []; this.actual = null; }

  escenario(numero, nombre) {
    this.actual = { numero, nombre, comprobaciones: [], datos: [] };
    this.escenarios.push(this.actual);
    console.log(`\n${'='.repeat(100)}\nESCENARIO ${numero}. ${nombre}   (${hora()})\n${'='.repeat(100)}`);
  }

  /** Un dato que se muestra pero no se comprueba. */
  dato(texto) {
    this.actual.datos.push(texto);
    console.log(`   dato  ${texto}`);
  }

  /** Una comprobación: qué se esperaba, si se cumplió y lo que se vio. */
  ok(descripcion, condicion, visto = '') {
    const bien = Boolean(condicion);
    this.actual.comprobaciones.push({ descripcion, bien, visto: String(visto) });
    console.log(`  ${bien ? ' ok  ' : 'FALLA'} ${descripcion}${visto === '' ? '' : `: ${visto}`}`);
    return bien;
  }

  igual(descripcion, visto, esperado) {
    return this.ok(`${descripcion} (se espera ${JSON.stringify(esperado)})`, visto === esperado, JSON.stringify(visto));
  }

  resumen() {
    console.log(`\n${'='.repeat(100)}\nRESUMEN\n${'='.repeat(100)}`);
    let fallas = 0;
    for (const e of this.escenarios) {
      const malas = e.comprobaciones.filter((c) => !c.bien);
      fallas += malas.length;
      console.log(`${malas.length === 0 ? 'PASA ' : 'FALLA'}  ${e.numero}. ${e.nombre}`
        + `  (${e.comprobaciones.length - malas.length}/${e.comprobaciones.length} comprobaciones)`);
      for (const m of malas) console.log(`         - ${m.descripcion}: ${m.visto}`);
    }
    return fallas;
  }
}

/** Comprueba que una respuesta de error sea un Problem Details del tipo pedido. */
export function esProblema(informe, r, estado, tipo, ruta) {
  const p = r.json ?? {};
  const bien = r.estado === estado
    && r.tipo.startsWith('application/problem+json')
    && p.type === `/problemas/${tipo}`
    && p.status === estado
    && typeof p.title === 'string' && p.title.length > 0
    && typeof p.detail === 'string' && p.detail.length > 0
    && (ruta === undefined || p.instance === ruta);
  return informe.ok(`${estado} ${tipo} en Problem Details`, bien,
    `${r.estado} ${r.tipo} ${r.texto.slice(0, 260)}`);
}
