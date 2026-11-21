// Resumen del análisis estático del backend a partir de los reportes de PMD (target/pmd.xml de cada
// módulo, que deja "mvn verify"). Imprime en Markdown los hallazgos por módulo y por prioridad, y los de
// mayor prioridad con archivo y línea: el CI lo agrega al resumen de la corrida.
// Uso: node scripts/qa/analisis-estatico.mjs [carpeta del backend]
import fs from 'node:fs';
import path from 'node:path';

const raiz = path.resolve(process.argv[2] ?? 'backend');
const MAS_IMPORTANTES = 15;

function buscarReportes(dir, encontrados = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.name === 'node_modules' || e.name === 'src') continue;
    const ruta = path.join(dir, e.name);
    if (e.isDirectory()) buscarReportes(ruta, encontrados);
    else if (e.name === 'pmd.xml' && path.basename(dir) === 'target') encontrados.push(ruta);
  }
  return encontrados;
}

const texto = (s) =>
  s.trim().replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'")
    .replace(/&amp;/g, '&').replace(/\|/g, '\\|').replace(/\s+/g, ' ');

const reportes = buscarReportes(raiz).sort((a, b) => a.localeCompare(b));
if (reportes.length === 0) {
  // Sin reportes, PMD no corrió: se falla para que el CI no muestre en verde un análisis que no se hizo.
  console.log('No hay reportes de PMD: corré "mvn verify" en backend/ primero.');
  process.exit(1);
}

const filas = [];
const hallazgos = [];
const total = [0, 0, 0, 0, 0];
for (const xml of reportes) {
  const modulo = path.relative(raiz, path.dirname(path.dirname(xml))).split(path.sep).join('/');
  const porPrioridad = [0, 0, 0, 0, 0];
  const contenido = fs.readFileSync(xml, 'utf8');
  for (const [, archivo, violaciones] of contenido.matchAll(/<file name="([^"]+)">([\s\S]*?)<\/file>/g)) {
    // La ruta desde el paquete (com/palco/...) alcanza para ubicar el archivo dentro del módulo.
    const relativo = archivo.split(/[\\/]/).join('/').replace(/^.*\/src\/main\/java\//, '');
    for (const [, atributos, mensaje] of violaciones.matchAll(/<violation ([^>]+)>([\s\S]*?)<\/violation>/g)) {
      const valor = (nombre) => atributos.match(new RegExp(`\\b${nombre}="([^"]*)"`))?.[1] ?? '';
      const prioridad = Number(valor('priority'));
      porPrioridad[prioridad - 1]++;
      total[prioridad - 1]++;
      hallazgos.push({ prioridad, modulo, archivo: relativo, linea: Number(valor('beginline')), regla: valor('rule'), mensaje: texto(mensaje) });
    }
  }
  const suma = porPrioridad.reduce((a, b) => a + b, 0);
  filas.push(`| ${modulo} | ${porPrioridad.join(' | ')} | ${suma} |`);
}

console.log('## Análisis estático del backend (PMD)\n');
console.log('Reglas: `rulesets/java/quickstart.xml` de PMD, sobre el código de `src/main/java`. Prioridad 1 es la más');
console.log('alta y 5 la más baja. Los hallazgos no rompen el build; sí lo rompe que PMD no pueda analizar un archivo.\n');
console.log('| Módulo | P1 | P2 | P3 | P4 | P5 | Total |');
console.log('|---|---|---|---|---|---|---|');
console.log(filas.join('\n'));
console.log(`| **Total** | ${total.map((n) => `**${n}**`).join(' | ')} | **${total.reduce((a, b) => a + b, 0)}** |`);

if (hallazgos.length > 0) {
  // Por regla: casi siempre unas pocas reglas juntan la mayoría de los hallazgos, y es por donde conviene empezar.
  const porRegla = new Map();
  for (const { prioridad, regla } of hallazgos) {
    const clave = `${prioridad} ${regla}`;
    porRegla.set(clave, { prioridad, regla, cantidad: (porRegla.get(clave)?.cantidad ?? 0) + 1 });
  }
  console.log('\n### Por regla\n');
  console.log('| P | Regla | Hallazgos |');
  console.log('|---|---|---|');
  for (const r of [...porRegla.values()].sort((a, b) => a.prioridad - b.prioridad || b.cantidad - a.cantidad)) {
    console.log(`| ${r.prioridad} | ${r.regla} | ${r.cantidad} |`);
  }

  hallazgos.sort((a, b) => a.prioridad - b.prioridad || a.modulo.localeCompare(b.modulo)
    || a.archivo.localeCompare(b.archivo) || a.linea - b.linea);
  console.log(`\n### Los ${Math.min(MAS_IMPORTANTES, hallazgos.length)} de mayor prioridad\n`);
  console.log('| P | Módulo | Archivo:línea | Regla | Detalle |');
  console.log('|---|---|---|---|---|');
  for (const h of hallazgos.slice(0, MAS_IMPORTANTES)) {
    console.log(`| ${h.prioridad} | ${h.modulo} | \`${h.archivo}:${h.linea}\` | ${h.regla} | ${h.mensaje} |`);
  }
}
