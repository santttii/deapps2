// ETag en la API REST, contra el servidor en marcha. Cada GET con @ConEtag devuelve ETag, con
// If-None-Match responde 304 sin cuerpo mientras no cambie, y si el recurso cambia, cambia el ETag.
// Uso: node scripts/pruebas/etag.mjs
import { BASE, eventoPropio, dniAlAzar, hasta, http, login, uuid } from './lib.mjs';

let bien = 0, total = 0;
function comprobar(texto, ok, detalle = '') {
  total++;
  if (ok) bien++;
  console.log(`${ok ? 'ok  ' : 'MAL '} ${texto}${detalle ? `: ${detalle}` : ''}`);
}

/** Pide el recurso, toma su ETag y lo vuelve a pedir con If-None-Match: tiene que dar 304 sin cuerpo. */
async function conEtag(nombre, ruta, token) {
  const auth = token ? { Authorization: `Bearer ${token}` } : {};
  let r = await fetch(BASE + ruta, { headers: auth });
  const etag = r.headers.get('etag');
  const segundo = await fetch(BASE + ruta, { headers: { ...auth, 'If-None-Match': etag } });
  const cuerpo = await segundo.text();
  comprobar(`${nombre}: 200 con ETag y, con If-None-Match, 304 sin cuerpo`,
    r.status === 200 && Boolean(etag) && segundo.status === 304 && cuerpo === '', `${r.status} ${etag} → ${segundo.status}`);
  return etag;
}

console.log(`ETag en la API. Servidor: ${BASE}. ${new Date().toISOString()}\n`);
const T = await login('titular@palco.test');
const O = await login('organizador@palco.test');
const ST = await login('puerta@palco.test');
const evento = await eventoPropio('Prueba ETag');
const sector = evento.sectores[0].id;

// Una compra pagada y emitida, para tener entrada, venta y factura.
const venta = (await http('POST', '/api/v1/ventas', { token: T, clave: uuid(),
  cuerpo: { eventoId: evento.id, items: [{ sectorId: sector, cantidad: 1 }], titulares: [{ nombre: 'Prueba Etag', dni: dniAlAzar() }] } })).json;
await http('POST', `/api/v1/ventas/${venta.id}/pagos`, { token: T, clave: uuid(), cuerpo: { medioDePago: 'tarjeta', token: 'tok_test' } });
const emitida = await hasta(async () => {
  const v = (await http('GET', `/api/v1/ventas/${venta.id}`, { token: T })).json;
  return v?.estado === 'EMITIDA' ? v : null;
});
await hasta(async () => (await http('GET', `/api/v1/facturas/${venta.id}`, { token: T })).estado === 200, { maximoMs: 30000 });

console.log('A. Cada endpoint');
const eDetalle = await conEtag('GET /api/eventos/{slug} (detalle de un evento)', `/api/eventos/${evento.slug}`);
await conEtag('GET /api/eventos (catálogo)', '/api/eventos');
await conEtag('GET /api/eventos/mios (panel del organizador)', '/api/eventos/mios', O);
await conEtag('GET /api/v1/ventas (mis compras)', '/api/v1/ventas', T);
await conEtag('GET /api/v1/ventas/{id} (una compra)', `/api/v1/ventas/${venta.id}`, T);
await conEtag('GET /api/v1/entradas/{id} (una entrada)', `/api/v1/entradas/${emitida.entradas[0].id}`, T);
await conEtag('GET /api/v1/facturas/{ventaId} (la factura)', `/api/v1/facturas/${venta.id}`, T);
await conEtag('GET /api/v1/reventas?eventoId= (reventa del evento)', `/api/v1/reventas?eventoId=${evento.id}`);
await conEtag('GET /api/v1/reventas/mias (mis publicaciones)', '/api/v1/reventas/mias', T);
await conEtag('GET /api/v1/validacion/escaneos?eventoId= (registro de la puerta)', `/api/v1/validacion/escaneos?eventoId=${evento.id}`, ST);

console.log('\nB. Con otro valor en If-None-Match, 200 con el cuerpo');
let r = await fetch(`${BASE}/api/eventos/${evento.slug}`, { headers: { 'If-None-Match': '"otro"' } });
comprobar('200 con el cuerpo', r.status === 200 && (await r.text()).length > 0, `${r.status}`);

console.log('\nC. Si el recurso cambia, cambia el ETag (otra compra cambia las vendidas del sector)');
const otra = (await http('POST', '/api/v1/ventas', { token: T, clave: uuid(),
  cuerpo: { eventoId: evento.id, items: [{ sectorId: sector, cantidad: 1 }], titulares: [{ nombre: 'Prueba Etag', dni: dniAlAzar() }] } })).json;
r = await fetch(`${BASE}/api/eventos/${evento.slug}`, { headers: { 'If-None-Match': eDetalle } });
const nuevo = r.headers.get('etag');
comprobar('con el ETag viejo ya no es 304: 200 con ETag nuevo', r.status === 200 && nuevo && nuevo !== eDetalle, `${r.status} ${eDetalle} → ${nuevo}`);
await http('DELETE', `/api/v1/ventas/${otra.id}`, { token: T });

console.log('\nD. Un POST no lleva ETag (solo los GET)');
const post = await fetch(`${BASE}/api/v1/ventas`, {
  method: 'POST',
  headers: { Authorization: `Bearer ${T}`, 'Idempotency-Key': uuid(), 'Content-Type': 'application/json' },
  body: JSON.stringify({ eventoId: evento.id, items: [{ sectorId: sector, cantidad: 1 }],
    titulares: [{ nombre: 'Prueba Etag', dni: dniAlAzar() }] }),
});
const abierta = await post.json();
comprobar('el POST que abre una compra no trae ETag', post.status === 201 && post.headers.get('etag') === null,
  `${post.status} ETag ${post.headers.get('etag')}`);
await http('DELETE', `/api/v1/ventas/${abierta.id}`, { token: T });

console.log(`\n${bien} de ${total} comprobaciones`);
process.exit(bien === total ? 0 : 1);
