// Prueba en vivo: N pagos de N ventas distintas que entran al servidor en el mismo instante.
//
// Avisa si el cobro vuelve a esperar a Pagos con una conexión de la base tomada. Todos los servicios
// comparten un pool de 10 conexiones: si cada pago retuviera una mientras espera, con más de 10 pagos a
// la vez Pagos no tendría con qué contestar, los pagos darían 504 a los 10 segundos y la base quedaría
// congelada para todos los servicios. Los tests con Mockito no lo ven: hace falta el servidor.
//
// Cómo logra el "mismo instante": abre una conexión por pago, manda todo el pedido menos el último byte
// y, con todas listas, manda ese byte en todas. Mientras tanto consulta GET /api/eventos, que es otro
// servicio sobre la misma base, para ver si se congela.
//
// Uso, con WildFly levantado:   node pagos-simultaneos.mjs [N]        (N por defecto: 16)
// Sale con código 0 si los N pagos dieron 201 y ninguna consulta a Eventos tardó más de 2 segundos.
// Cada corrida compra N entradas de verdad en el sector de abajo: gasta cupo.
import net from 'node:net';
import { randomUUID } from 'node:crypto';

const N = Number(process.argv[2] ?? 16);
const SERVIDOR = 'http://localhost:8080';
const COMPRA = { eventoId: 'ev-2', sectorId: 'sec-2-1' };
const TOPE_DE_LA_SONDA_MS = 2000;
const esperar = (ms) => new Promise((listo) => setTimeout(listo, ms));

async function pedir(metodo, ruta, token, cuerpo) {
  const cabeceras = { 'Content-Type': 'application/json', 'Idempotency-Key': randomUUID() };
  if (token) cabeceras.Authorization = 'Bearer ' + token;
  const respuesta = await fetch(SERVIDOR + ruta, { method: metodo, headers: cabeceras, body: JSON.stringify(cuerpo) });
  const json = await respuesta.json();
  if (!respuesta.ok) throw new Error(`${metodo} ${ruta}: ${respuesta.status} ${JSON.stringify(json)}`);
  return json;
}

// 1. Un comprador y N ventas abiertas, cada una con su clave de pago.
const { token } = await pedir('POST', '/api/usuarios/login', null, { email: 'titular@palco.test', password: 'palco1234' });
const ventas = [];
for (let i = 0; i < N; i++) {
  const venta = await pedir('POST', '/api/v1/ventas', token,
    { eventoId: COMPRA.eventoId, items: [{ sectorId: COMPRA.sectorId, cantidad: 1 }], titulares: [{ nombre: 'Pago Simultaneo', dni: '30123456' }] });
  ventas.push({ id: venta.id, clave: randomUUID() });
}
console.log(`Abiertas ${N} ventas: ${ventas.map((v) => v.id).join(' ')}`);

// 2. Una conexión por pago, con el pedido entero menos el último byte.
function preparar(venta) {
  const cuerpo = JSON.stringify({ medioDePago: 'tarjeta', token: 'tok_test' });
  const pedido = `POST /api/v1/ventas/${venta.id}/pagos HTTP/1.1\r\nHost: localhost:8080\r\n`
    + `Authorization: Bearer ${token}\r\nIdempotency-Key: ${venta.clave}\r\nContent-Type: application/json\r\n`
    + `Content-Length: ${Buffer.byteLength(cuerpo)}\r\nConnection: close\r\n\r\n${cuerpo}`;
  return new Promise((lista) => {
    const conexion = net.connect(8080, '127.0.0.1', () => {
      conexion.write(pedido.slice(0, -1), () => lista(() => new Promise((fin) => {
        const salio = Date.now();
        let datos = '';
        const terminar = () => fin({ estado: Number(datos.slice(9, 12)) || 0, ms: Date.now() - salio, cuerpo: datos });
        conexion.on('data', (parte) => { datos += parte; });
        conexion.on('end', terminar);
        conexion.on('error', terminar);
        conexion.write(pedido.slice(-1));
      })));
    });
  });
}
const disparos = await Promise.all(ventas.map(preparar));

// 3. La sonda: una consulta a Eventos cada medio segundo, desde antes del disparo hasta después.
let sondear = true;
const sondas = [];
const sonda = (async () => {
  while (sondear) {
    const salio = Date.now();
    const estado = await fetch(SERVIDOR + '/api/eventos').then((r) => r.status).catch(() => 0);
    sondas.push({ ms: Date.now() - salio, estado });
    await esperar(500);
  }
})();
await esperar(1000);

// 4. El disparo: el último byte de los N pedidos.
const salio = Date.now();
const respuestas = await Promise.all(disparos.map((disparar) => disparar()));
console.log(`Los ${N} pagos terminaron en ${Date.now() - salio} ms`);
await esperar(2000);
sondear = false;
await sonda;

// 5. El resultado.
const porCodigo = {};
respuestas.forEach((r, i) => {
  porCodigo[r.estado] = (porCodigo[r.estado] ?? 0) + 1;
  if (r.estado !== 201) {
    const tipo = (r.cuerpo.match(/problemas\/[a-z-]+/) ?? ['sin cuerpo'])[0];
    console.log(`  ${ventas[i].id}: ${r.estado} ${tipo} (${r.ms} ms)`);
  }
});
const sondaMasLenta = Math.max(...sondas.map((s) => s.ms));
const sondasFallidas = sondas.filter((s) => s.estado !== 200).length;
console.log(`Pagos por código: ${JSON.stringify(porCodigo)}`);
console.log(`GET /api/eventos durante la prueba: ${sondas.length} consultas, la más lenta ${sondaMasLenta} ms, fallidas ${sondasFallidas}`);

const bien = porCodigo[201] === N && sondaMasLenta <= TOPE_DE_LA_SONDA_MS && sondasFallidas === 0;
console.log(bien
  ? `BIEN: los ${N} pagos dieron 201 y la base no se congeló.`
  : `MAL: se esperaban ${N} pagos con 201 y ninguna consulta de más de ${TOPE_DE_LA_SONDA_MS} ms.`);
process.exit(bien ? 0 : 1);
