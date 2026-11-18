// ETag en la API REST, contra el servidor en marcha: el detalle de un evento y una entrada
// devuelven ETag, y con If-None-Match responden 304 sin cuerpo mientras no cambien.
// Uso: node scripts/pruebas/etag.mjs
import { http, login, uuid } from './lib.mjs';

let bien = 0, total = 0;
function comprobar(texto, ok, detalle = '') {
  total++;
  if (ok) bien++;
  console.log(`${ok ? 'ok  ' : 'MAL '} ${texto}${detalle ? `: ${detalle}` : ''}`);
}

console.log(`ETag en la API. ${new Date().toISOString()}\n`);
console.log('A. GET /api/eventos/{slug}');
const ruta = '/api/eventos/atletico-norte-racing-sur';
let r = await fetch(`http://localhost:8080${ruta}`);
const e1 = r.headers.get('etag');
comprobar('200 con ETag', r.status === 200 && Boolean(e1), `${r.status} ETag ${e1}`);
r = await fetch(`http://localhost:8080${ruta}`, { headers: { 'If-None-Match': e1 } });
comprobar('con If-None-Match igual: 304 sin cuerpo', r.status === 304 && (await r.text()) === '', `${r.status}`);
r = await fetch(`http://localhost:8080${ruta}`, { headers: { 'If-None-Match': '"otro"' } });
comprobar('con otro valor: 200 con el cuerpo', r.status === 200, `${r.status}`);

console.log('\nB. Si el recurso cambia, cambia el ETag (una compra cambia las vendidas del sector)');
const T = await login('titular@palco.test');
const venta = (await http('POST', '/api/v1/ventas', { token: T, clave: uuid(),
  cuerpo: { eventoId: 'ev-1', items: [{ sectorId: 'sec-1-4', cantidad: 1 }],
    titulares: [{ nombre: 'Prueba Etag', dni: String(43000000 + Math.floor(Math.random() * 999999)) }] } })).json;
r = await fetch(`http://localhost:8080${ruta}`, { headers: { 'If-None-Match': e1 } });
const e2 = r.headers.get('etag');
comprobar('con el ETag viejo ya no es 304: 200 con ETag nuevo', r.status === 200 && e2 && e2 !== e1, `${r.status} ${e1} → ${e2}`);
await http('DELETE', `/api/v1/ventas/${venta.id}`, { token: T });

console.log('\nC. GET /api/v1/entradas/{id} (con sesión)');
const compras = (await http('GET', '/api/v1/ventas', { token: T })).json;
const entrada = compras.flatMap((v) => v.entradas ?? []).find((e) => e.estado === 'VALIDA');
const auth = { Authorization: `Bearer ${T}` };
r = await fetch(`http://localhost:8080/api/v1/entradas/${entrada.id}`, { headers: auth });
const e3 = r.headers.get('etag');
comprobar('200 con ETag', r.status === 200 && Boolean(e3), `${r.status} ETag ${e3}`);
r = await fetch(`http://localhost:8080/api/v1/entradas/${entrada.id}`, { headers: { ...auth, 'If-None-Match': e3 } });
comprobar('con If-None-Match igual: 304', r.status === 304, `${r.status}`);

console.log(`\n${bien} de ${total} comprobaciones`);
process.exit(bien === total ? 0 : 1);
