// Las cuentas compradoras de la prueba de carga (palco.js): una por usuario virtual, nuevas y con el email
// verificado, así cada usuario virtual compra con su propia cuenta y no choca con el tope de 6 entradas por
// persona y evento. Se crean antes de la prueba y no durante, porque el registro cuesta un hash de contraseña y
// no es lo que se mide. Como en scripts/pruebas y en las pruebas E2E, el código de verificación sale del
// server.log: sin SMTP, el servidor escribe ahí el email, y con PALCO_CORREO_LOG_COMPLETO=true (como lo dejan
// dev.sh y el docker compose) lo escribe entero, con el destinatario y el código.
//
//   node scripts/carga/cuentas.mjs [cantidad]        50 por defecto
//
// Escribe scripts/carga/resultados/cuentas.json (que git ignora) con el email, el nombre y el DNI de cada una.
// La contraseña es la de las cuentas de prueba. Usa las variables de scripts/pruebas/lib.mjs: PALCO_URL, la
// dirección del backend (http://localhost:8080), y PALCO_LOG, la ruta del server.log.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { BASE, LOG, dniAlAzar, hasta, http, logDesde, marcaDelLog, uuid } from '../pruebas/lib.mjs';

const CANTIDAD = Number(process.argv[2] ?? 50);
const DE_A = 10; // registros a la vez: cada uno cuesta un hash de contraseña en el servidor
const PASSWORD = 'palco1234';
const SALIDA = path.join(path.dirname(fileURLToPath(import.meta.url)), 'resultados', 'cuentas.json');

if (!Number.isInteger(CANTIDAD) || CANTIDAD < 1) {
  console.error('Uso: node scripts/carga/cuentas.mjs [cantidad]');
  process.exit(2);
}
if (!fs.existsSync(LOG)) {
  console.error(`No está el log del servidor en ${LOG}. Definí PALCO_LOG con la ruta del server.log del backend.`);
  process.exit(1);
}

/** Registra una cuenta y la verifica con el código que el servidor dejó en el log. */
async function cuentaNueva(numero, marca) {
  // El número va en el email y no en el apellido: el registro acepta solo letras en el nombre y el apellido.
  const email = `carga.${uuid().slice(0, 8)}.${numero}@palco.test`;
  const datos = { nombre: 'Usuario', apellido: 'Carga', email, fechaNacimiento: '1995-05-17', password: PASSWORD };
  for (let intento = 1; ; intento++) {
    const dni = dniAlAzar();
    const registro = await http('POST', '/api/usuarios/registro', { cuerpo: { ...datos, dni } });
    // El DNI sale al azar: si justo ya tiene cuenta, se prueba con otro.
    if (registro.estado === 400 && registro.texto.includes('DNI') && intento < 3) continue;
    if (registro.estado !== 201) throw new Error(`registro de ${email}: ${registro.estado} ${registro.texto}`);

    const linea = await hasta(() => logDesde(marca).find((l) => l.includes(`Email para ${email} `) && /el email es \d{6}\./.test(l)));
    if (!linea) {
      throw new Error(`el código de verificación de ${email} no apareció en ${LOG}`
        + ' (el backend tiene que correr con PALCO_CORREO_LOG_COMPLETO=true)');
    }
    const codigo = linea.match(/el email es (\d{6})\./)[1];
    const verificacion = await http('POST', '/api/usuarios/verificacion', { cuerpo: { email, codigo } });
    if (verificacion.estado !== 200) throw new Error(`verificación de ${email}: ${verificacion.estado} ${verificacion.texto}`);
    return { email, nombre: `${datos.nombre} ${datos.apellido}`, dni };
  }
}

const inicio = Date.now();
const marca = marcaDelLog();
const cuentas = [];
for (let desde = 1; desde <= CANTIDAD; desde += DE_A) {
  const numeros = Array.from({ length: Math.min(DE_A, CANTIDAD - desde + 1) }, (_, i) => desde + i);
  cuentas.push(...(await Promise.all(numeros.map((n) => cuentaNueva(n, marca)))));
  console.log(`${cuentas.length} de ${CANTIDAD} cuentas creadas en ${BASE}`);
}

fs.mkdirSync(path.dirname(SALIDA), { recursive: true });
fs.writeFileSync(SALIDA, `${JSON.stringify(cuentas, null, 2)}\n`);
console.log(`Listo en ${Math.round((Date.now() - inicio) / 1000)} s: ${path.relative(process.cwd(), SALIDA)}`);
