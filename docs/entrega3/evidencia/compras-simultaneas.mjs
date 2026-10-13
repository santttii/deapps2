// Palco - compras y pagos simultáneos contra ServicioDeVentas.
//
// Qué muestra: que muchas compras a la vez no se traban entre sí. Todos los servicios del servidor comparten un
// pool de 10 conexiones a la base. Ventas no retiene una conexión mientras espera a Eventos (al reservar) ni a
// Pagos (al cobrar): por eso pueden pasar más de 10 compras a la vez.
//
// Uso, desde la raíz del repo, con WildFly levantado y los servicios desplegados (necesita Node 18 o más nuevo):
//     node docs/entrega3/evidencia/compras-simultaneas.mjs
// Corre tres casos, uno después del otro, y de cada uno dice qué se espera:
//     1. 80 compras abiertas a la vez, cada una con su clave de idempotencia. Después las cancela.
//     2. 20 pedidos a la vez con la MISMA clave: tiene que quedar una sola venta. Después la cancela.
//     3. 30 compras ya abiertas, pagadas todas a la vez.
// Usa 1 entrada por compra de "Campo general" (sec-2-1) del evento ev-2. Deja 30 ventas EMITIDAS (30 entradas
// vendidas de ese sector); el resto queda cancelado y con el cupo devuelto. No mueve plata: la pasarela es simulada.
const H = 'http://localhost:8080', V = H + '/api/v1';
const J = { 'Content-Type': 'application/json' };
const EVENTO = 'ev-2', SLUG = 'kiroshi-tour-continental', SECTOR = 'sec-2-1';

const hora = () => new Date().toTimeString().slice(0, 8);
const espera = (ms) => new Promise((listo) => setTimeout(listo, ms));
const clave = () => crypto.randomUUID();
const dni = () => String(20000000 + Math.floor(Math.random() * 9000000));
// Cuenta las respuestas iguales: { '201': 80 } quiere decir 80 respuestas con código 201.
const contar = (respuestas, que) => respuestas.reduce((total, r) => { const k = que(r); total[k] = (total[k] || 0) + 1; return total; }, {});
const codigo = (r) => r.estado + (r.tipo ? ' ' + r.tipo.replace('/problemas/', '') : '');

const sesion = await (await fetch(H + '/api/usuarios/login', { method: 'POST', headers: J, body: JSON.stringify({ email: 'titular@palco.test', password: 'palco1234' }) })).json();
const A = { ...J, Authorization: 'Bearer ' + sesion.token };

const pedido = () => JSON.stringify({ eventoId: EVENTO, items: [{ sectorId: SECTOR, cantidad: 1 }], titulares: [{ nombre: 'Prueba Simultanea', dni: dni() }] });
const vendidas = async () => (await (await fetch(H + '/api/eventos/' + SLUG)).json()).sectores.find((s) => s.id === SECTOR).vendidas;
const abrir = async (k, cuerpo = pedido()) => { const r = await fetch(V + '/ventas', { method: 'POST', headers: { ...A, 'Idempotency-Key': k }, body: cuerpo }); const j = await r.json().catch(() => ({})); return { estado: r.status, tipo: j.type, id: j.id }; };
const pagar = async (ventaId) => { const r = await fetch(`${V}/ventas/${ventaId}/pagos`, { method: 'POST', headers: { ...A, 'Idempotency-Key': clave() }, body: JSON.stringify({ medioDePago: 'tarjeta', token: 'tok_test' }) }); const j = await r.json().catch(() => ({})); return { estado: r.status, tipo: j.type }; };
const cancelar = async (ventaId) => (await fetch(`${V}/ventas/${ventaId}`, { method: 'DELETE', headers: A })).status;
const ver = async (ventaId) => (await fetch(`${V}/ventas/${ventaId}`, { headers: A })).json();
// Abre n conexiones y las deja listas: así los n pedidos que siguen salen de verdad al mismo tiempo.
const abrirConexiones = (n) => Promise.all(Array.from({ length: n }, () => fetch(V + '/health').then((r) => r.text())));
// De a 10 por vez: es la parte que ordena, no la que se mide.
const deADiez = async (lista, hacer) => { const salida = []; for (let i = 0; i < lista.length; i += 10) salida.push(...await Promise.all(lista.slice(i, i + 10).map(hacer))); return salida; };

console.log('Palco - compras y pagos simultáneos. Salida de docs/entrega3/evidencia/compras-simultaneas.mjs');
console.log('Fecha:', new Date().toLocaleDateString('es-AR'), hora(), '(hora local)');

console.log('\n1. 80 compras abiertas a la vez, cada una con su clave. Se espera: 80 respuestas 201 y vendidas +80.');
let antes = await vendidas();
await abrirConexiones(80);
let inicio = Date.now();
const abiertas = await Promise.all(Array.from({ length: 80 }, () => abrir(clave())));
console.log('   respuestas:', contar(abiertas, codigo), '| tardó', Date.now() - inicio, 'ms en total');
await espera(1000);
console.log('   vendidas del sector:', antes, '->', await vendidas());
const canceladas = await deADiez(abiertas.filter((r) => r.id), (r) => cancelar(r.id));
await espera(1000);
console.log('   después de cancelarlas:', contar(canceladas, (c) => c), '| vendidas:', await vendidas(), '(se espera', antes + ')');

console.log('\n2. 20 pedidos a la vez con la misma clave. Se espera: 20 respuestas 201 con el mismo id y vendidas +1.');
antes = await vendidas();
const misma = clave(), cuerpo = pedido();
await abrirConexiones(20);
inicio = Date.now();
const repetidas = await Promise.all(Array.from({ length: 20 }, () => abrir(misma, cuerpo)));
const ids = [...new Set(repetidas.map((r) => r.id))];
console.log('   respuestas:', contar(repetidas, codigo), '| ventas distintas:', ids, '| tardó', Date.now() - inicio, 'ms en total');
await espera(1000);
console.log('   vendidas del sector:', antes, '->', await vendidas());
console.log('   cancelar esa venta:', await cancelar(ids[0]), '| vendidas:', (await espera(800), await vendidas()), '(se espera', antes + ')');

console.log('\n3. 30 compras abiertas y pagadas todas a la vez. Se espera: 30 respuestas 201 y las 30 ventas EMITIDAS.');
const paraPagar = await deADiez(Array.from({ length: 30 }), () => abrir(clave()));
await abrirConexiones(30);
inicio = Date.now();
const pagos = await Promise.all(paraPagar.map((r) => pagar(r.id)));
console.log('   respuestas:', contar(pagos, codigo), '| tardó', Date.now() - inicio, 'ms en total');
await espera(3000);
const finales = await deADiez(paraPagar, (r) => ver(r.id));
console.log('   tres segundos después:', contar(finales, (v) => v.estado), '| entradas:', contar(finales.flatMap((v) => v.entradas), (e) => e.estado));
console.log('   primera y última venta:', paraPagar[0].id, paraPagar[29].id);
console.log('\nTerminó a las', hora() + '. En el log del servidor no tiene que haber ninguna línea nueva con "no respondió".');
