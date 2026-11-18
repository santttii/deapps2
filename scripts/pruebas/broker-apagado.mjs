// Resiliencia: si se apaga el broker, no se pierden mensajes.
//
// El broker (ActiveMQ Artemis) está embebido en WildFly: apagar el broker es apagar el servidor.
// La prueba deja un PAGO_CONFIRMADO esperando en la cola, reinicia el servidor entero y comprueba
// que, al volver, el mensaje sigue ahí y la entrada se emite. Funciona porque el mensaje es
// persistente (JMSDeliveryMode) y Artemis lo guarda en su journal en disco antes de confirmar.
//
// Necesita el servidor levantado (./dev.sh o docker compose) y jboss-cli, igual que escenarios.mjs.
// Uso: node scripts/pruebas/broker-apagado.mjs
import { BASE, COLA, MDB_EMISION, cli, contadores, dniAlAzar, esperar, eventoPropio, hasta, http, login, uuid, verContadores } from './lib.mjs';

let bien = 0, total = 0;
function comprobar(texto, ok, detalle = '') {
  total++;
  if (ok) bien++;
  console.log(`${ok ? 'ok  ' : 'MAL '} ${texto}${detalle ? `: ${detalle}` : ''}`);
}

const vivo = async () => {
  try {
    return (await fetch(`${BASE}/api/v1/health`)).status === 200;
  } catch {
    return false;
  }
};

console.log(`Prueba: apagar el broker con un mensaje pendiente. Servidor: ${BASE}. ${new Date().toISOString()}\n`);
let T = await login('titular@palco.test');
const evento = await eventoPropio('Prueba broker apagado');
const pedido = { eventoId: evento.id, items: [{ sectorId: evento.sectores[0].id, cantidad: 1 }],
  titulares: [{ nombre: 'Prueba Broker', dni: dniAlAzar() }] };

// 1. El consumidor de la cola, detenido: el mensaje se queda en la cola.
comprobar('1. stop-delivery del consumidor de la cola (EmisionDeEntradas)', cli(`${MDB_EMISION}:stop-delivery`).includes('success'));
const antes = contadores();

// 2. Una compra: el pago sale bien y el mensaje queda pendiente.
const venta = (await http('POST', '/api/v1/ventas', { token: T, clave: uuid(), cuerpo: pedido })).json;
const pago = await http('POST', `/api/v1/ventas/${venta.id}/pagos`, { token: T, clave: uuid(),
  cuerpo: { medioDePago: 'tarjeta', token: 'tok_test' } });
comprobar('2. el pago responde 201', pago.estado === 201, String(pago.estado));
await esperar(2000);
const conPendiente = contadores();
comprobar('   el mensaje quedó pendiente en la cola', conPendiente.colaPendientes - antes.colaPendientes === 1,
  verContadores(conPendiente));
const enCola = cli(`${COLA}:list-messages`);
comprobar('   y es persistente (JMSDeliveryMode = true)', /"JMSDeliveryMode" => true/.test(enCola));
const sinEmitir = (await http('GET', `/api/v1/ventas/${venta.id}`, { token: T })).json;
comprobar('   la venta sigue CONFIRMADA, sin emitir', sinEmitir.estado === 'CONFIRMADA', sinEmitir.estado);

// 3. Se apaga el servidor entero, con el broker adentro, y se lo vuelve a arrancar.
console.log('\n3. Apagando el servidor (y el broker)...');
try {
  cli(':shutdown(restart=true)');
} catch {
  // Al apagarse, jboss-cli pierde la conexión: es lo esperado.
}
const seCayo = await hasta(async () => !(await vivo()), { maximoMs: 30000, cadaMs: 200 });
comprobar('   el servidor dejó de responder', Boolean(seCayo));
const volvio = await hasta(vivo, { maximoMs: 180000, cadaMs: 1000 });
comprobar('   el servidor volvió a arrancar', Boolean(volvio));
await esperar(3000);

// 4. Al volver, el consumidor arranca solo y procesa el mensaje que había quedado.
T = await login('titular@palco.test');
const final = await hasta(async () => {
  const v = (await http('GET', `/api/v1/ventas/${venta.id}`, { token: T })).json;
  return v?.estado === 'EMITIDA' ? v : null;
}, { maximoMs: 60000, cadaMs: 500 });
comprobar('4. después del reinicio, la venta pasó a EMITIDA', final?.estado === 'EMITIDA', final?.estado ?? 'no se emitió');
comprobar('   y su entrada está VALIDA con su código', final?.entradas?.every((e) => e.estado === 'VALIDA' && e.codigoQr),
  final?.entradas?.map((e) => e.estado).join(' '));
const despues = contadores();
comprobar('   la cola quedó sin pendientes y con consumidores', despues.colaPendientes === 0 && despues.colaConsumidores > 0,
  verContadores(despues));

console.log(`\n${bien} de ${total} comprobaciones. ${bien === total ? 'NO SE PERDIÓ NINGÚN MENSAJE' : 'HAY FALLAS'}`);
process.exit(bien === total ? 0 : 1);
