// Prueba de carga de Palco con k6: 50 usuarios virtuales durante 5 minutos recorriendo el flujo principal (login,
// catálogo, detalle del evento y compra). Criterio, declarado como thresholds de k6: en cada endpoint crítico,
// p95 < 500 ms y menos de 1 % de errores. Si no se cumple alguno, k6 termina con error.
//
//   node scripts/carga/cuentas.mjs     una cuenta compradora nueva por usuario virtual (antes de la prueba)
//   k6 run scripts/carga/palco.js      la prueba, desde la raíz del repo
//
// El CI la corre con la imagen grafana/k6 contra el docker compose (.github/workflows/carga.yml), y ahí están
// los comandos exactos. Variables (todas opcionales):
//   PALCO_URL             dirección de la app                         (https://localhost: nginx, como la usa la gente)
//   PALCO_CUENTAS         el archivo de cuentas.mjs, relativo a este  (./resultados/cuentas.json)
//   PALCO_CARGA_SALIDA    carpeta del reporte, relativa a donde corre (scripts/carga/resultados)
//   PALCO_CARGA_VUS       usuarios virtuales                          (50)
//   PALCO_CARGA_DURACION  cuánto dura la carga                        (5m)
// Las dos últimas son para una corrida corta de prueba: la del criterio es con los valores por defecto.
//
// Los tres endpoints críticos son el camino de quien entra a comprar cuando sale a la venta un evento:
//   GET  /api/eventos          el catálogo. Es la portada y lo que más se pide: lo lee todo el que entra, con o sin cuenta.
//   GET  /api/eventos/{slug}   el detalle del evento, con sus sectores y lo que queda de cupo: el paso antes de comprar.
//   POST /api/v1/ventas        abrir la compra. Es la operación que más trabajo hace: Ventas le pregunta a Usuarios si
//                              la identidad está verificada, reserva el cupo en Eventos (que bloquea la fila del sector),
//                              guarda la venta y crea la instancia stateful de la compra.
// Cada compra abierta se cancela enseguida (DELETE /api/v1/ventas/{id}), que devuelve el cupo. Ese pedido también se
// mide y tiene los mismos umbrales: es parte del recorrido y, si fallara, las compras abiertas se acumularían.
//
// Lo que queda fuera de los umbrales, y por qué:
//   - El login. Cuesta unos 400 ms a propósito (PBKDF2 con 600.000 iteraciones) y no es lo que hace un comprador en
//     cada página: ingresa una vez y después navega y compra con su sesión. Por eso cada usuario virtual ingresa una
//     sola vez, en setup(), con su propia cuenta. Esos logins se miden y el reporte da su promedio, su p95 y su
//     máximo, pero no tienen umbral.
//   - El pago. Gastaría entradas de verdad: cada cuenta llega al tope de 6 por evento y su DNI queda con entrada, así
//     que la prueba no podría repetirse. Además el cobro lo hace el simulador de pagos, no Palco.
//
// Cada usuario virtual usa su propia cuenta: hay un tope de 6 entradas por persona y evento, y una compra abierta
// cuenta para el tope hasta que se cancela o vence. Entre página y página hay una pausa de entre 0,5 y 1,5 segundos,
// como la de una persona que lee lo que se cargó: sin pausas, 50 usuarios virtuales serían 50 clientes pidiendo en
// bucle, que es una prueba de estrés y no de carga.

import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';

const URL = (__ENV.PALCO_URL || 'https://localhost').replace(/\/$/, '');
const SALIDA = (__ENV.PALCO_CARGA_SALIDA || 'scripts/carga/resultados').replace(/\/$/, '');
const VUS = Number(__ENV.PALCO_CARGA_VUS || 50);
const DURACION = __ENV.PALCO_CARGA_DURACION || '5m';

/** La contraseña de las cuentas de prueba (está en el README) y de las que crea cuentas.mjs. */
const PASSWORD = 'palco1234';
const ORGANIZADOR = 'organizador@palco.test';

/**
 * Las cuentas compradoras. open() solo se puede llamar acá, al cargar el guion, y lee el archivo una sola vez.
 * Sin el archivo, k6 no arranca: el mensaje dice cómo crearlo.
 */
const ARCHIVO_DE_CUENTAS = __ENV.PALCO_CUENTAS || './resultados/cuentas.json';
let CUENTAS;
try {
  CUENTAS = JSON.parse(open(ARCHIVO_DE_CUENTAS));
} catch (error) {
  throw new Error(`No se pudo leer ${ARCHIVO_DE_CUENTAS} (${error}): corré antes node scripts/carga/cuentas.mjs`);
}

/** Los pedidos que se miden, con el nombre que llevan en la etiqueta "endpoint" y en el reporte. */
const ENDPOINTS = [
  { etiqueta: 'catalogo', nombre: 'GET /api/eventos (catálogo)' },
  { etiqueta: 'detalle', nombre: 'GET /api/eventos/{slug} (detalle)' },
  { etiqueta: 'abrir_compra', nombre: 'POST /api/v1/ventas (abrir compra)' },
  { etiqueta: 'cancelar_compra', nombre: 'DELETE /api/v1/ventas/{id} (cancelar)' },
];

// Los umbrales van sobre los pedidos del escenario "carga": así no cuentan los logins y el alta del evento de
// setup(), ni el calentamiento. Cada endpoint tiene los suyos, y también el total.
const umbrales = {
  'http_req_duration{scenario:carga}': ['p(95)<500'],
  'http_req_failed{scenario:carga}': ['rate<0.01'],
  // Una respuesta 200 con otra cosa adentro también es un error: las comprobaciones miran el cuerpo.
  'checks{scenario:carga}': ['rate>0.99'],
};
for (const { etiqueta } of ENDPOINTS) {
  umbrales[`http_req_duration{scenario:carga,endpoint:${etiqueta}}`] = ['p(95)<500'];
  umbrales[`http_req_failed{scenario:carga,endpoint:${etiqueta}}`] = ['rate<0.01'];
}

export const options = {
  scenarios: {
    // 30 segundos con pocos usuarios antes de medir: el servidor del CI recién arrancó y las primeras llamadas
    // cargan clases y compilan código. Un sistema en producción ya está caliente cuando llega la gente.
    calentamiento: { executor: 'constant-vus', vus: 5, duration: '30s', gracefulStop: '10s' },
    carga: { executor: 'constant-vus', vus: VUS, duration: DURACION, startTime: '40s', gracefulStop: '30s' },
  },
  thresholds: umbrales,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'],
  // El compose sirve HTTPS con un certificado autofirmado que genera al arrancar.
  insecureSkipTLSVerify: true,
  // Los logins de setup() son de a varios a la vez, pero cada uno cuesta un hash de contraseña.
  setupTimeout: '3m',
  teardownTimeout: '1m',
};

const json = (cuerpo) => JSON.stringify(cuerpo);
const conToken = (token) => ({ Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' });
const pausa = () => sleep(0.5 + Math.random());

/** El cuerpo como JSON, o null si no lo es (un error de nginx, por ejemplo). */
function cuerpo(respuesta) {
  try {
    return respuesta.json();
  } catch {
    return null;
  }
}

/** Una fecha a tantos días de hoy, como la pide Eventos: hora de Argentina, sin zona. */
function fechaDentroDe(dias) {
  return `${new Date(Date.now() + dias * 24 * 60 * 60 * 1000).toISOString().slice(0, 10)}T21:00:00`;
}

function fallar(mensaje, respuesta) {
  exec.test.abort(`${mensaje}: ${respuesta.status} ${String(respuesta.body).slice(0, 300)}`);
}

/**
 * Antes de la carga: el evento de la prueba y una sesión por usuario virtual. El evento es propio, con cupo de sobra,
 * así la prueba no toca el cupo de los eventos del catálogo ni choca con lo que esté haciendo otra persona.
 */
export function setup() {
  if (CUENTAS.length < VUS) {
    exec.test.abort(`Hay ${CUENTAS.length} cuentas y hacen falta ${VUS}: corré node scripts/carga/cuentas.mjs ${VUS}`);
  }
  const preparacion = { tags: { fase: 'preparacion' } };

  const sesion = http.post(`${URL}/api/usuarios/login`, json({ email: ORGANIZADOR, password: PASSWORD }), {
    ...preparacion,
    headers: { 'Content-Type': 'application/json' },
  });
  if (sesion.status !== 200) fallar('El login del organizador falló', sesion);
  const tokenOrganizador = sesion.json('token');

  const alta = http.post(`${URL}/api/eventos`, json({
    titulo: `Prueba de carga ${new Date().toISOString().slice(0, 16).replace('T', ' ')}`,
    categoria: 'Música',
    venue: 'Estadio de pruebas, Rosario',
    fechaISO: fechaDentroDe(60),
    descripcion: 'Evento creado por la prueba de carga (scripts/carga/palco.js). Se cancela al terminar.',
    sectores: [{ nombre: 'Campo', precio: 20000, cupo: 1000 }],
  }), { ...preparacion, headers: conToken(tokenOrganizador) });
  if (alta.status !== 201) fallar('No se pudo crear el evento de la prueba', alta);
  const evento = { id: alta.json('id'), slug: alta.json('slug'), sectorId: alta.json('sectores.0.id') };

  // Un login por cuenta, de a varios a la vez (http.batch abre hasta 6 conexiones por servidor).
  const cuentas = CUENTAS.slice(0, VUS);
  const logins = http.batch(cuentas.map((cuenta) => ({
    method: 'POST',
    url: `${URL}/api/usuarios/login`,
    body: json({ email: cuenta.email, password: PASSWORD }),
    params: { ...preparacion, headers: { 'Content-Type': 'application/json' } },
  })));
  const compradores = logins.map((respuesta, i) => {
    if (respuesta.status !== 200) fallar(`El login de ${cuentas[i].email} falló`, respuesta);
    return { token: respuesta.json('token'), nombre: cuentas[i].nombre, dni: cuentas[i].dni };
  });
  // Lo que tardó cada login, de menor a mayor: el reporte da el promedio, el p95 y el máximo.
  const duraciones = logins.map((r) => r.timings.duration).sort((a, b) => a - b);
  const login = {
    promedio: duraciones.reduce((suma, d) => suma + d, 0) / duraciones.length,
    p95: duraciones[Math.ceil(duraciones.length * 0.95) - 1],
    maximo: duraciones[duraciones.length - 1],
  };

  console.log(`Evento de la prueba: ${evento.slug}. ${compradores.length} sesiones abiertas.`);
  return { evento, tokenOrganizador, compradores, login };
}

/** Lo que hace cada usuario virtual, una y otra vez: catálogo, detalle, abrir la compra y cancelarla. */
export default function (datos) {
  const { evento } = datos;
  // Los usuarios virtuales se numeran desde 1, y el calentamiento usa los primeros 5: cada uno con su cuenta.
  const comprador = datos.compradores[(exec.vu.idInTest - 1) % datos.compradores.length];

  const catalogo = http.get(`${URL}/api/eventos`, { tags: { endpoint: 'catalogo' } });
  check(catalogo, {
    'catálogo: 200 con el evento de la prueba': (r) => r.status === 200 && (cuerpo(r) ?? []).some((e) => e.id === evento.id),
  });
  pausa();

  const detalle = http.get(`${URL}/api/eventos/${evento.slug}`, { tags: { endpoint: 'detalle' } });
  check(detalle, { 'detalle: 200 con sus sectores': (r) => r.status === 200 && cuerpo(r)?.sectores?.length > 0 });
  pausa();

  // Cada compra lleva su clave de idempotencia: repetir el pedido devolvería la misma venta.
  const compra = http.post(`${URL}/api/v1/ventas`, json({
    eventoId: evento.id,
    items: [{ sectorId: evento.sectorId, cantidad: 1 }],
    titulares: [{ nombre: comprador.nombre, dni: comprador.dni }],
  }), {
    headers: { ...conToken(comprador.token), 'Idempotency-Key': crypto.randomUUID() },
    tags: { endpoint: 'abrir_compra' },
  });
  const venta = cuerpo(compra);
  check(compra, { 'abrir compra: 201 en checkout': (r) => r.status === 201 && venta?.estado === 'EN_CHECKOUT' });
  pausa();

  if (compra.status === 201 && venta?.id) {
    const cancelacion = http.del(`${URL}/api/v1/ventas/${venta.id}`, null, {
      headers: conToken(comprador.token),
      tags: { endpoint: 'cancelar_compra' },
    });
    check(cancelacion, { 'cancelar compra: 204': (r) => r.status === 204 });
  }
  pausa();
}

/** Al terminar, el evento de la prueba se cancela: sale del catálogo. Sus compras ya están canceladas. */
export function teardown(datos) {
  const respuesta = http.post(`${URL}/api/eventos/${datos.evento.id}/cancelacion`,
    json({ motivo: 'Fin de la prueba de carga.' }),
    { headers: conToken(datos.tokenOrganizador), tags: { fase: 'preparacion' } });
  if (respuesta.status !== 200 && respuesta.status !== 204) {
    console.warn(`No se pudo cancelar el evento de la prueba: ${respuesta.status}`);
  }
}

// ------------------------------------------------------------------------------------------------- reporte

const ms = (valor) => (valor === undefined ? '-' : `${valor.toFixed(valor < 100 ? 1 : 0)} ms`);
const porcentaje = (valor) => (valor === undefined ? '-' : `${(valor * 100).toFixed(2)} %`);
const columna = (texto, ancho) => String(texto).padStart(ancho);

/** Una duración de k6 en segundos: "5m" son 300, "1m30s" son 90. */
function segundosDe(duracion) {
  const [, h = 0, m = 0, s = 0] = duracion.match(/^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$/) ?? [];
  return Number(h) * 3600 + Number(m) * 60 + Number(s) || 1;
}

function cumplido(metrica) {
  const umbralesDeLaMetrica = Object.values(metrica?.thresholds ?? {});
  if (umbralesDeLaMetrica.length === 0) return '-';
  return umbralesDeLaMetrica.every((u) => u.ok) ? 'sí' : 'NO';
}

/** Una línea de la tabla: pedidos, errores, percentiles y si cumple los dos umbrales. */
function fila(nombre, duracion, fallidos) {
  const d = duracion?.values ?? {};
  const f = fallidos?.values ?? {};
  const pedidos = d.count ?? (f.passes ?? 0) + (f.fails ?? 0);
  return `${nombre.padEnd(40)}${columna(pedidos, 8)}${columna(porcentaje(f.rate), 10)}${columna(ms(d.med), 10)}`
    + `${columna(ms(d['p(90)']), 10)}${columna(ms(d['p(95)']), 10)}${columna(ms(d['p(99)']), 10)}${columna(ms(d.max), 10)}`
    + `${columna(cumplido(duracion), 10)}${columna(cumplido(fallidos), 8)}`;
}

/** El reporte en texto: el criterio, una tabla por endpoint, los umbrales y lo que costó la preparación. */
function reporte(data) {
  const m = data.metrics;
  const deCarga = (metrica, etiqueta) => m[`${metrica}{scenario:carga,endpoint:${etiqueta}}`];
  const umbralesNoCumplidos = Object.entries(m)
    .flatMap(([nombre, metrica]) => Object.entries(metrica.thresholds ?? {})
      .filter(([, u]) => !u.ok).map(([umbral]) => `${nombre} ${umbral}`));
  const pedidosMedidos = m['http_req_duration{scenario:carga}']?.values.count ?? 0;
  const segundos = data.state.testRunDurationMs / 1000;
  const ahora = new Date(Date.now() - 3 * 60 * 60 * 1000).toISOString().slice(0, 16).replace('T', ' ');
  const checks = m['checks{scenario:carga}']?.values;
  const login = data.setup_data?.login;

  return [
    'Prueba de carga de Palco (k6)',
    `Fecha: ${ahora} (hora de Argentina)${__ENV.PALCO_K6 ? ` · ${__ENV.PALCO_K6}` : ''}`,
    `Sistema: ${URL}`,
    `Carga: ${VUS} usuarios virtuales durante ${DURACION}, cada uno con su cuenta y su sesión, después de 30 s de`
      + ' calentamiento con 5. Pausa de 0,5 a 1,5 s entre página y página.',
    'Criterio: en cada endpoint, p95 < 500 ms y menos de 1 % de errores (solo cuenta el escenario de carga).',
    '',
    `${'Endpoint'.padEnd(40)}${columna('pedidos', 8)}${columna('errores', 10)}${columna('p50', 10)}${columna('p90', 10)}`
      + `${columna('p95', 10)}${columna('p99', 10)}${columna('máx', 10)}${columna('p95<500', 10)}${columna('err<1%', 8)}`,
    ...ENDPOINTS.map(({ etiqueta, nombre }) =>
      fila(nombre, deCarga('http_req_duration', etiqueta), deCarga('http_req_failed', etiqueta))),
    fila('Todos los pedidos medidos', m['http_req_duration{scenario:carga}'], m['http_req_failed{scenario:carga}']),
    '',
    `Pedidos medidos: ${pedidosMedidos}, unos ${Math.round(pedidosMedidos / segundosDe(DURACION))} por segundo.`,
    `Comprobaciones del cuerpo de las respuestas: ${porcentaje(checks?.rate)} bien`
      + ` (${checks?.passes ?? 0} de ${(checks?.passes ?? 0) + (checks?.fails ?? 0)}).`,
    `Login (una vez por usuario virtual, en setup, sin umbral): ${data.setup_data?.compradores?.length ?? 0}`
      + ` sesiones, de a 6 a la vez. Promedio ${ms(login?.promedio)}, p95 ${ms(login?.p95)},`
      + ` máximo ${ms(login?.maximo)}.`,
    `Duración total de la corrida, con la preparación: ${Math.round(segundos)} s.`,
    '',
    umbralesNoCumplidos.length === 0
      ? 'Resultado: se cumplen todos los umbrales.'
      : `Resultado: NO se ${umbralesNoCumplidos.length === 1 ? 'cumple un umbral' : `cumplen ${umbralesNoCumplidos.length} umbrales`}:`
        + `\n  ${umbralesNoCumplidos.join('\n  ')}`,
    '',
  ].join('\n');
}

/** Al final: el reporte en texto (también por pantalla) y todos los números en JSON, sin los tokens de setup(). */
export function handleSummary(data) {
  const { setup_data: _sesiones, ...sinSesiones } = data;
  const texto = reporte(data);
  return {
    stdout: texto,
    [`${SALIDA}/carga-k6.txt`]: texto,
    [`${SALIDA}/carga-k6.json`]: JSON.stringify(sinSesiones, null, 2),
  };
}
