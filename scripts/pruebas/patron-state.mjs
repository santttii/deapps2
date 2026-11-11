// Prueba del patrón State contra el servidor en marcha: cada transición inválida responde 409.
import { http, login, uuid } from './lib.mjs';

const T = await login('titular@palco.test');
const pedido = () => ({ eventoId: 'ev-1', items: [{ sectorId: 'sec-1-4', cantidad: 1 }],
  titulares: [{ nombre: 'Prueba State', dni: String(40000000 + Math.floor(Math.random() * 999999)) }] });
const abrir = () => http('POST', '/api/v1/ventas', { token: T, clave: uuid(), cuerpo: pedido() });
const pagar = (id) => http('POST', `/api/v1/ventas/${id}/pagos`, { token: T, clave: uuid(),
  cuerpo: { medioDePago: 'tarjeta', token: 'tok_test' } });
const cancelar = (id) => http('DELETE', `/api/v1/ventas/${id}`, { token: T });
const ver = (id) => http('GET', `/api/v1/ventas/${id}`, { token: T });

let bien = 0, total = 0;
function mostrar(paso, r, esperado) {
  total++;
  const ok = r.estado === esperado;
  if (ok) bien++;
  const tipo = r.json?.type ? `  type=${r.json.type}` : '';
  const estado = r.json?.estado ? `  estado=${r.json.estado}` : '';
  console.log(`${ok ? 'ok  ' : 'MAL '} ${paso.padEnd(58)} → ${r.estado}${tipo}${estado}`);
}

console.log('A. Venta pagada (CONFIRMADA / EMITIDA)');
const a = await abrir(); mostrar('abrir la compra (EN_CHECKOUT)', a, 201);
mostrar('pagarla (EN_CHECKOUT → CONFIRMADA)', await pagar(a.json.id), 201);
await new Promise((r) => setTimeout(r, 1500));
mostrar('ver la venta (la cola la emitió)', await ver(a.json.id), 200);
mostrar('pagarla otra vez, con otra clave (transición inválida)', await pagar(a.json.id), 409);
mostrar('cancelarla estando paga (transición inválida)', await cancelar(a.json.id), 409);

console.log('B. Venta cancelada (CANCELADA)');
const b = await abrir(); mostrar('abrir otra compra (EN_CHECKOUT)', b, 201);
mostrar('cancelarla (EN_CHECKOUT → CANCELADA)', await cancelar(b.json.id), 204);
mostrar('pagarla cancelada (transición inválida)', await pagar(b.json.id), 409);
mostrar('cancelarla de nuevo (no es error: no hace nada)', await cancelar(b.json.id), 204);
mostrar('ver la venta', await ver(b.json.id), 200);

console.log(`\n${bien} de ${total} respuestas como se esperaba`);
process.exit(bien === total ? 0 : 1);
