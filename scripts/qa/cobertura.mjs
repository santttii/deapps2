// Resumen de cobertura del backend a partir de los reportes de JaCoCo (target/site/jacoco/jacoco.csv
// de cada módulo, que deja "mvn verify"). Imprime una tabla en Markdown: el CI la agrega al resumen
// de la corrida. Uso: node scripts/qa/cobertura.mjs [carpeta del backend]
import fs from 'node:fs';
import path from 'node:path';

const raiz = path.resolve(process.argv[2] ?? 'backend');

function buscarCsv(dir, encontrados = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.name === 'node_modules' || e.name === 'src') continue;
    const ruta = path.join(dir, e.name);
    if (e.isDirectory()) buscarCsv(ruta, encontrados);
    else if (e.name === 'jacoco.csv' && ruta.includes(`${path.sep}site${path.sep}jacoco`)) encontrados.push(ruta);
  }
  return encontrados;
}

const pct = (cubiertas, total) => (total === 0 ? '—' : `${((100 * cubiertas) / total).toFixed(1)} %`);
const filas = [];
const total = { li: 0, lc: 0, ri: 0, rc: 0 };

for (const csv of buscarCsv(raiz).sort()) {
  const modulo = path.relative(raiz, csv).split(path.sep).slice(0, -4).join('/');
  let li = 0, lc = 0, ri = 0, rc = 0;
  for (const linea of fs.readFileSync(csv, 'utf8').trim().split('\n').slice(1)) {
    const c = linea.split(',');
    // GROUP,PACKAGE,CLASS,INSTR_MISSED,INSTR_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,...
    rc += +c[6]; ri += +c[5] + +c[6]; lc += +c[8]; li += +c[7] + +c[8];
  }
  filas.push(`| ${modulo} | ${pct(lc, li)} | ${pct(rc, ri)} | ${lc} / ${li} |`);
  total.li += li; total.lc += lc; total.ri += ri; total.rc += rc;
}

if (filas.length === 0) {
  console.log('No hay reportes de JaCoCo: corré "mvn verify" en backend/ primero.');
  process.exit(1);
}
console.log('## Cobertura del backend (JaCoCo)\n');
console.log('| Módulo | Líneas | Ramas | Líneas cubiertas |');
console.log('|---|---|---|---|');
console.log(filas.join('\n'));
console.log(`| **Total** | **${pct(total.lc, total.li)}** | **${pct(total.rc, total.ri)}** | **${total.lc} / ${total.li}** |`);
