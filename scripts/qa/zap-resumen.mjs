// Resumen de los reportes de OWASP ZAP que deja scripts/qa/zap.sh (un JSON por escaneo) y el criterio de
// seguridad: ninguna alerta High, y cada Medium justificada en .zap/reglas.tsv. Imprime el resumen en
// Markdown (el workflow lo agrega al de la corrida) y termina con código 1 si el criterio no se cumple.
//
// Una High no se acepta por justificación. Lo único que la descuenta es un falso positivo verificado,
// anotado en .zap/reglas.tsv instancia por instancia: misma regla, mismo nombre de alerta, mismo método,
// misma URL y mismo parámetro. Si la alerta tiene alguna otra instancia, la corrida falla igual. Los
// falsos positivos salen en su propia sección del resumen, con su justificación.
//
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
const FALSO_POSITIVO = 'FALSO_POSITIVO';

/**
 * Los renglones de .zap/reglas.tsv (ver el encabezado del archivo): las alertas aceptadas (id, nombre
 * exacto y justificación) y los falsos positivos verificados (id, nombre, método y URL, parámetro y
 * justificación).
 */
function leerReglas(archivo) {
  const aceptadas = [];
  const falsosPositivos = [];
  fs.readFileSync(archivo, 'utf8').split(/\r?\n/).forEach((linea, i) => {
    if (linea.startsWith('#') || linea.trim() === '') return;
    const columnas = linea.split('\t').map((c) => c.trim());
    const [id = '', nivel = '', nombre = ''] = columnas;
    const alerta = nombre.replace(/^\((.*)\)$/, '$1');
    const renglon = `${archivo}, renglón ${i + 1}`;
    if (nivel === FALSO_POSITIVO) {
      const [, , , metodoYUrl = '', parametro = '', justificacion = ''] = columnas;
      const [metodo = '', url = ''] = metodoYUrl.split(/ +/);
      if (!id || !alerta || !metodo || !url || !parametro || !justificacion) {
        throw new Error(`${renglon}: un falso positivo lleva el id, el nombre de la alerta, el método y la URL, `
          + 'el parámetro y la justificación');
      }
      falsosPositivos.push({ id, alerta, metodo: metodo.toUpperCase(), url, parametro, justificacion, instancias: 0 });
      return;
    }
    const justificacion = columnas[3] ?? '';
    if (!id || !alerta || !justificacion) {
      throw new Error(`${renglon}: faltan el id, el nombre de la alerta o la justificación`);
    }
    aceptadas.push({ id, alerta, justificacion, usada: false });
  });
  return { aceptadas, falsosPositivos };
}

/** Las alertas de un reporte JSON de ZAP (formato "traditional-json"), una por regla y nombre, con sus instancias. */
function leerAlertas(archivo) {
  const reporte = JSON.parse(fs.readFileSync(archivo, 'utf8'));
  const alertas = [];
  for (const sitio of reporte.site ?? []) {
    for (const a of sitio.alerts ?? []) {
      const instancias = (a.instances ?? []).map((i) => ({
        metodo: String(i.method ?? '').toUpperCase(),
        url: String(i.uri ?? ''),
        parametro: String(i.param ?? ''),
      }));
      alertas.push({
        id: String(a.pluginid),
        nombre: a.name ?? a.alert,
        riesgo: Number(a.riskcode),
        // Si el reporte dice que hay más instancias de las que lista, las que no lista no se pueden
        // comparar con los falsos positivos: cuentan como no verificadas.
        total: Math.max(instancias.length, Number(a.count ?? 0) || 0),
        instancias,
        sitio: sitio['@name'],
      });
    }
  }
  return { version: reporte['@version'], alertas };
}

const mismoNombre = (a, b) => a.toLowerCase() === b.toLowerCase();

/** Cuántas instancias de la alerta son falsos positivos verificados, con el renglón exacto de cada una. */
function instanciasVerificadas(alerta, falsosPositivos) {
  const candidatos = falsosPositivos.filter((f) => f.id === alerta.id && mismoNombre(f.alerta, alerta.nombre));
  let verificadas = 0;
  for (const instancia of alerta.instancias) {
    const fp = candidatos.find((f) => f.metodo === instancia.metodo && f.url === instancia.url
      && f.parametro === instancia.parametro);
    if (fp) {
      fp.instancias++;
      verificadas++;
    }
  }
  return verificadas;
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

const { aceptadas, falsosPositivos } = leerReglas(archivoDeReglas);
const cuenta = {
  highs: 0, mediums: 0, mediumsSinJustificar: 0, lows: 0, lowsSinJustificar: 0, infos: 0, falsosPositivos: 0,
};
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
    const verificadas = instanciasVerificadas(a, falsosPositivos);
    const restantes = a.total - verificadas;
    cuenta.falsosPositivos += verificadas;
    let instancias = String(a.total);
    if (verificadas > 0) instancias += ` (${verificadas} falso${verificadas === 1 ? '' : 's'} positivo${verificadas === 1 ? '' : 's'})`;

    let estado;
    if (restantes === 0 && verificadas > 0) {
      // Todas sus instancias son falsos positivos verificados: no es una alerta de este nivel.
      estado = 'Falso positivo verificado (ver abajo)';
    } else {
      const regla = a.riesgo < HIGH
        ? aceptadas.find((r) => r.id === a.id && mismoNombre(r.alerta, a.nombre))
        : undefined;
      if (regla) regla.usada = true;
      if (a.riesgo >= HIGH) {
        cuenta.highs++;
        estado = verificadas > 0
          ? `**FALLA: High** (${restantes} de ${a.total} instancias no son falsos positivos verificados)`
          : '**FALLA: High** (no se acepta nunca)';
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
    }
    salida.push(`| ${RIESGOS[a.riesgo] ?? a.riesgo} | ${celda(a.nombre)} | ${a.id} | ${instancias} | ${estado} |`);
  }
  salida.push('');
}

const usadas = aceptadas.filter((r) => r.usada);
if (usadas.length > 0) {
  salida.push('### Alertas aceptadas y por qué', '');
  for (const r of usadas) salida.push(`- **${r.alerta}** (${r.id}): ${r.justificacion}`);
  salida.push('');
}
const sinUso = aceptadas.filter((r) => !r.usada);
if (sinUso.length > 0) {
  salida.push(`Reglas de .zap/reglas.tsv sin alerta en esta corrida: ${sinUso.map((r) => `${r.alerta} (${r.id})`).join(', ')}.`, '');
}

const falsosUsados = falsosPositivos.filter((f) => f.instancias > 0);
if (falsosUsados.length > 0) {
  salida.push(
    '### Falsos positivos verificados',
    '',
    'Instancias que ZAP marcó y que se comprobó que no son una vulnerabilidad. Cada una está anotada en '
      + '.zap/reglas.tsv con su regla, método, URL y parámetro exactos; cualquier otra instancia de la misma '
      + 'alerta cuenta con su riesgo y corta la corrida si es High. Cómo se comprobó cada una está en el '
      + 'encabezado de ese archivo.',
    '',
  );
  for (const f of falsosUsados) {
    salida.push(`- **${f.alerta}** (${f.id}) en ${f.metodo} ${f.url}, parámetro \`${f.parametro}\`: ${f.justificacion}`);
  }
  salida.push('');
}
const falsosSinUso = falsosPositivos.filter((f) => f.instancias === 0);
if (falsosSinUso.length > 0) {
  salida.push(`Falsos positivos de .zap/reglas.tsv que no aparecieron en esta corrida: ${
    falsosSinUso.map((f) => `${f.alerta} (${f.id}) en ${f.metodo} ${f.url}, parámetro ${f.parametro}`).join('; ')}.`, '');
}

const pasa = cuenta.highs === 0 && cuenta.mediumsSinJustificar === 0;
salida.push(
  '### Resultado',
  '',
  `High: ${cuenta.highs}. Medium: ${cuenta.mediums} (${cuenta.mediums - cuenta.mediumsSinJustificar} justificadas). `
    + `Low: ${cuenta.lows} (${cuenta.lowsSinJustificar} sin justificar). Informativas: ${cuenta.infos}. `
    + `Instancias descontadas como falsos positivos verificados: ${cuenta.falsosPositivos}.`,
  '',
  pasa
    ? 'Cumple el criterio: ninguna alerta High y todas las Medium justificadas.'
    : 'NO cumple el criterio: hay alertas High o Medium sin justificar (ver la tabla).',
);
console.log(salida.join('\n'));
process.exit(pasa ? 0 : 1);
