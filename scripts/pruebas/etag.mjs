// ETag en la API REST, contra el servidor en marcha. Cada GET con @ConEtag devuelve ETag, con
// If-None-Match responde 304 sin cuerpo mientras no cambie, y si el recurso cambia, cambia el ETag.
// El otro lado es If-Match: la edición de un evento (PUT /api/eventos/{id}) solo se guarda si el
// evento sigue como se leyó; si no, 412 en Problem Details.
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

/** El cuerpo de PUT /api/eventos/{id} armado con el evento como lo devolvió el GET: los mismos sectores, otro título. */
function edicion(e, titulo) {
  return {
    titulo, categoria: e.categoria, venue: e.venue, fechaISO: e.fechaISO, descripcion: e.descripcion,
    imagenUrl: e.imagenUrl ?? null,
    sectores: e.sectores.map((s) => ({ id: s.id, nombre: s.nombre, precio: s.precio, cupo: s.cupo })),
  };
}

/** Edita el evento como su organizador; con `ifMatch`, manda esa cabecera. Devuelve el código, el ETag y el cuerpo. */
async function editar(token, id, cuerpo, ifMatch) {
  const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
  if (ifMatch !== undefined) headers['If-Match'] = ifMatch;
  const r = await fetch(`${BASE}/api/eventos/${id}`, { method: 'PUT', headers, body: JSON.stringify(cuerpo) });
  const texto = await r.text();
  let json = null;
  try { json = texto ? JSON.parse(texto) : null; } catch { /* no es JSON */ }
  return { estado: r.status, etag: r.headers.get('etag'), tipo: r.headers.get('content-type') ?? '', json, texto };
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

console.log('\nE. If-Match en la edición de un evento (PUT /api/eventos/{id})');
r = await fetch(`${BASE}/api/eventos/${evento.slug}`);
const leido = r.headers.get('etag');
const comoSeLeyo = await r.json();
let editado = await editar(O, evento.id, edicion(comoSeLeyo, `${comoSeLeyo.titulo} (editado)`), leido);
comprobar('con el ETag del GET en If-Match: 200 con ETag nuevo',
  editado.estado === 200 && Boolean(editado.etag) && editado.etag !== leido, `${editado.estado} ${leido} → ${editado.etag}`);
r = await fetch(`${BASE}/api/eventos/${evento.slug}`);
comprobar('el ETag de la respuesta del PUT es el que da el GET siguiente', r.headers.get('etag') === editado.etag,
  `PUT ${editado.etag} / GET ${r.headers.get('etag')}`);

// Otra pestaña que leyó lo mismo y edita después: su ETag ya es viejo.
const pisado = await editar(O, evento.id, edicion(comoSeLeyo, `${comoSeLeyo.titulo} (pisado)`), leido);
const p = pisado.json ?? {};
comprobar('con el ETag viejo: 412 evento-modificado en Problem Details',
  pisado.estado === 412 && pisado.tipo.startsWith('application/problem+json') && p.type === '/problemas/evento-modificado'
    && p.status === 412 && typeof p.title === 'string' && typeof p.detail === 'string' && p.instance === `/api/eventos/${evento.id}`,
  `${pisado.estado} ${pisado.tipo} ${pisado.texto.slice(0, 260)}`);
r = await fetch(`${BASE}/api/eventos/${evento.slug}`);
const despues = await r.json();
comprobar('el 412 no guardó nada: el evento quedó como la edición que pasó',
  r.headers.get('etag') === editado.etag && despues.titulo === `${comoSeLeyo.titulo} (editado)`, `${despues.titulo} ${r.headers.get('etag')}`);

const debil = await editar(O, evento.id, edicion(comoSeLeyo, comoSeLeyo.titulo), `W/${editado.etag}`);
comprobar('If-Match compara fuerte: el ETag actual como W/"…" es 412', debil.estado === 412, `${debil.estado}`);
const sinCondicion = await editar(O, evento.id, edicion(comoSeLeyo, comoSeLeyo.titulo));
comprobar('sin If-Match edita como siempre: 200', sinCondicion.estado === 200 && sinCondicion.json?.titulo === comoSeLeyo.titulo,
  `${sinCondicion.estado} ${sinCondicion.json?.titulo}`);

console.log(`\n${bien} de ${total} comprobaciones`);
process.exit(bien === total ? 0 : 1);
