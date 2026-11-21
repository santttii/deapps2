// Estabilidad de la suite del backend: compara, test por test, el resultado de varias corridas de "mvn test".
//
//   node scripts/qa/estabilidad.mjs <corrida-1> <corrida-2> [<corrida-3> ...] [--salida resumen.md]
//
// Cada carpeta tiene los reportes de surefire de una corrida: los TEST-*.xml que están en una carpeta
// surefire-reports, a cualquier profundidad. Sirve la copia que deja .github/workflows/estabilidad.yml
// (corridas/corrida-N/servicios/ventas/target/surefire-reports/...) y también la carpeta backend/ recién corrida.
// El módulo de cada test es la ruta que está antes de target/surefire-reports.
//
// El resultado de un test en una corrida es uno de estos:
//   pasa        el <testcase> no tiene <failure>, <error> ni <skipped>
//   falla       tiene <failure>: una aserción que no se cumplió
//   error       tiene <error>: una excepción que el test no esperaba
//   saltado     tiene <skipped>: @Disabled o una suposición (assume) que no se cumplió
//   inestable   tiene <flakyFailure> o <flakyError>: falló y pasó al reintentarlo. Pasa solo si surefire corre
//               con rerunFailingTestsCount; cuenta como cambio aunque se repita, porque en esa corrida dio dos
//               resultados distintos
//   no corrió   el test está en otra corrida y en esta no (por ejemplo, porque el módulo no compiló)
//
// Imprime en Markdown (el workflow lo agrega al resumen de la corrida) los totales de cada corrida, los tests de
// cada módulo y la lista de los que no dan lo mismo en todas. Con --salida escribe además lo mismo en ese archivo.
// Sale con código 1 si algún test cambia entre corridas o es inestable, o si una carpeta no tiene reportes, y con
// código 2 si los argumentos no alcanzan. Solo usa módulos que trae Node.
import fs from 'node:fs';
import path from 'node:path';

const USO = 'Uso: node scripts/qa/estabilidad.mjs <corrida-1> <corrida-2> [<corrida-3> ...] [--salida resumen.md]';
const NO_CORRIO = 'no corrió';
const ESTADOS = ['pasa', 'falla', 'error', 'saltado', 'inestable', NO_CORRIO];

// ------------------------------------------------------------------- argumentos
const args = process.argv.slice(2);
const iSalida = args.indexOf('--salida');
const salida = iSalida === -1 ? undefined : args[iSalida + 1];
const carpetas = iSalida === -1 ? args : args.filter((_, i) => i !== iSalida && i !== iSalida + 1);
if (carpetas.length < 2 || (iSalida !== -1 && !salida)) {
  console.error(USO);
  process.exit(2);
}

// ------------------------------------------------------------------- lectura de los reportes
function buscarReportes(dir, encontrados = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const ruta = path.join(dir, e.name);
    if (e.isDirectory()) buscarReportes(ruta, encontrados);
    else if (e.name.startsWith('TEST-') && e.name.endsWith('.xml') && path.basename(dir) === 'surefire-reports') {
      encontrados.push(ruta);
    }
  }
  return encontrados;
}

const ENTIDADES = { lt: '<', gt: '>', amp: '&', quot: '"', apos: "'" };
const decodificar = (texto) => texto.replaceAll(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (entidad, codigo) => {
  if (codigo.startsWith('#x') || codigo.startsWith('#X')) return String.fromCodePoint(Number.parseInt(codigo.slice(2), 16));
  if (codigo.startsWith('#')) return String.fromCodePoint(Number(codigo.slice(1)));
  return ENTIDADES[codigo] ?? entidad;
});

// Los atributos de una etiqueta de apertura. Surefire los escribe siempre entre comillas dobles.
const atributos = (etiqueta) => Object.fromEntries(
  [...etiqueta.matchAll(/([\w:.-]+)\s*=\s*"([^"]*)"/g)].map(([, nombre, valor]) => [nombre, decodificar(valor)]),
);

// Surefire puede escribir los segundos con separador de miles ("1,234.5").
const segundos = (valor) => Number(String(valor ?? '0').replaceAll(',', '')) || 0;

// Quita las secciones <![CDATA[ ... ]]>, que son la salida de consola de los tests y las trazas de los errores:
// adentro puede haber cualquier texto, incluso "<failure" o "</testcase>", y confundiría la búsqueda de etiquetas.
function sinCdata(xml) {
  const partes = [];
  let desde = 0;
  let inicio = xml.indexOf('<![CDATA[');
  while (inicio !== -1) {
    partes.push(xml.slice(desde, inicio));
    const fin = xml.indexOf(']]>', inicio);
    desde = fin === -1 ? xml.length : fin + 3;
    inicio = xml.indexOf('<![CDATA[', desde);
  }
  partes.push(xml.slice(desde));
  return partes.join('');
}

function estadoDe(cuerpo) {
  if (/<failure\b/.test(cuerpo)) return 'falla';
  if (/<error\b/.test(cuerpo)) return 'error';
  if (/<skipped\b/.test(cuerpo)) return 'saltado';
  if (/<flaky(?:Failure|Error)\b/.test(cuerpo)) return 'inestable';
  return 'pasa';
}

// Una etiqueta <testcase ...> o <testcase .../>. Un valor entre comillas puede tener ">" adentro.
const TESTCASE = /<testcase\b(?:"[^"]*"|'[^']*'|[^'">])*>/g;

// Lee una corrida: devuelve los tests (clave → { modulo, test, estado }) y los segundos que surefire midió.
function leerCorrida(carpeta) {
  const tests = new Map();
  let tiempo = 0;
  // Lo que no existe o no es una carpeta (un archivo pasado por error) cuenta como una corrida sin reportes.
  const esCarpeta = fs.statSync(carpeta, { throwIfNoEntry: false })?.isDirectory() ?? false;
  const reportes = esCarpeta ? buscarReportes(carpeta) : [];
  reportes.sort((a, b) => a.localeCompare(b));
  for (const archivo of reportes) {
    const partes = path.relative(carpeta, path.dirname(archivo)).split(path.sep);
    const modulo = partes.slice(0, partes.at(-2) === 'target' ? -2 : -1).join('/') || '.';
    const xml = sinCdata(fs.readFileSync(archivo, 'utf8'));
    tiempo += segundos(atributos(xml.match(/<testsuite\b[^>]*>/)?.[0] ?? '').time);
    for (const coincidencia of xml.matchAll(TESTCASE)) {
      const etiqueta = coincidencia[0];
      const finEtiqueta = coincidencia.index + etiqueta.length;
      const cierre = etiqueta.endsWith('/>') ? finEtiqueta : xml.indexOf('</testcase>', finEtiqueta);
      const cuerpo = xml.slice(finEtiqueta, cierre === -1 ? xml.length : cierre);
      const { name = '', classname = '' } = atributos(etiqueta);
      const test = `${classname}#${name}`;
      // Si el mismo nombre aparece dos veces en la corrida, la segunda cuenta como otro test.
      let clave = `${modulo} ${test}`;
      let repeticion = 1;
      while (tests.has(clave)) {
        repeticion += 1;
        clave = `${modulo} ${test} (${repeticion})`;
      }
      tests.set(clave, { modulo, test: clave.slice(modulo.length + 1), estado: estadoDe(cuerpo) });
    }
  }
  return { carpeta, reportes: reportes.length, tests, tiempo };
}

// ------------------------------------------------------------------- comparación
const corridas = carpetas.map(leerCorrida);
// Una carpeta sin reportes se informa aparte y no entra en la comparación: si no, todos los tests de las demás
// saldrían como "no corrió" y taparían los que cambian de verdad.
const vacias = corridas.filter((c) => c.reportes === 0);

const claves = [...new Set(corridas.flatMap((c) => [...c.tests.keys()]))].sort((a, b) => a.localeCompare(b));
const filas = claves.map((clave) => {
  const conDatos = corridas.map((c) => c.tests.get(clave)).find(Boolean);
  const estados = corridas.map((c) => c.tests.get(clave)?.estado ?? NO_CORRIO);
  const comparables = estados.filter((_, i) => corridas[i].reportes > 0);
  const cambia = new Set(comparables).size > 1 || comparables.includes('inestable');
  return { modulo: conDatos.modulo, test: conDatos.test, estados, cambia };
});
const cambian = filas.filter((f) => f.cambia);

// ------------------------------------------------------------------- salida en Markdown
const celda = (texto) => String(texto).replaceAll('|', String.raw`\|`);
const lineas = [];
const escribir = (...texto) => lineas.push(...texto);

escribir(
  `## Estabilidad de la suite del backend (${corridas.length} corridas)`,
  '',
  `Cada test se compara con su resultado en las demás corridas: ${claves.length} tests distintos en total.`,
  '',
  '| Corrida | Carpeta | Tests | Pasan | Fallan | Con error | Saltados | Inestables | No corrieron | Tiempo en los tests |',
  '|---|---|---:|---:|---:|---:|---:|---:|---:|---:|',
);
corridas.forEach((c, i) => {
  const cuenta = Object.fromEntries(ESTADOS.map((e) => [e, 0]));
  for (const f of filas) cuenta[f.estados[i]]++;
  escribir(
    `| ${i + 1} | ${celda(c.carpeta)} | ${c.tests.size} | ${cuenta.pasa} | ${cuenta.falla} | ${cuenta.error} | `
      + `${cuenta.saltado} | ${cuenta.inestable} | ${cuenta[NO_CORRIO]} | ${c.tiempo.toFixed(1)} s |`,
  );
});

escribir('', '| Módulo | Tests | Cambian entre corridas |', '|---|---:|---:|');
const modulos = [...new Set(filas.map((f) => f.modulo))].sort((a, b) => a.localeCompare(b));
for (const modulo of modulos) {
  const delModulo = filas.filter((f) => f.modulo === modulo);
  escribir(`| ${celda(modulo)} | ${delModulo.length} | ${delModulo.filter((f) => f.cambia).length} |`);
}

escribir('', '### Tests que cambian entre corridas', '');
const conReportes = corridas.length - vacias.length;
if (conReportes === 0) {
  escribir('Ninguna carpeta tiene reportes: no hay nada que comparar.');
} else if (cambian.length === 0) {
  // Si alguna carpeta vino vacía, la frase cuenta solo las corridas que se compararon de verdad.
  let enCuales = `las ${corridas.length} corridas`;
  if (vacias.length > 0 && conReportes === 1) enCuales = 'la única corrida que tiene reportes';
  else if (vacias.length > 0) enCuales = `las ${conReportes} corridas que tienen reportes`;
  escribir(`Ninguno: los ${claves.length} tests dan el mismo resultado en ${enCuales}.`);
} else {
  escribir(
    `| Módulo | Test | ${corridas.map((_, i) => `Corrida ${i + 1}`).join(' | ')} |`,
    `|---|---|${corridas.map(() => '---').join('|')}|`,
    ...cambian.map((f) => `| ${celda(f.modulo)} | ${celda(f.test)} | ${f.estados.join(' | ')} |`),
  );
}
for (const c of vacias) {
  escribir('', `La carpeta ${c.carpeta} no existe o no tiene reportes de surefire (TEST-*.xml en surefire-reports).`);
}

const estable = cambian.length === 0 && vacias.length === 0;
const motivos = [
  cambian.length > 0 && `${cambian.length} ${cambian.length === 1 ? 'test cambia' : 'tests cambian'} entre corridas`,
  vacias.length > 0 && `${vacias.length} ${vacias.length === 1 ? 'carpeta no tiene' : 'carpetas no tienen'} reportes`,
].filter(Boolean);
let veredicto = '**Resultado: estable.** Todas las corridas dan idéntico resultado.';
if (!estable) veredicto = `**Resultado: ${cambian.length > 0 ? 'inestable' : 'incompleto'}.** ${motivos.join(' y ')}.`;
escribir('', veredicto);

const texto = `${lineas.join('\n')}\n`;
process.stdout.write(texto);
if (salida) {
  fs.mkdirSync(path.dirname(path.resolve(salida)), { recursive: true });
  fs.writeFileSync(salida, texto);
}
// Con exitCode y no con exit(): así Node termina de escribir la salida aunque vaya a un pipe.
process.exitCode = estable ? 0 : 1;
