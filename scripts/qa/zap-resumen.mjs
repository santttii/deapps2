// Resumen de los reportes de OWASP ZAP que deja scripts/qa/zap.sh (un JSON por escaneo) y el criterio de
// seguridad: ninguna alerta High, y cada Medium justificada en .zap/reglas.tsv. Imprime el resumen en
// Markdown (el workflow lo agrega al de la corrida) y termina con código 1 si el criterio no se cumple.
// Uso: node scripts/qa/zap-resumen.mjs [carpeta de los reportes] [archivo de reglas]
import fs from 'node:fs';
import path from 'node:path';

const carpeta = path.resolve(process.argv[2] ?? 'logs/zap');
const archivoDeReglas = process.argv[3] ?? '.zap/reglas.tsv';

// El riskcode de ZAP: 0 informativa, 1 Low, 2 Medium, 3 High.
const RIESGOS = ['Info', 'Low', 'Medium', 'High'];
const HIGH = 3;
const MEDIUM = 2;
const LOW = 1;

/** Las alertas aceptadas: id de la regla, nombre exacto de la alerta y justificación (ver el encabezado del archivo). */
function leerReglas(archivo) {
  const reglas = [];
  fs.readFileSync(archivo, 'utf8').split(/\r?\n/).forEach((linea, i) => {
    if (linea.startsWith('#') || linea.trim() === '') return;
    const [id = '', , nombre = '', justificacion = ''] = linea.split('\t');
    const alerta = nombre.trim().replace(/^\((.*)\)$/, '$1');
    if (!id.trim() || !alerta || !justificacion.trim()) {
      throw new Error(`${archivo}, renglón ${i + 1}: faltan el id, el nombre de la alerta o la justificación`);
    }
    reglas.push({ id: id.trim(), alerta, justificacion: justificacion.trim(), usada: false });
  });
  return reglas;
}

/** Las alertas de un reporte JSON de ZAP (formato "traditional-json"), una por regla y nombre. */
function leerAlertas(archivo) {
  const reporte = JSON.parse(fs.readFileSync(archivo, 'utf8'));
  const alertas = [];
  for (const sitio of reporte.site ?? []) {
    for (const a of sitio.alerts ?? []) {
      alertas.push({
        id: String(a.pluginid),
        nombre: a.name ?? a.alert,
        riesgo: Number(a.riskcode),
        instancias: a.instances?.length ?? Number(a.count ?? 0),
        sitio: sitio['@name'],
      });
    }
  }
  return { version: reporte['@version'], alertas };
}

const celda = (texto) => String(texto).replaceAll('|', '\\|');

// Primero el baseline y después la API, en el orden en que corren; cualquier otro reporte, al final.
const ORDEN = ['baseline.json', 'api.json'];
const lugar = (f) => (ORDEN.includes(f) ? ORDEN.indexOf(f) : ORDEN.length);

const reportes = fs.existsSync(carpeta)
  ? fs.readdirSync(carpeta).filter((f) => f.endsWith('.json')).sort((a, b) => lugar(a) - lugar(b) || a.localeCompare(b))
  : [];
if (reportes.length === 0) {
  console.log('## OWASP ZAP\n\nNo hay reportes JSON de ZAP: el escaneo no llegó a terminar.');
  process.exit(1);
}

const reglas = leerReglas(archivoDeReglas);
const cuenta = { highs: 0, mediums: 0, mediumsSinJustificar: 0, lows: 0, lowsSinJustificar: 0, infos: 0 };
const salida = ['## OWASP ZAP', ''];

for (const nombreDelReporte of reportes) {
  const { version, alertas } = leerAlertas(path.join(carpeta, nombreDelReporte));
  const sitios = [...new Set(alertas.map((a) => a.sitio))].join(', ') || 'sin alertas';
  salida.push(`### ${nombreDelReporte} (ZAP ${version ?? '?'}, ${sitios})`, '');
  if (alertas.length === 0) {
    salida.push('Ninguna alerta.', '');
    continue;
  }
  salida.push('| Riesgo | Alerta | Regla | Instancias | Estado |', '|---|---|---|---|---|');
  alertas.sort((a, b) => b.riesgo - a.riesgo || a.nombre.localeCompare(b.nombre));
  for (const a of alertas) {
    const regla = a.riesgo < HIGH
      ? reglas.find((r) => r.id === a.id && r.alerta.toLowerCase() === a.nombre.toLowerCase())
      : undefined;
    if (regla) regla.usada = true;
    let estado;
    if (a.riesgo >= HIGH) {
      cuenta.highs++;
      estado = '**FALLA: High** (no se acepta nunca)';
    } else if (regla) {
      estado = 'Justificada en .zap/reglas.tsv';
    } else if (a.riesgo === MEDIUM) {
      cuenta.mediumsSinJustificar++;
      estado = '**FALLA: Medium sin justificar**';
    } else if (a.riesgo === LOW) {
      cuenta.lowsSinJustificar++;
      estado = 'Sin justificar (Low: no corta la corrida)';
    } else {
      estado = 'Informativa';
    }
    if (a.riesgo === MEDIUM) cuenta.mediums++;
    if (a.riesgo === LOW) cuenta.lows++;
    if (a.riesgo <= 0) cuenta.infos++;
    salida.push(`| ${RIESGOS[a.riesgo] ?? a.riesgo} | ${celda(a.nombre)} | ${a.id} | ${a.instancias} | ${estado} |`);
  }
  salida.push('');
}

const usadas = reglas.filter((r) => r.usada);
if (usadas.length > 0) {
  salida.push('### Alertas aceptadas y por qué', '');
  for (const r of usadas) salida.push(`- **${r.alerta}** (${r.id}): ${r.justificacion}`);
  salida.push('');
}
const sinUso = reglas.filter((r) => !r.usada);
if (sinUso.length > 0) {
  salida.push(`Reglas de .zap/reglas.tsv sin alerta en esta corrida: ${sinUso.map((r) => `${r.alerta} (${r.id})`).join(', ')}.`, '');
}

const pasa = cuenta.highs === 0 && cuenta.mediumsSinJustificar === 0;
salida.push(
  '### Resultado',
  '',
  `High: ${cuenta.highs}. Medium: ${cuenta.mediums} (${cuenta.mediums - cuenta.mediumsSinJustificar} justificadas). `
    + `Low: ${cuenta.lows} (${cuenta.lowsSinJustificar} sin justificar). Informativas: ${cuenta.infos}.`,
  '',
  pasa
    ? 'Cumple el criterio: ninguna alerta High y todas las Medium justificadas.'
    : 'NO cumple el criterio: hay alertas High o Medium sin justificar (ver la tabla).',
);
console.log(salida.join('\n'));
process.exit(pasa ? 0 : 1);
