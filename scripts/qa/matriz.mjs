// Matriz de trazabilidad: historia → criterio → casos → evidencia → defectos, armada a partir de los reportes.
//
//   node scripts/qa/matriz.mjs [--escenarios salida.txt] [--junit carpeta]... [--newman reporte]...
//                              [--playwright reporte]... [--salida matriz.md]
//
// - Las historias y los criterios salen de docs/documentacion/historias-de-usuario.md.
// - Un caso con un ID "CP-xx.y.z" en el nombre (convención del documento de HU) va al criterio de ese CP, venga de
//   JUnit, Newman o Playwright.
// - La salida de scripts/pruebas/escenarios.mjs aporta el resultado de cada escenario; qué criterios cubre cada
//   uno sale de la tabla "Cobertura actual por la prueba de integración" del documento de HU.
// - Los reportes de JUnit (--junit, carpetas surefire-reports o failsafe-reports) aportan además los tests de la
//   tabla "Cobertura por los tests unitarios" del documento de HU (Clase#método por criterio). Un test de la tabla
//   que no está en los reportes cuenta como falla.
// - --newman acepta el XML del reporter junit de Newman (el que exporta el CI en newman/coleccion.xml, reenvio.xml
//   y verificacion.xml) o el JSON del reporter json. --playwright acepta la carpeta playwright-report (o su
//   index.html: el reporte html que sube el CI) o el JSON del reporter json. Los pedidos de la colección no llevan
//   CP en el nombre, y la mayoría de las pruebas de Playwright tampoco: qué criterio comprueba cada uno sale de
//   CRITERIOS_DE_NEWMAN y CRITERIOS_DE_PLAYWRIGHT, más abajo.
// - La columna Evidencia dice, para cada caso y con el mismo número, de dónde sale: el archivo de la clase de
//   JUnit, el spec de Playwright con su línea, el pedido de la colección de Postman o el escenario de
//   escenarios.mjs con su línea.
// - La columna Defectos lista los DEF-xx de docs/testing/registro-de-defectos.md vinculados al criterio, con su
//   severidad y su estado. El registro no tiene una columna de HU: el vínculo sale de lo que nombra la fila del
//   resumen o el detalle del defecto. Un "CA-xx.y", un rango "CA-xx.y a CA-xx.z" o un "CP-xx.y.z" lo vinculan a
//   ese criterio. Si el defecto no nombra ningún criterio, un "HU-xx" lo vincula a todos los de esa historia.
//
// Un criterio sin ningún caso queda "SIN CASO" y uno con casos que solo se omitieron, "OMITIDO". Si hay criterios
// sin caso, omitidos o con casos que fallan, sale con código 1. Solo usa módulos que trae Node.
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const HU = path.join(REPO, 'docs', 'documentacion', 'historias-de-usuario.md');
const DEFECTOS = path.join(REPO, 'docs', 'testing', 'registro-de-defectos.md');
const ESCENARIOS = 'scripts/pruebas/escenarios.mjs';
const COLECCION = 'docs/api/palco-api.postman_collection.json';
// El testDir de frontend/playwright.config.ts: los reportes dan la ruta del spec desde ahí.
const SPECS = 'frontend/e2e';

const args = process.argv.slice(2);
const valores = (opcion) => args.flatMap((a, i) => (a === opcion && args[i + 1] ? [args[i + 1]] : []));
const salida = valores('--salida')[0];
// Las rutas del repo van relativas a la raíz; un reporte de afuera (bajado del CI, por ejemplo), como se pasó.
const relativa = (p) => {
  const r = path.relative(REPO, path.resolve(p));
  return (r.startsWith('..') || path.isAbsolute(r) ? p : r).split(path.sep).join('/');
};

// ------------------------------------------------------------------- casos sin CP en el nombre
// Qué criterio comprueba cada pedido de la colección de Postman (scripts/postman/generar.js), por el nombre del
// pedido. Entra solo el pedido cuyos pm.test comprueban lo que dice el "Entonces" del criterio (el código y el
// type, o el estado que deja): los pasos que solo preparan datos (logins, eventos de prueba) no entran.
const CRITERIOS_DE_NEWMAN = {
  'Registro (201)': ['CA-01.1'],
  'MANUAL · Verificar email (200)': ['CA-01.1'],
  'Reenviar código antes de los 30 segundos (409)': ['CA-01.6'],
  'MANUAL · Reenviar código (204)': ['CA-01.6'],
  'Login (200) guarda token': ['CA-02.1'],
  'Mi perfil (200)': ['CA-02.1'],
  'Login incorrecto (401)': ['CA-02.2'],
  'SEC-W03b Login con la clave incorrecta (401, el mismo mensaje)': ['CA-02.2'],
  'SEC-W04 Sexto login fallido en 15 minutos (429, Retry-After)': ['CA-02.3'],
  'SEC-W05a GET /yo con la firma del token alterada (401)': ['CA-02.4'],
  'SEC-W05b GET /yo con un token alg=none (401)': ['CA-02.4'],
  'Login con el email sin verificar (409)': ['CA-02.5'],
  'POST /password/olvide de una cuenta que existe (204)': ['CA-03.1'],
  'POST /password/olvide de un email que no existe (204, la misma respuesta)': ['CA-03.2'],
  'POST /password/reinicio con un código incorrecto (400)': ['CA-03.3'],
  'Listar eventos (200) guarda slug': ['CA-04.1'],
  'Detalle por slug (200)': ['CA-04.1'],
  'Detalle inexistente (404)': ['CA-04.3'],
  'POST /v1/ventas (201) guarda ventaId': ['CA-05.1'],
  'GET /v1/ventas/{id} (200, EN_CHECKOUT)': ['CA-05.1'],
  'POST /v1/ventas/{id}/pagos (201) guarda entradaId': ['CA-05.1'],
  'GET /v1/entradas/{id} emitida (200, VALIDA con codigoQr)': ['CA-05.1'],
  'GET /v1/ventas/{id} (200, EMITIDA)': ['CA-05.1'],
  'POST /v1/ventas/{id}/pagos con el cobro rechazado (402)': ['CA-05.2'],
  'POST /v1/ventas con el DNI repetido (422)': ['CA-05.3'],
  'POST /v1/ventas repetido con la misma clave (201, la misma venta)': ['CA-05.4'],
  'POST /v1/ventas misma clave con otro pedido (409)': ['CA-05.4'],
  'POST /v1/ventas/{id}/pagos repetido con la misma clave (201, el mismo pago)': ['CA-05.4'],
  'DELETE /v1/ventas/{id} (204)': ['CA-05.7'],
  'POST /v1/ventas/{id}/pagos de una venta cancelada (409)': ['CA-05.7'],
  'GET /v1/facturas/{ventaId} (200) guarda el comprobante': ['CA-06.1'],
  'GET /v1/facturas/{ventaId} de otro comprador (404)': ['CA-06.4'],
  'GET /v1/ventas mis compras (200)': ['CA-07.1'],
  'SEC-W01 GET /v1/entradas/{id} de A con el token de B (404, sin datos)': ['CA-07.3'],
  'POST /v1/reventas (201) guarda publicacionId': ['CA-08.1'],
  'GET /v1/entradas/{id} publicada (200, PUBLICADA)': ['CA-08.1'],
  'POST /v1/reventas sobre el tope (422)': ['CA-08.2'],
  'POST /v1/reventas bajo el mínimo (422)': ['CA-08.2'],
  'POST /v1/reventas de la misma entrada (409)': ['CA-08.3'],
  'DELETE /v1/reventas/{id} (204)': ['CA-08.4'],
  'DELETE /v1/reventas/{id} repetido (409)': ['CA-08.4'],
  'GET /v1/entradas/{id} retirada (200, VALIDA)': ['CA-08.4'],
  'POST /v1/reventas/{id}/compra (201) guarda entradaCompradaId': ['CA-09.1'],
  'GET /v1/entradas/{id} original (200, VENDIDA)': ['CA-09.1'],
  'GET /v1/entradas/{id} del comprador (200, VALIDA)': ['CA-09.1'],
  'POST /v1/reventas/{id}/compra con el cobro rechazado (402)': ['CA-09.2'],
  'POST /v1/reventas/{id}/compra propia (409)': ['CA-09.3'],
  'POST /v1/reventas/{id}/compra ya vendida (409)': ['CA-09.3'],
  'Crear evento, rol organizer (201) guarda eventoCreadoId': ['CA-10.1'],
  'SEC-W02 Crear evento con rol user (403)': ['CA-10.3'],
  'PUT /eventos/{id} ajeno (404)': ['CA-10.3'],
  'Mis eventos, rol organizer (200)': ['CA-10.4'],
  'PUT /eventos/{id} propio (200)': ['CA-10.5'],
  'PUT /eventos/{id} con el ETag de antes de la compra (412)': ['CA-10.7'],
  'POST /eventos/{id}/cancelacion propio (200)': ['CA-11.1'],
  'GET /v1/ventas/{id} anulada (200, CANCELADA)': ['CA-11.1'],
  'GET /v1/pagos/{id} de la venta anulada (200, REEMBOLSADO)': ['CA-11.1'],
  'Detalle del evento cancelado (200)': ['CA-11.1'],
  'POST /eventos/{id}/cancelacion ajeno (404)': ['CA-11.2'],
  'POST /v1/ventas en el evento cancelado (409)': ['CA-11.3'],
  'POST /eventos/{id}/cancelacion repetida (409)': ['CA-11.5'],
  'GET /v1/resumenes?eventoId propio (200)': ['CA-12.1'],
  'GET /v1/resumenes?eventoId ajeno (404)': ['CA-12.2'],
  'GET /v1/resumenes?eventoId con rol user (403)': ['CA-12.3'],
  'POST /v1/validacion/escaneos (201, VALIDA) guarda escaneoId': ['CA-13.1'],
  'GET /v1/entradas/{id} usada (200, USADA)': ['CA-13.1'],
  'POST /v1/validacion/escaneos repetido (201, YA_USADA)': ['CA-13.2'],
  'POST /v1/validacion/escaneos código inexistente (201, INEXISTENTE)': ['CA-13.3'],
  'POST /v1/validacion/escaneos en otro evento (201, OTRO_EVENTO)': ['CA-13.3'],
  // Corre después de quitar la asignación: es el 403 del final de CA-14.1 y el de una cuenta no asignada (CA-13.5).
  'POST /v1/validacion/escaneos sin la puerta asignada (403)': ['CA-13.5', 'CA-14.1'],
  'PUT /v1/validacion/puertas/{eventoId}/{email} (200)': ['CA-14.1'],
  'GET /v1/validacion/puertas/mias, rol staff (200)': ['CA-14.1'],
  'DELETE /v1/validacion/puertas/{eventoId}/{email} (204)': ['CA-14.1'],
  'PUT /v1/validacion/puertas/{eventoId}/{email} de una cuenta que no es de puerta (422)': ['CA-14.3'],
  'POST /v1/asistente/preguntas (200, MODELO)': ['CA-15.1'],
  'POST /v1/asistente/preguntas con inyección de prompt (200, BLOQUEADA)': ['CA-15.3'],
  'POST /eventos/imagenes (200) guarda imagenId': ['CA-16.1'],
  'GET /eventos/imagenes/{id} (200, la foto subida)': ['CA-16.1'],
  'POST /eventos/imagenes con texto declarado como PNG (400)': ['CA-16.2'],
  'POST /eventos/imagenes con un SVG (415)': ['CA-16.3'],
};

// Las pruebas de frontend/e2e sin CP en el título: [spec, comienzo del título, criterios]. Las que no comprueban
// ningún criterio (accesibilidad, temas, carga diferida, caídas del servidor simuladas en el navegador) no
// entran. La primera del asistente tampoco: acepta la respuesta del modelo o la de las preguntas frecuentes, y
// CA-15.1 pide la del modelo.
const CRITERIOS_DE_PLAYWRIGHT = [
  ['registro.spec.ts', 'registro: crea una cuenta, la verifica', ['CA-01.1']],
  ['sesion.spec.ts', 'ingreso con rol ', ['CA-02.1']],
  ['catalogo.spec.ts', 'catálogo: busca un evento, lo filtra por categoría', ['CA-04.1', 'CA-04.2']],
  ['catalogo.spec.ts', 'un evento que no existe: el 404', ['CA-04.3']],
  ['xss.spec.ts', 'XSS sin pasar por el formulario', ['CA-04.4']],
  ['compra.spec.ts', 'compra completa: del evento al checkout', ['CA-05.1', 'CA-07.1']],
  ['compra.spec.ts', 'tarjeta rechazada:', ['CA-05.2']],
  ['factura-rechazada.spec.ts', 'factura rechazada:', ['CA-06.3']],
  ['factura-rechazada.spec.ts', 'factura demorada:', ['CA-06.3']],
  ['reventa.spec.ts', 'reventa: publica una entrada', ['CA-08.1', 'CA-09.1']],
  ['organizador.spec.ts', 'organizador: crea un evento con foto', ['CA-10.1', 'CA-10.5', 'CA-11.1', 'CA-16.1']],
  ['organizador.spec.ts', 'organizador: ve las ventas por día', ['CA-12.1']],
  ['puerta.spec.ts', 'puerta: valida una entrada', ['CA-13.1', 'CA-13.2', 'CA-13.3']],
  ['asistente.spec.ts', 'asistente: un intento de inyección de prompt', ['CA-15.3']],
];

// ------------------------------------------------------------------- historias y criterios
const texto = fs.readFileSync(HU, 'utf8');
const historias = [...texto.matchAll(/^## (HU-\d+) (.+?) · (\w+)$/gm)]
  .map(([, id, nombre, prioridad]) => ({ id, nombre, prioridad }));
// Un criterio es un "Escenario" o, si se prueba con varios datos, un "Esquema del escenario" con sus Ejemplos.
const criterios = [...texto.matchAll(/(?:Esquema del escenario|Escenario): (CA-(\d+)\.\d+) (.+)$/gm)]
  .map(([, id, hu, nombre]) => ({ id, hu: `HU-${hu}`, nombre: nombre.trim(), casos: [], defectos: [] }));
const porId = new Map(criterios.map((c) => [c.id, c]));

// Tabla "criterios | escenario" del documento.
const [tabla = '', tablaUnitarios = ''] = (texto.split('## Cobertura actual por la prueba de integración')[1] ?? '')
  .split('## Cobertura por los tests unitarios');
const escenariosDe = new Map(); // CA → [números de escenario]
for (const [, cas, escenarios] of tabla.matchAll(/^\| (CA-[^|]+) \| ([^|]+) \|$/gm)) {
  const numeros = [...escenarios.matchAll(/\d+/g)].map(Number);
  for (const ca of cas.split(',').map((s) => s.trim())) escenariosDe.set(ca, numeros);
}

// Tabla "criterio | Clase#método, ..." de los tests unitarios.
const unitariosDe = new Map(); // CA → ['Clase#método']
for (const [, ca, tests] of tablaUnitarios.matchAll(/^\| (CA-[\d.]+) \| ([^|]+) \|$/gm)) {
  unitariosDe.set(ca.trim(), tests.split(',').map((t) => t.trim()).filter((t) => t.includes('#')));
}

// ------------------------------------------------------------------- reportes
const CP = /CP-\d+\.\d+\.\d+/g;
const criterioDelCp = (cp) => `CA-${cp.split('-')[1].split('.').slice(0, 2).join('.')}`;
const peso = (resultado) => (resultado.startsWith('falla') ? 2 : resultado.startsWith('pasa') ? 1 : 0);
// Si un test falló en alguna corrida, cuenta como falla; si pasó en alguna y en ninguna falló, como pasa.
const peor = (a, b) => (peso(b) > peso(a) ? b : a);

// El mismo caso puede llegar varias veces (un parametrizado, un pedido en dos corridas, una prueba en dos
// proyectos de Playwright): queda una sola fila con el peor resultado.
function agregar(ca, caso) {
  const c = porId.get(ca);
  if (!c) {
    console.warn(`${caso.id} (${caso.fuente}) va a ${ca}, que no es ningún criterio del documento de HU`);
    return;
  }
  const igual = c.casos.find((k) => k.id === caso.id && k.fuente === caso.fuente && k.evidencia === caso.evidencia);
  if (igual) igual.resultado = peor(igual.resultado, caso.resultado);
  else c.casos.push({ ...caso });
}

const desescapar = (s) => s.replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>')
  .replace(/&#x([0-9a-f]+);/gi, (_, h) => String.fromCodePoint(parseInt(h, 16)))
  .replace(/&#(\d+);/g, (_, d) => String.fromCodePoint(Number(d))).replace(/&amp;/g, '&');
const reportes = { 'escenarios.mjs': [], JUnit: [], Newman: [], Playwright: [] }; // fuente → reportes leídos
const cuantos = (n, palabra) => `${n} ${palabra}${n === 1 ? '' : 's'}`;

// escenarios.mjs: líneas del resumen "PASA   12. ..." o "FALLA  12. ...". La evidencia es la línea del
// escenario en escenarios.mjs (la llamada a informe.escenario).
const lineaDelEscenario = new Map();
if (valores('--escenarios').length > 0) {
  fs.readFileSync(path.join(REPO, ESCENARIOS), 'utf8').split('\n').forEach((linea, i) => {
    const n = linea.match(/informe\.escenario\((\d+),/)?.[1];
    if (n) lineaDelEscenario.set(Number(n), i + 1);
  });
}
for (const archivo of valores('--escenarios')) {
  reportes['escenarios.mjs'].push(relativa(archivo));
  const resultados = new Map();
  for (const [, estado, n] of fs.readFileSync(archivo, 'utf8').matchAll(/^(PASA|FALLA)\s+(\d+)\. /gm)) {
    resultados.set(Number(n), estado === 'PASA' ? 'pasa' : 'falla');
  }
  for (const [ca, numeros] of escenariosDe) {
    for (const n of numeros) {
      if (!resultados.has(n)) continue;
      const evidencia = `${ESCENARIOS}${lineaDelEscenario.has(n) ? `:${lineaDelEscenario.get(n)}` : ''}`;
      agregar(ca, { id: `escenario ${n}`, resultado: resultados.get(n), fuente: 'escenarios.mjs', evidencia });
    }
  }
}

// JUnit XML: <testcase name="..." classname="..."> con <failure> o <error> adentro si falló. La evidencia es el
// archivo de la clase: el reporte está en <módulo>/target/<surefire|failsafe>-reports y la clase, en
// <módulo>/src/test/java. Si no se encuentra (un reporte bajado del CI, fuera del repo), se busca en los módulos
// de backend y, si tampoco está, queda el reporte.
const modulos = [path.join(REPO, 'backend', 'common'), ...fs.readdirSync(path.join(REPO, 'backend', 'servicios'))
  .map((m) => path.join(REPO, 'backend', 'servicios', m))];
const fuentes = new Map(); // 'paquete.Clase' → ruta en el repo
function fuenteDe(clase, archivoDelReporte) {
  const claseExterna = clase.split('$')[0];
  if (!fuentes.has(claseExterna)) {
    const java = path.join('src', 'test', 'java', ...claseExterna.split('.')) + '.java';
    const moduloDelReporte = path.dirname(path.dirname(path.dirname(archivoDelReporte)));
    const encontrada = [moduloDelReporte, ...modulos].map((m) => path.join(m, java)).find((f) => fs.existsSync(f));
    fuentes.set(claseExterna, encontrada ? relativa(encontrada) : null);
  }
  return fuentes.get(claseExterna) ?? relativa(archivoDelReporte);
}
const xmls = (dir) => (fs.existsSync(dir) ? fs.readdirSync(dir, { recursive: true }) : [])
  .filter((f) => String(f).endsWith('.xml')).map((f) => path.join(dir, String(f)));
const unitarios = new Map(); // 'Clase#método' → { resultado, evidencias }, de todos los reportes JUnit
for (const dir of valores('--junit')) {
  const archivos = xmls(dir);
  reportes.JUnit.push(`${relativa(dir)} (${cuantos(archivos.length, 'archivo')})`);
  for (const archivo of archivos) {
    const xml = fs.readFileSync(archivo, 'utf8');
    for (const [, atributos, cuerpo = ''] of xml.matchAll(/<testcase([^>]*?)(?:\/>|>([\s\S]*?)<\/testcase>)/g)) {
      const resultado = /<(failure|error)\b/.test(cuerpo) ? 'falla' : /<skipped\b/.test(cuerpo) ? 'omitido' : 'pasa';
      const nombre = desescapar(atributos.match(/\bname="([^"]+)"/)?.[1] ?? '');
      const claseCompleta = desescapar(atributos.match(/\bclassname="([^"]+)"/)?.[1] ?? '');
      const evidencia = claseCompleta ? fuenteDe(claseCompleta, archivo) : relativa(archivo);
      for (const [cp] of nombre.matchAll(CP)) agregar(criterioDelCp(cp), { id: cp, resultado, fuente: 'JUnit', evidencia });
      if (nombre && claseCompleta) {
        // El método sin lo que va desde el primer paréntesis (los parámetros de un parametrizado).
        const clave = `${claseCompleta.split('.').pop()}#${nombre.split('(')[0]}`;
        const anterior = unitarios.get(clave) ?? { resultado: 'omitido', evidencias: new Set() };
        anterior.resultado = peor(anterior.resultado, resultado);
        anterior.evidencias.add(evidencia);
        unitarios.set(clave, anterior);
      }
    }
  }
}
if (valores('--junit').length > 0) {
  for (const [ca, tests] of unitariosDe) {
    for (const test of tests) {
      const visto = unitarios.get(test);
      agregar(ca, {
        id: test,
        resultado: visto?.resultado ?? 'falla (no está en los reportes)',
        fuente: 'JUnit',
        evidencia: visto ? [...visto.evidencias].join(', ') : 'ningún reporte lo tiene',
      });
    }
  }
}

// Casos de Newman y de Playwright: el CP del nombre o, si no tiene, la tabla de más arriba. Avisa por consola de
// las filas de la tabla que ningún reporte trajo (un pedido o una prueba que cambió de nombre).
function agregarSinCp(caso, criteriosDeLaTabla, usados, clave) {
  const cps = [...caso.id.matchAll(CP)].map(([cp]) => cp);
  if (cps.length > 0) {
    for (const cp of cps) agregar(criterioDelCp(cp), { ...caso, id: cp });
    return;
  }
  for (const ca of criteriosDeLaTabla ?? []) agregar(ca, caso);
  if (criteriosDeLaTabla) usados.add(clave);
}

// Newman, reporter junit: un <testsuite name="carpeta / pedido"> por pedido, con un <testcase> por cada pm.test y
// un <failure> o un <error> si algo falló. Reporter json: run.executions[] con el item y sus assertions; la
// carpeta sale del árbol de la colección, que viene en el mismo archivo.
function pedidosDeNewman(archivo) {
  const contenido = fs.readFileSync(archivo, 'utf8');
  if (contenido.trimStart().startsWith('<')) {
    const suites = contenido.matchAll(/<testsuite\b([^>]*?)(?:\/>|>([\s\S]*?)<\/testsuite>)/g);
    return [...suites].map(([, atributos, cuerpo = '']) => ({
      nombre: desescapar(atributos.match(/\bname="([^"]*)"/)?.[1] ?? ''),
      resultado: /<(failure|error)\b/.test(cuerpo) ? 'falla' : /<testcase\b/.test(cuerpo) ? 'pasa' : 'omitido',
    }));
  }
  const { run, collection } = JSON.parse(contenido);
  const rutas = new Map(); // id del item → 'carpeta / pedido'
  const recorrer = (items = [], carpeta = []) => items.forEach((item) => (item.item
    ? recorrer(item.item, [...carpeta, item.name])
    : rutas.set(item.id, [...carpeta, item.name].join(' / '))));
  recorrer(collection?.item);
  return run.executions.map((e) => ({
    nombre: rutas.get(e.item.id) ?? e.item.name,
    resultado: e.requestError || (e.assertions ?? []).some((a) => a.error) ? 'falla'
      : (e.assertions ?? []).length > 0 ? 'pasa' : 'omitido',
  }));
}
const pedidosUsados = new Set();
for (const archivo of valores('--newman')) {
  const pedidos = pedidosDeNewman(archivo);
  reportes.Newman.push(`${relativa(archivo)} (${cuantos(pedidos.length, 'pedido')})`);
  for (const { nombre, resultado } of pedidos) {
    const pedido = nombre.split(' / ').pop();
    const caso = { id: pedido, resultado, fuente: 'Newman', evidencia: `${COLECCION} › ${nombre}` };
    agregarSinCp(caso, CRITERIOS_DE_NEWMAN[pedido], pedidosUsados, pedido);
  }
}

// Playwright. Reporte html: index.html lleva sus datos en un zip en base64; su report.json tiene cada prueba con
// el título, la ubicación y el resultado (outcome). Se lee con zlib, sin dependencias. Reporter json: suites
// anidadas con specs[] y el status de cada uno en tests[].
function leerReporteHtml(archivo) {
  const base64 = fs.readFileSync(archivo, 'utf8')
    .match(/<template id="playwrightReportBase64">data:application\/zip;base64,([^<]+)</)?.[1];
  if (!base64) throw new Error(`${archivo} no es un reporte html de Playwright`);
  const zip = Buffer.from(base64, 'base64');
  // El registro final del zip (firma PK 5 6) dice cuántas entradas hay y dónde empieza el directorio central.
  const fin = zip.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  let p = zip.readUInt32LE(fin + 16);
  for (let i = 0; i < zip.readUInt16LE(fin + 10); i++) {
    const largoDelNombre = zip.readUInt16LE(p + 28);
    if (zip.toString('utf8', p + 46, p + 46 + largoDelNombre) === 'report.json') {
      const local = zip.readUInt32LE(p + 42);
      const inicio = local + 30 + zip.readUInt16LE(local + 26) + zip.readUInt16LE(local + 28);
      const datos = zip.subarray(inicio, inicio + zip.readUInt32LE(p + 20));
      return JSON.parse((zip.readUInt16LE(p + 10) === 8 ? zlib.inflateRawSync(datos) : datos).toString('utf8'));
    }
    p += 46 + largoDelNombre + zip.readUInt16LE(p + 30) + zip.readUInt16LE(p + 32);
  }
  throw new Error(`${archivo} no trae report.json`);
}
const RESULTADO_DE_PLAYWRIGHT = { expected: 'pasa', flaky: 'pasa (en el reintento)', unexpected: 'falla', skipped: 'omitido' };
function pruebasDePlaywright(ruta) {
  const archivo = fs.statSync(ruta).isDirectory() ? path.join(ruta, 'index.html') : ruta;
  if (archivo.endsWith('.html')) {
    return leerReporteHtml(archivo).files.flatMap((f) => f.tests.map((t) => ({
      spec: t.location.file, linea: t.location.line, titulo: t.title,
      resultado: RESULTADO_DE_PLAYWRIGHT[t.outcome] ?? 'falla',
    })));
  }
  const specs = (suite) => [...(suite.specs ?? []), ...(suite.suites ?? []).flatMap(specs)];
  return JSON.parse(fs.readFileSync(archivo, 'utf8')).suites.flatMap(specs).map((s) => ({
    spec: s.file, linea: s.line, titulo: s.title,
    resultado: s.tests.map((t) => RESULTADO_DE_PLAYWRIGHT[t.status] ?? 'falla').reduce(peor, 'omitido'),
  }));
}
const pruebasUsadas = new Set();
for (const ruta of valores('--playwright')) {
  const pruebas = pruebasDePlaywright(ruta);
  reportes.Playwright.push(`${relativa(ruta)} (${cuantos(pruebas.length, 'prueba')})`);
  for (const { spec, linea, titulo, resultado } of pruebas) {
    const fila = CRITERIOS_DE_PLAYWRIGHT.find(([s, comienzo]) => s === spec && titulo.startsWith(comienzo));
    const caso = { id: titulo, resultado, fuente: 'Playwright', evidencia: `${SPECS}/${spec}:${linea}` };
    agregarSinCp(caso, fila?.[2], pruebasUsadas, fila && `${fila[0]} · ${fila[1]}`);
  }
}

const faltantes = (fuente, tabla, lista) => valores(`--${fuente.toLowerCase()}`).length > 0 && lista.length > 0
  && console.warn(`${fuente}: ${cuantos(lista.length, 'fila')} de ${tabla} que no trae ningún reporte: ${lista.join('; ')}`);
faltantes('Newman', 'CRITERIOS_DE_NEWMAN', Object.keys(CRITERIOS_DE_NEWMAN).filter((p) => !pedidosUsados.has(p)));
faltantes('Playwright', 'CRITERIOS_DE_PLAYWRIGHT', CRITERIOS_DE_PLAYWRIGHT
  .filter(([s, c]) => !pruebasUsadas.has(`${s} · ${c}`)).map(([s, c]) => `${s} "${c}…"`));

// ------------------------------------------------------------------- defectos
const registro = fs.readFileSync(DEFECTOS, 'utf8');
const detalles = new Map(); // DEF-xx → su sección "### DEF-xx · ...", hasta el título siguiente
for (const seccion of registro.split(/^(?=#{1,3} )/m)) {
  const id = seccion.match(/^### (DEF-\d+) /)?.[1];
  if (id) detalles.set(id, (detalles.get(id) ?? '') + seccion);
}
function criteriosQueNombra(textoDelDefecto) {
  const cas = new Set();
  for (const [, hu, desde, hasta] of textoDelDefecto.matchAll(/CA-(\d+)\.(\d+) a CA-\1\.(\d+)/g)) {
    for (let n = Number(desde); n <= Number(hasta); n++) cas.add(`CA-${hu}.${n}`);
  }
  for (const [ca] of textoDelDefecto.matchAll(/CA-\d+\.\d+/g)) cas.add(ca);
  for (const [cp] of textoDelDefecto.matchAll(CP)) cas.add(criterioDelCp(cp));
  return [...cas].filter((ca) => porId.has(ca));
}
let vinculados = 0;
const filasDelResumen = [...registro.matchAll(/^\| (DEF-\d+) \|(.+)\|$/gm)];
for (const [fila, id, resto] of filasDelResumen) {
  // Defecto | Severidad | Prioridad | Estado | Dueño | Fecha | Corrección. Del estado queda lo de antes del paréntesis.
  const celdas = resto.split('|').map((s) => s.trim());
  const etiqueta = `${id} (${celdas[1]} · ${celdas[3].replace(/\s*\(.*$/, '')})`;
  const textoDelDefecto = `${fila}\n${detalles.get(id) ?? ''}`;
  const cas = criteriosQueNombra(textoDelDefecto);
  const hus = cas.length > 0 ? [] : [...new Set(textoDelDefecto.match(/HU-\d+/g) ?? [])];
  for (const ca of cas) porId.get(ca).defectos.push(etiqueta);
  for (const hu of hus) {
    for (const c of criterios.filter((x) => x.hu === hu)) c.defectos.push(etiqueta.replace(/\)$/, ` · por ${hu})`));
  }
  if (cas.length > 0 || criterios.some((c) => hus.includes(c.hu))) vinculados++;
}

// ------------------------------------------------------------------- matriz
const estado = (c) => {
  if (c.casos.length === 0) return 'SIN CASO';
  if (c.casos.some((k) => k.resultado.startsWith('falla'))) return 'FALLA';
  if (c.casos.every((k) => k.resultado === 'omitido')) return 'OMITIDO';
  return 'PASA';
};
const celda = (s) => s.replace(/\|/g, '\\|');
const numerada = (lista) => lista.map((s, i) => `${i + 1}. ${celda(s)}`).join('<br>');
const lineas = [
  '# Matriz de trazabilidad',
  '',
  `Generada por \`scripts/qa/matriz.mjs\` el ${new Date().toISOString().slice(0, 16).replace('T', ' ')} UTC con estos reportes:`,
  '',
  ...Object.entries(reportes).map(([fuente, lista]) => `- ${fuente}: ${lista.length > 0 ? lista.join(', ') : 'ninguno'}.`),
  '',
  'Cada caso tiene su evidencia con el mismo número. Los defectos son los del registro '
    + '(`docs/testing/registro-de-defectos.md`) que nombran el criterio, o la historia si no nombran ningún criterio.',
  '',
  '| HU | Prioridad | Criterio | Casos | Evidencia | Resultado | Defectos |',
  '|---|---|---|---|---|---|---|',
];
for (const h of historias) {
  for (const c of criterios.filter((x) => x.hu === h.id)) {
    const casos = numerada(c.casos.map((k) => `${k.id} (${k.fuente}: ${k.resultado})`)) || 'Ninguno';
    const evidencia = numerada(c.casos.map((k) => k.evidencia)) || 'Ninguna';
    const defectos = c.defectos.join('<br>') || 'Ninguno';
    lineas.push(`| ${h.id} ${h.nombre} | ${h.prioridad} | ${c.id} ${c.nombre} | ${casos} | ${evidencia} | ${estado(c)} | ${defectos} |`);
  }
}
const cuenta = (e) => criterios.filter((c) => estado(c) === e).length;
const resumen = `${criterios.length} criterios de ${historias.length} historias: ${cuenta('PASA')} pasan, `
  + `${cuenta('FALLA')} fallan, ${cuenta('SIN CASO')} sin caso`
  + `${cuenta('OMITIDO') > 0 ? `, ${cuenta('OMITIDO')} con todos sus casos omitidos` : ''}.`;
const porFuente = Object.keys(reportes)
  .map((f) => `${f} ${criterios.filter((c) => c.casos.some((k) => k.fuente === f)).length}`).join(', ');
const conDefectos = `${vinculados} de los ${filasDelResumen.length} defectos del registro nombran una historia o un `
  + `criterio y quedan en ${criterios.filter((c) => c.defectos.length > 0).length} criterios; los demás no nombran `
  + 'ninguno (seguridad, infraestructura, documentación).';
lineas.push('', resumen, '', `Criterios con casos de cada fuente: ${porFuente}.`, '', conDefectos, '');

if (salida) fs.writeFileSync(salida, lineas.join('\n'));
else console.log(lineas.join('\n'));
console.error(resumen);
console.error(`Criterios con casos de cada fuente: ${porFuente}.`);
process.exit(cuenta('FALLA') + cuenta('SIN CASO') + cuenta('OMITIDO') === 0 ? 0 : 1);
