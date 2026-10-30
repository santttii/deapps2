// Matriz de trazabilidad: historia → criterio → casos → resultado, armada a partir de los reportes.
//
//   node scripts/qa/matriz.mjs [--escenarios salida.txt] [--junit carpeta]... [--newman reporte.json]
//                              [--playwright reporte.json] [--salida matriz.md]
//
// - Las historias y los criterios salen de docs/documentacion/historias-de-usuario.md.
// - JUnit (surefire/failsafe), Newman y Playwright aportan los casos que tienen un ID "CP-xx.y.z" en el nombre
//   (convención del documento de HU): el CP dice a qué criterio pertenece.
// - La salida de scripts/pruebas/escenarios.mjs aporta el resultado de cada escenario; qué criterios cubre cada
//   uno sale de la tabla "Cobertura actual por la prueba de integración" del documento de HU.
//
// Un criterio sin ningún caso queda "SIN CASO". Si hay criterios sin caso o casos que fallan, sale con código 1.
// Solo usa módulos que trae Node.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const HU = path.join(REPO, 'docs', 'documentacion', 'historias-de-usuario.md');

const args = process.argv.slice(2);
const valores = (opcion) => args.flatMap((a, i) => (a === opcion && args[i + 1] ? [args[i + 1]] : []));
const salida = valores('--salida')[0];

// ------------------------------------------------------------------- historias y criterios
const texto = fs.readFileSync(HU, 'utf8');
const historias = [...texto.matchAll(/^## (HU-\d+) (.+?) · (\w+)$/gm)]
  .map(([, id, nombre, prioridad]) => ({ id, nombre, prioridad }));
const criterios = [...texto.matchAll(/Escenario: (CA-(\d+)\.\d+) (.+)$/gm)]
  .map(([, id, hu, nombre]) => ({ id, hu: `HU-${hu}`, nombre: nombre.trim(), casos: [] }));
const porId = new Map(criterios.map((c) => [c.id, c]));

// Tabla "criterios | escenario" del documento.
const tabla = texto.split('## Cobertura actual por la prueba de integración')[1] ?? '';
const escenariosDe = new Map(); // CA → [números de escenario]
for (const [, cas, escenarios] of tabla.matchAll(/^\| (CA-[^|]+) \| ([^|]+) \|$/gm)) {
  const numeros = [...escenarios.matchAll(/\d+/g)].map(Number);
  for (const ca of cas.split(',').map((s) => s.trim())) escenariosDe.set(ca, numeros);
}

// ------------------------------------------------------------------- reportes
const agregar = (cp, resultado, fuente) => {
  const ca = `CA-${cp.split('-')[1].split('.').slice(0, 2).join('.')}`;
  const c = porId.get(ca);
  if (c) c.casos.push({ id: cp, resultado, fuente });
  else console.warn(`${cp} (${fuente}) no corresponde a ningún criterio del documento de HU`);
};
const CP = /CP-\d+\.\d+\.\d+/g;

// escenarios.mjs: líneas del resumen "PASA   12. ..." o "FALLA  12. ...".
for (const archivo of valores('--escenarios')) {
  const resultados = new Map();
  for (const [, estado, n] of fs.readFileSync(archivo, 'utf8').matchAll(/^(PASA|FALLA)\s+(\d+)\. /gm)) {
    resultados.set(Number(n), estado === 'PASA' ? 'pasa' : 'falla');
  }
  for (const [ca, numeros] of escenariosDe) {
    for (const n of numeros) {
      if (!resultados.has(n)) continue;
      porId.get(ca)?.casos.push({ id: `escenario ${n}`, resultado: resultados.get(n), fuente: 'escenarios.mjs' });
    }
  }
}

// JUnit XML: <testcase name="..." classname="..."> con <failure> o <error> adentro si falló.
const xmls = (dir) => (fs.existsSync(dir) ? fs.readdirSync(dir, { recursive: true }) : [])
  .filter((f) => String(f).endsWith('.xml')).map((f) => path.join(dir, String(f)));
for (const dir of valores('--junit')) {
  for (const archivo of xmls(dir)) {
    const xml = fs.readFileSync(archivo, 'utf8');
    for (const [, atributos, cuerpo = ''] of xml.matchAll(/<testcase([^>]*?)(?:\/>|>([\s\S]*?)<\/testcase>)/g)) {
      const resultado = /<(failure|error)\b/.test(cuerpo) ? 'falla' : /<skipped\b/.test(cuerpo) ? 'omitido' : 'pasa';
      for (const [cp] of atributos.matchAll(CP)) agregar(cp, resultado, path.basename(archivo));
    }
  }
}

// Newman (--reporters json): run.executions[].item.name y sus assertions.
for (const archivo of valores('--newman')) {
  const run = JSON.parse(fs.readFileSync(archivo, 'utf8')).run;
  for (const e of run.executions) {
    const resultado = (e.assertions ?? []).some((a) => a.error) ? 'falla' : 'pasa';
    for (const [cp] of e.item.name.matchAll(CP)) agregar(cp, resultado, 'Newman');
  }
}

// Playwright (--reporter=json): suites anidadas con specs[].title y tests[].results[].status.
function specs(suite) {
  return [...(suite.specs ?? []), ...(suite.suites ?? []).flatMap(specs)];
}
for (const archivo of valores('--playwright')) {
  const reporte = JSON.parse(fs.readFileSync(archivo, 'utf8'));
  for (const spec of reporte.suites.flatMap(specs)) {
    const resultado = spec.ok ? 'pasa' : 'falla';
    for (const [cp] of spec.title.matchAll(CP)) agregar(cp, resultado, 'Playwright');
  }
}

// ------------------------------------------------------------------- matriz
const estado = (c) => {
  if (c.casos.length === 0) return 'SIN CASO';
  if (c.casos.some((k) => k.resultado === 'falla')) return 'FALLA';
  return 'PASA';
};
const lineas = [
  '# Matriz de trazabilidad',
  '',
  `Generada por \`scripts/qa/matriz.mjs\` el ${new Date().toISOString().slice(0, 16).replace('T', ' ')} UTC.`,
  '',
  '| HU | Prioridad | Criterio | Casos | Resultado |',
  '|---|---|---|---|---|',
];
for (const h of historias) {
  for (const c of criterios.filter((x) => x.hu === h.id)) {
    const casos = c.casos.map((k) => `${k.id} (${k.fuente}: ${k.resultado})`).join('<br>') || '—';
    lineas.push(`| ${h.id} ${h.nombre} | ${h.prioridad} | ${c.id} ${c.nombre} | ${casos} | ${estado(c)} |`);
  }
}
const cuenta = (e) => criterios.filter((c) => estado(c) === e).length;
const resumen = `${criterios.length} criterios de ${historias.length} historias: ${cuenta('PASA')} pasan, `
  + `${cuenta('FALLA')} fallan, ${cuenta('SIN CASO')} sin caso.`;
lineas.push('', resumen, '');

if (salida) fs.writeFileSync(salida, lineas.join('\n'));
else console.log(lineas.join('\n'));
console.error(resumen);
process.exit(cuenta('FALLA') + cuenta('SIN CASO') === 0 ? 0 : 1);
