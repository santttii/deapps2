// Resumen de las pruebas de mutación del backend a partir de los reportes de PIT (target/pit-reports/mutations.xml
// de cada módulo, que deja "mvn test-compile org.pitest:pitest-maven:mutationCoverage"). Imprime una tabla en
// Markdown, que el CI agrega al resumen de la corrida, y termina con error si no hay ningún reporte o si algún
// módulo queda debajo del mínimo. El mínimo sale de la propiedad mutacion.minimo del pom del backend: es el
// mismo con el que PIT corta el build, y así no hay dos números que mantener.
// Uso: node scripts/qa/mutacion.mjs [carpeta del backend]
import fs from 'node:fs';
import path from 'node:path';

const raiz = path.resolve(process.argv[2] ?? 'backend');

const pom = fs.readFileSync(path.join(raiz, 'pom.xml'), 'utf8');
const minimo = Number(/<mutacion\.minimo>(\d+)<\/mutacion\.minimo>/.exec(pom)?.[1]);
if (!Number.isFinite(minimo)) {
  console.log('No está la propiedad mutacion.minimo en el pom del backend.');
  process.exit(1);
}

// Solo target/pit-reports/mutations.xml: lo que haya en subcarpetas es de una corrida vieja hecha a mano.
function buscarReportes(dir, encontrados = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (!e.isDirectory() || e.name === 'node_modules' || e.name === 'src') continue;
    const ruta = path.join(dir, e.name);
    const reporte = path.join(ruta, 'pit-reports', 'mutations.xml');
    if (e.name === 'target') {
      if (fs.existsSync(reporte)) encontrados.push(reporte);
    } else {
      buscarReportes(ruta, encontrados);
    }
  }
  return encontrados;
}

const pct = (parte, total) => `${((100 * parte) / total).toFixed(1)} %`;
const filas = [];
const debajo = [];
const total = { mutantes: 0, muertos: 0 };

for (const reporte of buscarReportes(raiz).sort()) {
  const modulo = path.relative(raiz, reporte).split(path.sep).slice(0, -3).join('/');
  // Cada mutante es un <mutation detected='true|false' status='KILLED|SURVIVED|NO_COVERAGE|TIMED_OUT|...'>.
  // "detected" es lo que PIT cuenta como muerto: incluye los que hicieron fallar un test y los que lo colgaron.
  const mutantes = [...fs.readFileSync(reporte, 'utf8').matchAll(/<mutation\b([^>]*)>/g)].map(([, atributos]) => ({
    muerto: /detected=['"]true['"]/.test(atributos),
    estado: /status=['"](\w+)['"]/.exec(atributos)?.[1],
  }));
  if (mutantes.length === 0) continue;
  const muertos = mutantes.filter((m) => m.muerto).length;
  const sobrevivieron = mutantes.filter((m) => m.estado === 'SURVIVED').length;
  const sinCobertura = mutantes.filter((m) => m.estado === 'NO_COVERAGE').length;
  // Sin redondear: un 59,6 % no llega al 60.
  const alcanza = 100 * muertos >= minimo * mutantes.length;
  if (!alcanza) debajo.push(modulo);
  const score = pct(muertos, mutantes.length) + (alcanza ? '' : ' (debajo del mínimo)');
  filas.push(`| ${modulo} | ${score} | ${muertos} / ${mutantes.length} | ${sobrevivieron} | ${sinCobertura} |`);
  total.mutantes += mutantes.length;
  total.muertos += muertos;
}

if (filas.length === 0) {
  console.log('No hay reportes de PIT: corré "mvn test-compile org.pitest:pitest-maven:mutationCoverage" en backend/ primero.');
  process.exit(1);
}
console.log('## Pruebas de mutación del backend (PIT)\n');
console.log(`Paquete de dominio de cada servicio. Mínimo por módulo: ${minimo} % de mutantes muertos.\n`);
console.log('| Módulo | Mutantes muertos | Muertos / total | Sobrevivieron | Sin cobertura |');
console.log('|---|---|---|---|---|');
console.log(filas.join('\n'));
console.log(`| **Total** | **${pct(total.muertos, total.mutantes)}** | **${total.muertos} / ${total.mutantes}** | | |`);
if (debajo.length > 0) {
  console.log(`\nDebajo del ${minimo} %: ${debajo.join(', ')}.`);
  process.exit(1);
}
