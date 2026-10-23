// Prueba de integración del backend de Palco: 19 escenarios de compra, mensajería, conciliación de pagos,
// facturación, validación en puerta, reventa (con su conciliación) y devolución de cupo con Eventos caído, de
// punta a punta, por la API y por jboss-cli. Los 13 primeros son los que produjeron docs/entrega3/evidencia/integracion-13-escenarios.txt.
//
//   node scripts/pruebas/escenarios.mjs              corre los 19 escenarios
//   node scripts/pruebas/escenarios.mjs 1 2 7        corre solo esos
//
// Se corre desde cualquier carpeta: las rutas salen de la ubicación de este archivo. Necesita Node 18 o
// más nuevo (fetch nativo; no hay dependencias que instalar), WildFly levantado con los 8 WAR
// (backend/dev.sh) y, para los contadores del broker y el log, que el servidor sea el armado en
// backend/servidor/target/server de esta máquina.
// Sale con código 0 si todas las comprobaciones pasan y con 1 si alguna falla.
//
// Los escenarios 8, 10, 11 y 12 detienen un consumidor (stop-delivery) o pasan mensajes de una cola a
// otra (move-messages), y el 14 prende por unos segundos una propiedad de sistema que hace fallar todo
// pago después del cobro. El 18 saca de servicio por un rato el WAR de Eventos: mientras tanto nadie puede
// ver el catálogo ni comprar. Si otra persona está usando el servidor, se corren solo los demás:
//   node scripts/pruebas/escenarios.mjs 1 2 3 4 5 6 7 9 13 15 16 17
// El 14 espera a la conciliación de pagos, que corre una vez por minuto con 2 minutos de margen: tarda
// entre 2 y 4 minutos. El 18 espera al barrido de devoluciones, que corre una vez por minuto: tarda hasta 1.
// El 19 espera a la conciliación de reventas, que corre una vez por minuto con 2 minutos de margen: entre 2 y 4.
//
// Los valores se comparan antes y después (el cupo, los contadores del broker, el log); los ids salen de
// las respuestas. Los contadores del broker son de todo el servidor: si otra persona compra mientras
// corre la prueba, las diferencias suben por esas ventas. La prueba las busca en el log y las descuenta;
// si aun así no cierra, hay que repetirla.
// Deja en la base ventas emitidas y canceladas de titular@palco.test, una cuenta nueva y un evento de prueba,
// con sus facturas, escaneos de puerta y una publicación de reventa vendida a otro.titular@palco.test.
// El escenario 11 (suscriptor durable) tiene además su propio guion en docs/entrega3/evidencia.
//
// Variables de entorno (todas opcionales):
//   PALCO_URL   dirección del servidor                       (http://localhost:8080)
//   JAVA_HOME   el Java de jboss-cli; al modo de Windows o de Git Bash (/c/...)   (el del PATH)
//   JBOSS_CLI   ruta de jboss-cli, si no está en la del proyecto
//   PALCO_LOG   ruta del server.log, si no está en la del proyecto
import {
  COLA, COLA_DE_AUDITORIA, COLA_DE_FACTURACION, DLQ, Informe, MDB_AUDITORIA, MDB_EMISION, SUSCRIPCION_DE_AUDITORIA, TOPICO, cli,
  contadores, diferencia, esProblema, esperar, hasta, hora, http, logDesde, login, marcaDelLog, numeros,
  sector, tokenDeServicio, uuid, vendidas, verContadores,
} from './lib.mjs';

// Las compras van sobre un evento que crea esta misma prueba (como organizador), con un sector igual a
// Platea B de ev-1: 27.500, así dos entradas más el 10 % de servicio dan 60.500. Nadie más compra ahí, y
// el cupo se puede comparar exacto aunque otra persona esté usando el servidor.
let EVENTO; let SECTOR; let MINI; let GRANDE;
const PRECIO = 27500;
/** El tópico tiene tres suscripciones (aviso, auditoría y facturación): cada mensaje agrega una copia por cada una. */
const COPIAS_DEL_TOPICO = 3;

const informe = new Informe();
const pedidos = process.argv.slice(2).map(Number);
const corre = (n) => pedidos.length === 0 || pedidos.includes(n);

let dniSiguiente = 20000000 + Math.floor(Math.random() * 9000000);
const titular = (nombre) => ({ nombre, dni: String(dniSiguiente++) });
const pedidoDe = (cantidad, sectorId = SECTOR, eventoId = EVENTO) => ({
  eventoId,
  items: [{ sectorId, cantidad }],
  titulares: Array.from({ length: cantidad }, (_, i) => titular(`Prueba Integracion ${i + 1}`)),
});

let T; let S; let O; let ST;   // tokens: titular (user), servicio, organizador y staff

const abrir = (cuerpo, clave = uuid(), token = T) => http('POST', '/api/v1/ventas', { token, clave, cuerpo });
const pagar = (ventaId, tokenDePago = 'tok_test', clave = uuid(), token = T) => http('POST',
  `/api/v1/ventas/${ventaId}/pagos`, { token, clave, cuerpo: { medioDePago: 'tarjeta', token: tokenDePago } });
const ver = (ventaId, token = T) => http('GET', `/api/v1/ventas/${ventaId}`, { token });
const cancelar = (ventaId, token = T) => http('DELETE', `/api/v1/ventas/${ventaId}`, { token });
const emitida = (ventaId) => hasta(async () => {
  const r = await ver(ventaId);
  return r.json?.estado === 'EMITIDA' ? r.json : null;
});
const auditoriaDe = async (ventaId) => {
  const r = await http('GET', '/api/notificaciones/auditoria', { token: S });
  return (r.json ?? []).filter((fila) => fila.ventaId === ventaId);
};
const lineasCon = (marca, ...textos) => logDesde(marca).filter((l) => textos.every((t) => l.includes(t)));

// ===================================================================================== compra

async function escenario1() {
  informe.escenario(1, 'Compra completa: iniciar, pagar contra Pagos, venta emitida, cupo y pago');
  const antes = await vendidas(SECTOR);
  informe.dato(`vendidas de ${SECTOR} antes: ${antes}`);

  const abierta = await abrir(pedidoDe(2));
  informe.igual('POST /api/v1/ventas responde', abierta.estado, 201);
  const v = abierta.json;
  informe.ok('Location apunta a la venta', abierta.location?.endsWith(`/api/v1/ventas/${v.id}`), abierta.location);
  informe.igual('estado al abrir', v.estado, 'EN_CHECKOUT');
  informe.igual('montoTotal (2 x 27.500 + 10 %)', v.montoTotal, 60500);
  informe.ok('trae la fecha de creación y la reserva vence 10 minutos después',
    Date.parse(v.venceReserva) - Date.parse(v.creada) === 600000, `creada=${v.creada} venceReserva=${v.venceReserva}`);
  informe.ok('sin entradas ni pago, con los enlaces pagar y cancelar',
    v.entradas.length === 0 && v.pagoId === null && v._links.pagar && v._links.cancelar, JSON.stringify(v._links));
  informe.igual('vendidas con la compra abierta (reserva de cupo en Eventos)', await vendidas(SECTOR), antes + 2);

  const pagada = await pagar(v.id);
  informe.igual('POST /api/v1/ventas/{id}/pagos responde', pagada.estado, 201);
  const p = pagada.json;
  informe.ok('al volver el pago la venta está CONFIRMADA (o ya EMITIDA) con su pago',
    ['CONFIRMADA', 'EMITIDA'].includes(p.estado) && /^P-[0-9a-f]{8}$/.test(p.pagoId), `${p.estado} ${p.pagoId}`);
  informe.ok('Location del pago', pagada.location?.endsWith(`/api/v1/ventas/${v.id}/pagos/${p.pagoId}`), pagada.location);
  informe.ok('dos entradas, cada una con sectorId y precioPagado',
    p.entradas.length === 2 && p.entradas.every((e) => e.sectorId === SECTOR && e.precioPagado === PRECIO),
    p.entradas.map((e) => `${e.id} ${e.estado} ${e.sectorId} ${e.precioPagado}`).join(' | '));

  const final = await emitida(v.id);
  informe.ok('la venta pasa a EMITIDA (emisión asincrónica)', final !== null, final?.estado);
  informe.ok('las dos entradas quedan VALIDA y traen su código dentro de la venta',
    final?.entradas.every((e) => e.estado === 'VALIDA' && typeof e.codigoQr === 'string' && e.codigoQr.length > 0),
    final?.entradas.map((e) => `${e.id} ${e.estado} ${e.codigoQr}`).join(' | '));
  const entrada = await http('GET', `/api/v1/entradas/${final.entradas[0].id}`, { token: T });
  informe.ok('GET /api/v1/entradas/{id}: VALIDA, mismo código, con fecha de emisión y precio pagado',
    entrada.estado === 200 && entrada.json.estado === 'VALIDA' && entrada.json.codigoQr === final.entradas[0].codigoQr
      && entrada.json.emitida && entrada.json.precioPagado === PRECIO && entrada.json.ventaId === v.id,
    `${entrada.estado} ${entrada.texto.slice(0, 300)}`);
  informe.igual('vendidas al final: subió exactamente lo comprado', await vendidas(SECTOR), antes + 2);

  const pago = await http('GET', `/api/v1/pagos/${p.pagoId}`, { token: S });
  informe.ok('en Pagos existe ese pago, de esa venta, por ese monto y APROBADO',
    pago.estado === 200 && pago.json.pagoId === p.pagoId && pago.json.ventaId === v.id
      && pago.json.monto === 60500 && pago.json.estado === 'APROBADO', `${pago.estado} ${pago.texto}`);

  const lista = await http('GET', '/api/v1/ventas', { token: T });
  const enLista = lista.json?.find((x) => x.id === v.id);
  informe.ok('GET /api/v1/ventas (mis compras) la trae EMITIDA y con sus entradas',
    lista.estado === 200 && enLista?.estado === 'EMITIDA' && enLista.entradas.length === 2
      && enLista.entradas.every((e) => e.codigoQr), `${lista.estado}, ${lista.json?.length} ventas`);
  const fechasDeLaLista = lista.json.map((x) => x.creada);
  informe.ok('y la lista viene de la más nueva a la más vieja',
    fechasDeLaLista.every((f, i) => i === 0 || fechasDeLaLista[i - 1] >= f), fechasDeLaLista.slice(0, 4).join(' '));
  return { ventaId: v.id, pagoId: p.pagoId, entradaId: final.entradas[0].id };
}

async function escenario2() {
  informe.escenario(2, 'Idempotencia: la misma clave al iniciar y al pagar no duplica nada');
  const cupoAntes = await vendidas(SECTOR);
  const brokerAntes = contadores();
  const marca = marcaDelLog();
  const cuerpo = pedidoDe(1);
  const clave = uuid();

  const a = await abrir(cuerpo, clave);
  const b = await abrir(cuerpo, clave);
  informe.ok('los dos POST /ventas con la misma clave responden 201 con la misma venta',
    a.estado === 201 && b.estado === 201 && a.json.id === b.json.id, `${a.estado} ${a.json?.id} / ${b.estado} ${b.json?.id}`);
  informe.igual('el cupo se reservó una sola vez', await vendidas(SECTOR), cupoAntes + 1);

  const clavePago = uuid();
  const p1 = await pagar(a.json.id, 'tok_test', clavePago);
  const p2 = await pagar(a.json.id, 'tok_test', clavePago);
  informe.ok('los dos pagos con la misma clave responden 201 con el mismo pago',
    p1.estado === 201 && p2.estado === 201 && p1.json.pagoId === p2.json.pagoId,
    `${p1.estado} ${p1.json?.pagoId} / ${p2.estado} ${p2.json?.pagoId}`);
  await emitida(a.json.id);
  await esperar(1500);

  const conEseTitular = (await http('GET', '/api/v1/ventas', { token: T })).json
    .filter((x) => x.entradas.some((e) => e.titular.dni === cuerpo.titulares[0].dni));
  informe.ok('en "mis compras" hay una sola venta con ese titular', conEseTitular.length === 1 && conEseTitular[0].id === a.json.id,
    conEseTitular.map((x) => x.id).join(' '));
  informe.igual('cupo al final', await vendidas(SECTOR), cupoAntes + 1);
  const cobros = lineasCon(marca, 'ServicioDePagos', `de la venta ${a.json.id}`);
  informe.igual('un solo pago guardado por Pagos para esa venta (líneas de ServicioDePagos en el log)', cobros.length, 1);
  const confirmaciones = lineasCon(marca, `Compra ${a.json.id} confirmada`);
  informe.igual('una sola confirmación de Ventas', confirmaciones.length, 1);
  const brokerDespues = contadores();
  const deOtros = ajenas(marca, [a.json.id]);
  informe.dato(`broker: ${diferencia(brokerAntes, brokerDespues)}`);
  mensajes('un solo mensaje a la cola', brokerDespues.colaAgregados - brokerAntes.colaAgregados, 1, deOtros);
  mensajes('una sola copia para auditoría (un mensaje al tópico)',
    brokerDespues.auditoriaAgregados - brokerAntes.auditoriaAgregados, 1, deOtros);
  informe.igual('una sola fila de auditoría de esa venta', (await auditoriaDe(a.json.id)).length, 1);
}

async function escenario3() {
  informe.escenario(3, 'Pago rechazado: 402, venta sin confirmar, ningún mensaje; un segundo intento la completa');
  const abierta = await abrir(pedidoDe(1));
  const v = abierta.json;
  const brokerAntes = contadores();
  const marca = marcaDelLog();

  const claveRechazo = uuid();
  const rechazo = await pagar(v.id, 'tok_rechazado', claveRechazo);
  esProblema(informe, rechazo, 402, 'pago-rechazado', `/api/v1/ventas/${v.id}/pagos`);
  informe.dato(`cuerpo del 402: ${rechazo.texto}`);
  const sigue = (await ver(v.id)).json;
  informe.ok('la venta sigue EN_CHECKOUT, sin pago ni entradas',
    sigue.estado === 'EN_CHECKOUT' && sigue.pagoId === null && sigue.entradas.length === 0,
    `${sigue.estado} pagoId=${sigue.pagoId} entradas=${sigue.entradas.length}`);
  await esperar(1500);
  const brokerTrasRechazo = contadores();
  const deOtrosEnElRechazo = ajenas(marca, [v.id]);
  informe.dato(`broker tras el rechazo: ${diferencia(brokerAntes, brokerTrasRechazo)}`);
  mensajes('el rechazo no publicó nada en la cola', brokerTrasRechazo.colaAgregados - brokerAntes.colaAgregados, 0, deOtrosEnElRechazo);
  mensajes('ni en el tópico', brokerTrasRechazo.topicoAgregados - brokerAntes.topicoAgregados, 0, deOtrosEnElRechazo, COPIAS_DEL_TOPICO);
  informe.igual('ninguna fila de auditoría de esa venta', (await auditoriaDe(v.id)).length, 0);
  informe.igual('ninguna línea de los consumidores por esa venta',
    lineasCon(marca, v.id).filter((l) => /EmisionDeEntradas|AvisoDeCompra|AuditoriaDeCompras/.test(l)).length, 0);

  const mismaClave = await pagar(v.id, 'tok_test', claveRechazo);
  informe.igual('repetir la MISMA clave devuelve el mismo rechazo aunque cambie el token (regla de Pagos)',
    mismaClave.estado, 402);

  const aprobado = await pagar(v.id, 'tok_test');
  informe.igual('segundo intento, con clave nueva y tok_test', aprobado.estado, 201);
  const final = await emitida(v.id);
  informe.ok('la compra se completa: EMITIDA con su entrada VALIDA',
    final?.estado === 'EMITIDA' && final.entradas[0].estado === 'VALIDA', `${final?.estado} ${final?.entradas[0]?.estado}`);
  await esperar(1000);
  const brokerFinal = contadores();
  mensajes('ahora sí salió un mensaje a la cola', brokerFinal.colaAgregados - brokerAntes.colaAgregados, 1, ajenas(marca, [v.id]));
}

async function eventoDePrueba() {
  const r = await http('POST', '/api/eventos', {
    token: O,
    cuerpo: {
      titulo: `Integracion backend ${Date.now()}`, categoria: 'Teatro', venue: 'Sala de prueba',
      fechaISO: '2027-12-31T20:00:00', descripcion: 'Evento de la prueba de integración del backend.',
      sectores: [{ nombre: 'Platea B', precio: PRECIO, cupo: 500 }, { nombre: 'Mini', precio: 1000, cupo: 3 },
        { nombre: 'Grande', precio: 2000, cupo: 50 }],
    },
  });
  if (r.estado !== 201) throw new Error(`No se pudo crear el evento de prueba: ${r.estado} ${r.texto}`);
  return r.json;
}

/**
 * Las ventas que se confirmaron desde la marca del log y no son de esta prueba: otra persona comprando a
 * la vez. Cada una publica su propio mensaje, así que los contadores del broker suben también por ellas.
 */
const ajenas = (marca, propias) => logDesde(marca)
  .filter((l) => l.includes('PAGO_CONFIRMADO publicado en la misma transacción'))
  .map((l) => l.match(/(?:Compra|la venta) (V-[0-9a-f]{8})/)?.[1])
  .filter((id) => id && !propias.includes(id));

/** Compara un contador con lo que publicó esta prueba más lo que publicaron las ventas ajenas del mismo rato. */
function mensajes(descripcion, visto, propios, deOtros, porCadaUno = 1) {
  const nota = deOtros.length === 0 ? '' : ` (más ${deOtros.length * porCadaUno} de ventas ajenas: ${deOtros.join(' ')})`;
  return informe.ok(`${descripcion} (se espera ${propios}${nota})`, visto === propios + deOtros.length * porCadaUno, visto);
}

async function escenario4() {
  informe.escenario(4, 'Reglas de negocio: cada una con su código y su cuerpo Problem Details');
  const cupoAntes = await vendidas(SECTOR);
  const R = '/api/v1/ventas';

  esProblema(informe, await abrir(pedidoDe(7)), 422, 'maximo-de-entradas', R);
  const repetido = pedidoDe(2);
  repetido.titulares[1].dni = repetido.titulares[0].dni;
  esProblema(informe, await abrir(repetido), 422, 'dni-repetido', R);
  const unTitular = pedidoDe(2);
  unTitular.titulares.pop();
  esProblema(informe, await abrir(unTitular), 422, 'titulares-no-coinciden', R);
  esProblema(informe, await abrir(pedidoDe(1, 'sec-no-existe')), 422, 'sector-inexistente', R);

  const evento = { id: EVENTO };
  const [mini, grande] = [MINI, GRANDE];
  informe.dato(`sector ${mini.id} con cupo ${mini.cupo} y ${grande.id} con cupo ${grande.cupo}`);
  esProblema(informe, await abrir(pedidoDe(4, mini.id, evento.id)), 409, 'cupo-agotado', R);
  informe.igual('el sector sin cupo suficiente no vendió nada', await vendidas(mini.id), 0);
  const dosSectores = {
    eventoId: evento.id,
    items: [{ sectorId: grande.id, cantidad: 1 }, { sectorId: mini.id, cantidad: 4 }],
    titulares: Array.from({ length: 5 }, (_, i) => titular(`Dos Sectores ${i + 1}`)),
  };
  esProblema(informe, await abrir(dosSectores), 409, 'cupo-agotado', R);
  informe.igual('si falla el segundo sector, el primero devuelve lo que había reservado', await vendidas(grande.id), 0);
  const justo = await abrir(pedidoDe(3, mini.id, evento.id));
  informe.igual('exactamente el cupo que queda sí se puede comprar', justo.estado, 201);
  esProblema(informe, await abrir(pedidoDe(1, mini.id, evento.id)), 409, 'cupo-agotado', R);
  informe.igual('cancelar esa compra', (await cancelar(justo.json.id)).estado, 204);
  informe.igual('y el cupo vuelve a cero', await vendidas(mini.id), 0);

  // Datos inválidos: 400 con el campo que falló en "errores".
  const invalido = async (descripcion, pedido, campo) => {
    const r = await pedido();
    const bien = r.estado === 400 && r.tipo.startsWith('application/problem+json')
      && r.json?.type === '/problemas/datos-invalidos' && r.json.status === 400
      && (campo === undefined || (r.json.errores && Object.keys(r.json.errores).some((c) => c.includes(campo))));
    informe.ok(`400 datos-invalidos: ${descripcion}`, bien, `${r.estado} ${r.tipo} ${r.texto.slice(0, 220)}`);
  };
  await invalido('sin la cabecera Idempotency-Key', () => http('POST', R, { token: T, cuerpo: pedidoDe(1) }), 'Idempotency-Key');
  await invalido('con una clave que no es un UUID', () => abrir(pedidoDe(1), 'compra-1'), 'Idempotency-Key');
  await invalido('sin cuerpo', () => http('POST', R, { token: T, clave: uuid(), crudo: '' }));
  await invalido('sin titulares', () => abrir({ eventoId: EVENTO, items: [{ sectorId: SECTOR, cantidad: 1 }] }), 'titulares');
  await invalido('con cantidad 0', () => abrir({ eventoId: EVENTO, items: [{ sectorId: SECTOR, cantidad: 0 }],
    titulares: [titular('Cantidad Cero')] }), 'cantidad');
  const dniMalo = pedidoDe(1);
  dniMalo.titulares[0].dni = '12AB';
  await invalido('con un DNI que no tiene 7 u 8 dígitos', () => abrir(dniMalo), 'titulares');
  const conSalto = pedidoDe(1);
  conSalto.titulares[0].nombre = 'Rev Linea\n2026-10-13 14:00:00,000 INFO  [com.palco.falso] (falso) LINEA FALSA DE LA INTEGRACION';
  const marca = marcaDelLog();
  await invalido('con un salto de línea en el nombre del titular (inyección en el log)', () => abrir(conSalto), 'titulares');
  await esperar(500);
  informe.igual('la línea falsa no aparece en server.log', lineasCon(marca, 'LINEA FALSA DE LA INTEGRACION').length, 0);
  const pagoInvalido = await abrir(pedidoDe(1));
  await invalido('pagar sin medio de pago', () => http('POST', `${R}/${pagoInvalido.json.id}/pagos`,
    { token: T, clave: uuid(), cuerpo: { token: 'tok_test' } }), 'medioDePago');
  await cancelar(pagoInvalido.json.id);

  const noExiste = await ver('V-00000000');
  esProblema(informe, noExiste, 404, 'no-encontrado', '/api/v1/ventas/V-00000000');
  informe.igual(`ninguna de estas pruebas dejó cupo tomado en ${SECTOR}`, await vendidas(SECTOR), cupoAntes);
}

/** Registra una cuenta nueva y la deja con el email verificado: el código sale del log del servidor. */
async function cuentaNueva() {
  const email = `integracion.${Date.now()}@palco.test`;
  const marca = marcaDelLog();
  const registro = await http('POST', '/api/usuarios/registro', {
    cuerpo: { nombre: 'Bruno', apellido: 'Integracion', dni: String(dniSiguiente++), fechaNacimiento: '1995-05-17',
      email, password: 'palco1234' },
  });
  if (registro.estado !== 201) throw new Error(`registro: ${registro.estado} ${registro.texto}`);
  const linea = await hasta(() => lineasCon(marca, 'Código de verificación para', email)[0]);
  const codigo = linea.match(/: (\d{6})\s*$/)[1];
  const sesion = await http('POST', '/api/usuarios/verificacion', { cuerpo: { email, codigo } });
  if (sesion.estado !== 200) throw new Error(`verificación del email: ${sesion.estado} ${sesion.texto}`);
  return { email, token: sesion.json.token, usuario: sesion.json.usuario };
}

async function escenario5(compra) {
  informe.escenario(5, 'Seguridad: sin token, con el token de otro usuario, con otro rol, y lo interno solo con rol servicio');
  const cupoAntes = await vendidas(SECTOR);
  const deTitular = (await abrir(pedidoDe(1))).json;
  informe.dato(`venta abierta por titular@palco.test: ${deTitular.id}; venta emitida suya: ${compra.ventaId} (entrada ${compra.entradaId})`);
  const codigo = async (metodo, ruta, opciones) => (await http(metodo, ruta, opciones)).estado;
  const pago = { medioDePago: 'tarjeta', token: 'tok_test' };

  // --- Sin token: 401 en todas las operaciones de Ventas.
  const sinToken = [
    await codigo('POST', '/api/v1/ventas', { clave: uuid(), cuerpo: pedidoDe(1) }),
    await codigo('GET', '/api/v1/ventas'),
    await codigo('GET', `/api/v1/ventas/${deTitular.id}`),
    await codigo('POST', `/api/v1/ventas/${deTitular.id}/pagos`, { clave: uuid(), cuerpo: pago }),
    await codigo('DELETE', `/api/v1/ventas/${deTitular.id}`),
    await codigo('GET', `/api/v1/entradas/${compra.entradaId}`),
  ];
  informe.ok('sin token: abrir, listar, ver, pagar, cancelar y ver una entrada responden 401',
    sinToken.every((c) => c === 401), sinToken.join(' '));
  informe.igual('con un token con la firma alterada', await codigo('GET', '/api/v1/ventas', { token: `${T.slice(0, -6)}AAAAAA` }), 401);

  // --- Otro usuario: una cuenta recién registrada. Con el registro completo (email verificado) ya puede
  // comprar: no hay un paso aparte de verificación de identidad.
  const otro = await cuentaNueva();
  informe.dato(`cuenta nueva ${otro.email} (${otro.usuario.id})`);
  const yo = await http('GET', '/api/usuarios/yo', { token: otro.token });
  informe.ok('GET /api/usuarios/yo con el token de la cuenta nueva la devuelve, con rol user',
    yo.estado === 200 && yo.json?.id === otro.usuario.id && yo.json?.email === otro.email
      && (yo.json?.rol === undefined || yo.json.rol === 'user'), `${yo.estado} ${yo.texto.slice(0, 260)}`);
  const suya = await abrir(pedidoDe(1), uuid(), otro.token);
  informe.igual('la cuenta recién registrada abre una compra sin ningún paso más', suya.estado, 201);

  const ajeno = [
    await codigo('GET', `/api/v1/ventas/${deTitular.id}`, { token: otro.token }),
    await codigo('POST', `/api/v1/ventas/${deTitular.id}/pagos`, { token: otro.token, clave: uuid(), cuerpo: pago }),
    await codigo('DELETE', `/api/v1/ventas/${deTitular.id}`, { token: otro.token }),
    await codigo('GET', `/api/v1/ventas/${compra.ventaId}`, { token: otro.token }),
    await codigo('GET', `/api/v1/entradas/${compra.entradaId}`, { token: otro.token }),
  ];
  informe.ok('con el token de otro usuario: ver, pagar y cancelar una venta ajena, y ver una entrada ajena, responden 404',
    ajeno.every((c) => c === 404), ajeno.join(' '));
  const intacta = (await ver(deTitular.id)).json;
  informe.ok('la venta ajena quedó como estaba: EN_CHECKOUT y sin pago',
    intacta.estado === 'EN_CHECKOUT' && intacta.pagoId === null, `${intacta.estado} pagoId=${intacta.pagoId}`);
  const suLista = (await http('GET', '/api/v1/ventas', { token: otro.token })).json;
  informe.ok('su listado trae solo su compra', suLista.length === 1 && suLista[0].id === suya.json.id,
    suLista.map((x) => x.id).join(' '));
  const listaDelTitular = (await http('GET', '/api/v1/ventas', { token: T })).json.map((x) => x.id);
  informe.ok('y el listado del titular no trae la compra del otro, pero sí las suyas',
    !listaDelTitular.includes(suya.json.id) && listaDelTitular.includes(deTitular.id)
      && listaDelTitular.includes(compra.ventaId), `${listaDelTitular.length} ventas`);
  informe.igual('la cuenta nueva cancela su compra', (await cancelar(suya.json.id, otro.token)).estado, 204);
  informe.igual('el titular cancela la suya', (await cancelar(deTitular.id)).estado, 204);

  // --- Un rol que no corresponde.
  for (const [rol, token] of [['organizer', O], ['staff', ST], ['servicio', S]]) {
    const codigos = [
      await codigo('POST', '/api/v1/ventas', { token, clave: uuid(), cuerpo: pedidoDe(1) }),
      await codigo('GET', '/api/v1/ventas', { token }),
      await codigo('GET', `/api/v1/ventas/${compra.ventaId}`, { token }),
      await codigo('GET', `/api/v1/entradas/${compra.entradaId}`, { token }),
    ];
    informe.ok(`rol ${rol}: abrir, listar, ver una venta y ver una entrada responden 403`,
      codigos.every((c) => c === 403), codigos.join(' '));
  }
  informe.igual('rol user: crear un evento (es de organizer)', await codigo('POST', '/api/eventos', { token: T, cuerpo: {} }), 403);
  informe.igual('rol servicio: GET /api/usuarios/yo (no es una persona)', await codigo('GET', '/api/usuarios/yo', { token: S }), 403);
  informe.igual('sin token: GET /api/usuarios/yo', await codigo('GET', '/api/usuarios/yo'), 401);

  // --- Lo interno y lo de prueba: solo con rol servicio.
  const internos = [
    ['POST', '/api/v1/pagos', { clave: uuid(), cuerpo: { ventaId: 'V-prueba', monto: 100, medioDePago: 'tarjeta', token: 'tok_test' } }],
    ['GET', `/api/v1/pagos/${compra.pagoId}`, {}],
    ['POST', `/api/eventos/${EVENTO}/sectores/${SECTOR}/cupo`, { cuerpo: { cantidad: 0 } }],
    ['GET', '/api/notificaciones/auditoria', {}],
    ['POST', '/api/notificaciones/pruebas/pago-confirmado', {}],
    ['POST', '/api/notificaciones/pruebas/pago-confirmado-con-falla', {}],
    ['POST', '/api/notificaciones/pruebas/mensaje-invalido', {}],
  ];
  const brokerAntes = contadores();
  const marcaInternos = marcaDelLog();
  for (const [metodo, ruta, opciones] of internos) {
    const codigos = [
      await codigo(metodo, ruta, opciones),
      await codigo(metodo, ruta, { ...opciones, token: T }),
      await codigo(metodo, ruta, { ...opciones, token: O }),
      await codigo(metodo, ruta, { ...opciones, token: ST }),
    ];
    informe.ok(`${metodo} ${ruta}: 401 sin token y 403 con user, organizer y staff`,
      codigos.join(' ') === '401 403 403 403', codigos.join(' '));
  }
  await esperar(1000);
  const brokerDespues = contadores();
  informe.dato(`broker: ${diferencia(brokerAntes, brokerDespues)}`);
  mensajes('ninguno de esos pedidos rechazados publicó un mensaje en la cola',
    brokerDespues.colaAgregados - brokerAntes.colaAgregados, 0, ajenas(marcaInternos, []));
  const conServicio = [
    await codigo('GET', `/api/v1/pagos/${compra.pagoId}`, { token: S }),
    await codigo('POST', `/api/eventos/${EVENTO}/sectores/${SECTOR}/cupo`, { token: S, cuerpo: { cantidad: 0 } }),
    await codigo('GET', '/api/notificaciones/auditoria', { token: S }),
  ];
  informe.ok('con el token de rol servicio: consultar el pago, tocar el cupo (cantidad 0) y leer auditoría responden 200',
    conServicio.every((c) => c === 200), conServicio.join(' '));
  informe.igual('token de servicio con el secreto incorrecto',
    await codigo('POST', '/api/usuarios/servicios/token', { cuerpo: { secreto: 'no-es-el-secreto' } }), 401);
  informe.igual(`el cupo de ${SECTOR} quedó como al empezar`, await vendidas(SECTOR), cupoAntes);
}

async function escenario6() {
  informe.escenario(6, 'Cancelar una compra en curso libera el cupo');
  const antes = await vendidas(SECTOR);
  const v = (await abrir(pedidoDe(2))).json;
  informe.igual('vendidas con la compra abierta', await vendidas(SECTOR), antes + 2);
  const marca = marcaDelLog();
  informe.igual(`DELETE /api/v1/ventas/${v.id}`, (await cancelar(v.id)).estado, 204);
  informe.igual('vendidas después de cancelar', await vendidas(SECTOR), antes);
  const cancelada = (await ver(v.id)).json;
  informe.ok('la venta queda CANCELADA y sin los enlaces pagar y cancelar',
    cancelada.estado === 'CANCELADA' && !cancelada._links.pagar && !cancelada._links.cancelar,
    `${cancelada.estado} ${Object.keys(cancelada._links).join(',')}`);
  informe.igual('repetir el DELETE', (await cancelar(v.id)).estado, 204);
  informe.igual('el cupo no se devuelve dos veces', await vendidas(SECTOR), antes);
  esProblema(informe, await pagar(v.id), 409, 'reserva-vencida', `/api/v1/ventas/${v.id}/pagos`);
  const lineas = lineasCon(marca, v.id);
  informe.ok('el log de Ventas dice que canceló y devolvió el cupo', lineas.some((l) => l.includes('cancelada por su comprador')),
    lineas.map((l) => l.slice(0, 160)).join(' // '));
  informe.dato('el vencimiento real a los 10 minutos no se repite acá: está en docs/entrega3/evidencia/vencimiento-de-reserva.txt');
}

async function escenario18() {
  informe.escenario(18, 'Con Eventos caído, la devolución del cupo de una compra cancelada se reintenta sola y se aplica una vez');
  const antes = await vendidas(SECTOR);
  const v = (await abrir(pedidoDe(2))).json;
  informe.igual('vendidas con la compra abierta', await vendidas(SECTOR), antes + 2);

  const marca = marcaDelLog();
  informe.ok('se saca de servicio el WAR de Eventos', cli('/deployment=palco-eventos.war:undeploy').includes('success'));
  try {
    informe.igual('Eventos no responde (404 del servidor, no de Eventos)', (await http('GET', '/api/eventos/health')).estado, 404);
    informe.igual(`DELETE /api/v1/ventas/${v.id} con Eventos caído`, (await cancelar(v.id)).estado, 204);
    const aviso = lineasCon(marca, v.id, 'No se pudo devolver el cupo', 'Se reintenta en el próximo barrido');
    informe.ok('Ventas avisa que no pudo devolver el cupo y que lo reintenta (no lo da por rechazado)',
      aviso.length >= 1 && lineasCon(marca, v.id, 'rechazó la devolución').length === 0,
      aviso.map((l) => l.slice(0, 200)).join(' // '));
  } finally {
    informe.ok('se vuelve a desplegar Eventos', cli('/deployment=palco-eventos.war:deploy').includes('success'));
  }
  informe.ok('Eventos responde otra vez',
    await hasta(async () => (await http('GET', '/api/eventos/health')).estado === 200, { maximoMs: 30000, cadaMs: 500 }));

  // El barrido DevolucionDeCupos corre en el segundo 30 de cada minuto.
  const devuelto = await hasta(async () => (await vendidas(SECTOR)) === antes, { maximoMs: 75000, cadaMs: 2000 });
  informe.ok('el barrido devuelve el cupo apenas Eventos vuelve', devuelto, `vendidas ${await vendidas(SECTOR)}, antes ${antes}`);
  const reintentos = lineasCon(marca, 'DevolucionDeCupos', v.id);
  informe.ok('el reintento lo hizo el barrido (DevolucionDeCupos)', reintentos.length >= 1,
    reintentos.map((l) => l.slice(0, 160)).join(' // '));

  // Repetir la misma devolución (misma clave) no resta otra vez: si restara, ese lugar se vendería dos veces.
  const repetida = await http('POST', `/api/eventos/${EVENTO}/sectores/${SECTOR}/cupo`,
    { token: S, cuerpo: { cantidad: -2, clave: `devolucion:${v.id}:${SECTOR}` } });
  informe.igual('repetir la devolución con la misma clave responde 200', repetida.estado, 200);
  informe.igual('y no resta otra vez', await vendidas(SECTOR), antes);
  informe.igual('repetir el DELETE', (await cancelar(v.id)).estado, 204);
  informe.igual('el cupo sigue devuelto una sola vez', await vendidas(SECTOR), antes);
}

async function escenario19() {
  informe.escenario(19, 'Reventa con Pagos sin respuesta: nadie más compra ni retira, y la conciliación libera o transfiere');
  const OT = await login('otro.titular@palco.test');
  const otra = await cuentaNueva();
  const R = '/api/v1/reventas';
  const tope = Math.floor(PRECIO * 1.1);
  const monto = tope + Math.round(tope * 0.1);
  const publicar = async () => {
    const { entradaId } = await compraDeApoyo();
    return { entradaId, pub: (await http('POST', R, { token: T, cuerpo: { entradaId, precio: tope } })).json };
  };
  const comprar = (pubId, token, clave, tokenDePago, nombre = 'Luis Pérez') => http('POST', `${R}/${pubId}/compra`, {
    token, clave, cuerpo: { titular: { nombre, dni: String(dniSiguiente++) }, medioDePago: 'tarjeta', token: tokenDePago },
  });
  const estadoDe = async (pubId) => (await http('GET', `${R}/mias`, { token: T })).json.find((x) => x.id === pubId)?.estado;
  const activa = async (pubId) => (await http('GET', `${R}?eventoId=${EVENTO}`)).json.some((x) => x.id === pubId);

  const A = await publicar();
  const B = await publicar();
  informe.ok('dos publicaciones ACTIVA del titular', A.pub?.estado === 'ACTIVA' && B.pub?.estado === 'ACTIVA',
    `${A.pub?.id} ${B.pub?.id}`);
  const marca = marcaDelLog();

  // A: Pagos no responde y no cobró nada.
  const claveA = uuid();
  esProblema(informe, await comprar(A.pub.id, OT, claveA, 'tok_sin_respuesta'), 504, 'sin-respuesta', `${R}/${A.pub.id}/compra`);
  // B: Pagos no responde, pero el cobro se hizo (se registra a mano con la misma clave, como si Pagos sí hubiera cobrado).
  const claveB = uuid();
  esProblema(informe, await comprar(B.pub.id, OT, claveB, 'tok_sin_respuesta', 'Marta Ríos'), 504, 'sin-respuesta',
    `${R}/${B.pub.id}/compra`);
  const cobroB = await http('POST', '/api/v1/pagos', { token: S, clave: `${B.pub.id}:${claveB}`,
    cuerpo: { ventaId: B.pub.id, monto, medioDePago: 'tarjeta', token: 'tok_test' } });
  informe.ok('en Pagos queda aprobado el cobro de B con la clave de esa compra', cobroB.json?.estado === 'APROBADO',
    `${cobroB.estado} ${cobroB.texto.slice(0, 160)}`);

  // Mientras no se sabe cómo terminó, la toma no vence: ni otra persona la compra ni el vendedor la retira (L1).
  esProblema(informe, await comprar(A.pub.id, otra.token, uuid(), 'tok_test'), 409, 'publicacion-no-activa',
    `${R}/${A.pub.id}/compra`);
  esProblema(informe, await http('DELETE', `${R}/${A.pub.id}`, { token: T }), 409, 'publicacion-no-activa', `${R}/${A.pub.id}`);

  // La conciliación corre en el segundo 45 de cada minuto y mira las tomas de hace más de 2 minutos.
  informe.dato('se espera a la conciliación de reventas (entre 2 y 4 minutos)');
  const resuelta = await hasta(async () => (await activa(A.pub.id)) && (await estadoDe(B.pub.id)) === 'VENDIDA',
    { maximoMs: 260000, cadaMs: 3000 });
  informe.ok('la conciliación resolvió las dos', resuelta, `A activa: ${await activa(A.pub.id)}, B: ${await estadoDe(B.pub.id)}`);

  const lineasA = lineasCon(marca, 'ConciliacionDeReventas', A.pub.id, 'Queda libre');
  informe.ok('A: Pagos no tenía ningún cobro con esa clave y la publicación quedó libre', lineasA.length === 1,
    lineasA.map((l) => l.slice(0, 200)).join(' // '));
  const compraA = await comprar(A.pub.id, otra.token, uuid(), 'tok_test');
  informe.ok('A: ahora otra persona la compra', compraA.estado === 201 && compraA.json.publicacion.estado === 'VENDIDA',
    `${compraA.estado} ${compraA.texto.slice(0, 200)}`);

  const lineasB = lineasCon(marca, 'ConciliacionDeReventas', B.pub.id, 'quedó VENDIDA');
  informe.ok('B: la conciliación vio el pago aprobado, transfirió la entrada y la marcó VENDIDA', lineasB.length === 1,
    lineasB.map((l) => l.slice(0, 220)).join(' // '));
  informe.igual('B: la entrada original quedó VENDIDA', (await entrada(B.entradaId)).json.estado, 'VENDIDA');
  const reintento = await comprar(B.pub.id, OT, claveB, 'tok_test', 'Marta Ríos');
  const nueva = reintento.json?.entrada;
  informe.ok('B: el comprador tiene su entrada a nombre del titular de la compra, sin haber reintentado (el reintento la devuelve igual)',
    reintento.estado === 201 && nueva?.titular?.nombre === 'Marta Ríos' && nueva?.precioPagado === tope,
    `${reintento.estado} ${reintento.texto.slice(0, 220)}`);
  informe.igual('B: la entrada nueva es VALIDA', (await entrada(nueva?.id, OT)).json?.estado, 'VALIDA');
  const cobrosB = lineasCon(marca, B.pub.id, 'vendida a');
  informe.igual('B: no se cobró de nuevo (ninguna compra normal cerró B)', cobrosB.length, 0);
}

// ===================================================================================== mensajería

async function escenario7() {
  informe.escenario(7, 'Al confirmarse un pago sale UN mensaje a la cola y UNO al tópico, con el tipoEvento del contrato');
  const config = cli([
    `${COLA}:read-attribute(name=entries)`, `${TOPICO}:read-attribute(name=entries)`,
    `${TOPICO}:list-all-subscriptions`,
  ]);
  const suscripciones = [...config.matchAll(/"queueName" => "([^"]+)"/g)].map((m) => m[1]);
  const selectores = [...config.matchAll(/"selector" => "([^"]+)"/g)].map((m) => m[1]);
  informe.ok('el tópico tiene tres suscripciones: las durables de auditoría y de facturación, y la del aviso',
    suscripciones.length === COPIAS_DEL_TOPICO && suscripciones.includes(COLA_DE_AUDITORIA)
      && suscripciones.includes(COLA_DE_FACTURACION), suscripciones.join(' , '));
  // Desde el 23/10 el tópico también lleva REVENTA_CONFIRMADA (la compra en la reventa): los tres la reciben.
  informe.ok("las tres filtran por la propiedad tipoEvento: PAGO_CONFIRMADO o REVENTA_CONFIRMADA",
    selectores.length === COPIAS_DEL_TOPICO
      && selectores.every((s) => s === "tipoEvento IN ('PAGO_CONFIRMADO', 'REVENTA_CONFIRMADA')"),
    selectores.join(' | '));
  const noDurable = suscripciones.find((q) => q !== COLA_DE_AUDITORIA && q !== COLA_DE_FACTURACION);
  const avisoAgregados = () => numeros([`${'/subsystem=messaging-activemq/server=default'}/runtime-queue=${noDurable}:read-attribute(name=messages-added)`])[0];

  const v = (await abrir(pedidoDe(2))).json;
  await esperar(1000);
  const avisoAntes = avisoAgregados();
  const antes = contadores();
  const marca = marcaDelLog();
  informe.dato(`antes de pagar ${v.id} (la compra ya está abierta: abrirla no publica):  ${verContadores(antes)}`);
  const pagada = await pagar(v.id);
  informe.igual('pago', pagada.estado, 201);
  const final = await emitida(v.id);
  await esperar(1500);
  const despues = contadores();
  const deOtros = ajenas(marca, [v.id]);
  const avisoDespues = avisoAgregados();
  informe.dato(`después de pagar:  ${verContadores(despues)}`);
  mensajes('cola: mensajes agregados', despues.colaAgregados - antes.colaAgregados, 1, deOtros);
  mensajes(`tópico: copias agregadas, una por cada una de sus ${COPIAS_DEL_TOPICO} suscripciones`,
    despues.topicoAgregados - antes.topicoAgregados, COPIAS_DEL_TOPICO, deOtros, COPIAS_DEL_TOPICO);
  mensajes('suscripción durable (auditoría): recibió', despues.auditoriaAgregados - antes.auditoriaAgregados, 1, deOtros);
  informe.ok('suscripción no durable (aviso): recibió al menos 1', avisoDespues - avisoAntes >= 1, avisoDespues - avisoAntes);
  informe.ok('no quedó nada pendiente ni fue nada a la DLQ',
    despues.colaPendientes === 0 && despues.auditoriaPendientes === 0 && despues.dlq === antes.dlq,
    `cola ${despues.colaPendientes}, auditoría ${despues.auditoriaPendientes}, DLQ +${despues.dlq - antes.dlq}`);

  // El cuerpo: lo que cada consumidor sacó del mensaje coincide con la venta.
  const fila = (await auditoriaDe(v.id))[0];
  informe.ok('la fila de auditoría trae, leídos del cuerpo del mensaje, la venta, el comprador, el evento y el total',
    fila && fila.tipoEvento === 'PAGO_CONFIRMADO' && fila.compradorId === 'user-titular' && fila.eventoId === EVENTO
      && fila.montoTotal === 60500 && fila.mensajeId.startsWith('ID:'), JSON.stringify(fila));
  const publicacion = lineasCon(marca, `Compra ${v.id} confirmada`, 'PAGO_CONFIRMADO publicado en la misma transacción');
  informe.igual('Ventas dejó en el log una sola publicación, dentro de la transacción del pago', publicacion.length, 1);
  const aviso = lineasCon(marca, 'Aviso al comprador user-titular', v.id)[0] ?? '';
  informe.ok('el aviso nombra el sector y a los dos titulares, que viajan en entradas[] del cuerpo',
    aviso.includes('2 entradas: Platea B (Prueba Integracion 1), Platea B (Prueba Integracion 2)') && aviso.includes('$60500'),
    aviso.slice(aviso.indexOf('Aviso')));
  informe.ok('y las entradas emitidas son las que nombraba el mensaje', final?.entradas.length === 2, final?.entradas.map((e) => e.id).join(' '));
}

async function escenario8() {
  informe.escenario(8, 'El consumidor de la cola emite una sola vez aunque el mensaje llegue dos veces');
  informe.dato('cómo se logra la segunda entrega sin tocar código: se detiene auditoría, su copia del mensaje queda');
  informe.dato(`guardada en ${COLA_DE_AUDITORIA}, y con move-messages se la pasa a cola.emision-entradas: es el mismo texto con el mismo tipoEvento.`);
  const nombres = cli(`${'/subsystem=messaging-activemq/server=default'}:read-children-names(child-type=runtime-queue)`);
  const colaDeEmision = [...nombres.matchAll(/"([^"]*cola\.emision-entradas)"/g)].map((m) => m[1])[0];
  informe.ok('nombre interno de la cola de emisión', Boolean(colaDeEmision), colaDeEmision);

  informe.ok('stop-delivery de AuditoriaDeCompras', cli(`${MDB_AUDITORIA}:stop-delivery`).includes('success'));
  try {
    const dlqAntes = contadores().dlq;
    const marca = marcaDelLog();
    const v = (await abrir(pedidoDe(2))).json;
    informe.igual('pago', (await pagar(v.id)).estado, 201);
    const primera = await emitida(v.id);
    const foto = (venta) => venta.entradas.map((e) => `${e.id}=${e.codigoQr}`).join(' ');
    const fechas = async (venta) => (await Promise.all(venta.entradas.map((e) => http('GET', `/api/v1/entradas/${e.id}`, { token: T }))))
      .map((r) => r.json.emitida).join(' ');
    const fotoAntes = foto(primera);
    const fechasAntes = await fechas(primera);
    informe.ok('primera entrega: venta EMITIDA con sus dos códigos', primera?.estado === 'EMITIDA', `${fotoAntes} | emitidas ${fechasAntes}`);
    informe.igual('una emisión en el log', lineasCon(marca, 'EmisionDeEntradas', v.id, 'Entradas emitidas: 2').length, 1);
    await esperar(1000);
    const enEspera = contadores().auditoriaPendientes;
    informe.igual('la copia de auditoría espera en su suscripción durable', enEspera, 1);
    if (enEspera !== 1) {
      // Hay copias de ventas de otra persona: moverlas las dejaría sin auditar. Se corta acá y se repite.
      throw new Error(`En la suscripción durable hay ${enEspera} mensajes: otra persona compró a la vez. Repetir el escenario 8.`);
    }

    const movido = cli(`${SUSCRIPCION_DE_AUDITORIA}:move-messages(other-queue-name=${colaDeEmision})`);
    informe.ok('move-messages: se pasó 1 mensaje a la cola de emisión', /"result" => 1\b/.test(movido), movido.replace(/\s+/g, ' ').trim());
    const repetido = await hasta(() => lineasCon(marca, 'EmisionDeEntradas', v.id, 'es un mensaje repetido')[0]);
    informe.ok('segunda entrega: el consumidor la reconoce como repetida y no cambia nada', Boolean(repetido),
      (repetido ?? '').slice(0, 250));
    await esperar(1000);
    const segunda = (await ver(v.id)).json;
    informe.ok('los códigos de las entradas no cambiaron', foto(segunda) === fotoAntes, foto(segunda));
    informe.igual('las fechas de emisión tampoco', await fechas(segunda), fechasAntes);
    informe.igual('la venta sigue EMITIDA', segunda.estado, 'EMITIDA');
    informe.igual('sigue habiendo una sola emisión en el log', lineasCon(marca, 'EmisionDeEntradas', v.id, 'Entradas emitidas').length, 1);
    const fin = contadores();
    informe.ok('la cola quedó vacía y la DLQ igual: un repetido no es un error',
      fin.colaPendientes === 0 && fin.dlq === dlqAntes, `cola pendientes ${fin.colaPendientes}, DLQ +${fin.dlq - dlqAntes}`);
    informe.dato(`esa venta (${v.id}) no tiene fila de auditoría: su copia se usó para la prueba.`);
  } finally {
    informe.ok('start-delivery de AuditoriaDeCompras', cli(`${MDB_AUDITORIA}:start-delivery`).includes('success'));
  }
}

async function escenario9() {
  informe.escenario(9, 'Los dos suscriptores del tópico reciben cada uno su copia: el aviso (log) y la auditoría (fila)');
  const marca = marcaDelLog();
  const filasAntes = (await http('GET', '/api/notificaciones/auditoria', { token: S })).json.length;
  const v = (await abrir(pedidoDe(1))).json;
  informe.igual('pago', (await pagar(v.id)).estado, 201);
  await emitida(v.id);
  const fila = await hasta(async () => (await auditoriaDe(v.id))[0]);
  informe.ok('auditoría: GET /api/notificaciones/auditoria trae una fila de esa venta', Boolean(fila), JSON.stringify(fila));
  informe.ok('recibida hace instantes, en UTC', fila && Math.abs(Date.now() - Date.parse(fila.recibidoEn)) < 30000, fila?.recibidoEn);
  const total = (await http('GET', '/api/notificaciones/auditoria', { token: S })).json.length;
  informe.ok('el listado sumó al menos una fila (o ya estaba en su tope de 50)', total >= filasAntes + 1 || total === 50, `${filasAntes} -> ${total}`);
  await esperar(500);
  const aviso = lineasCon(marca, 'ServicioDeNotificaciones', 'Aviso al comprador', v.id);
  const avisoMdb = lineasCon(marca, 'AvisoDeCompraMDB', 'aviso por la venta', v.id);
  const auditoriaMdb = lineasCon(marca, 'AuditoriaDeComprasMDB', 'procesado el mensaje de la venta', v.id);
  informe.igual('aviso: una línea con el texto para el comprador', aviso.length, 1);
  informe.igual('aviso: una línea del MDB AvisoDeCompra', avisoMdb.length, 1);
  informe.igual('auditoría: una línea del MDB AuditoriaDeCompras', auditoriaMdb.length, 1);
  for (const l of [...aviso, ...avisoMdb, ...auditoriaMdb]) informe.dato(l.slice(0, 260));
  const hilo = (l) => l.match(/\(([^)]*\([^)]*\))\)|\(([^)]+)\)/)?.[0];
  informe.dato(`hilos: aviso ${hilo(avisoMdb[0] ?? '')}, auditoría ${hilo(auditoriaMdb[0] ?? '')}`);
}

async function escenario10() {
  informe.escenario(10, 'Taller: con el consumidor de emisión detenido el mensaje espera en la cola; al reanudarlo se emite');
  const antes = contadores();
  informe.ok('stop-delivery de EmisionDeEntradas', cli(`${MDB_EMISION}:stop-delivery`).includes('success'));
  try {
    const marca = marcaDelLog();
    const v = (await abrir(pedidoDe(2))).json;
    const pagada = await pagar(v.id);
    informe.igual('el pago responde igual, sin esperar a la emisión', pagada.estado, 201);
    await esperar(4000);
    const detenida = (await ver(v.id)).json;
    informe.ok('4 segundos después la venta sigue CONFIRMADA y sus entradas PENDIENTE_EMISION, sin código',
      detenida.estado === 'CONFIRMADA' && detenida.entradas.every((e) => e.estado === 'PENDIENTE_EMISION' && e.codigoQr === null),
      `${detenida.estado} ${detenida.entradas.map((e) => `${e.estado}/${e.codigoQr}`).join(' ')}`);
    const durante = contadores();
    informe.dato(`con el consumidor detenido:  ${verContadores(durante)}`);
    mensajes('el mensaje espera en la cola: pendientes', durante.colaPendientes - antes.colaPendientes, 1, ajenas(marca, [v.id]));
    informe.igual('la cola no tiene consumidores', durante.colaConsumidores, 0);
    const enCola = cli(`${COLA}:list-messages`);
    informe.ok('el mensaje pendiente lleva la propiedad tipoEvento = PAGO_CONFIRMADO, es de texto (type 3) y persistente',
      /"tipoEvento" => "PAGO_CONFIRMADO"/.test(enCola) && /"JMSDeliveryMode" => true/.test(enCola) && /"type" => 3,/.test(enCola),
      enCola.replace(/\s+/g, ' ').slice(0, 600));
    informe.ok('mientras tanto los suscriptores del tópico sí recibieron su copia (aviso y auditoría)',
      lineasCon(marca, 'AvisoDeCompraMDB', v.id).length === 1 && (await auditoriaDe(v.id)).length === 1,
      `aviso ${lineasCon(marca, 'AvisoDeCompraMDB', v.id).length}, filas ${(await auditoriaDe(v.id)).length}`);
    informe.igual('y todavía no hay ninguna línea de la emisión', lineasCon(marca, 'EmisionDeEntradas', v.id).length, 0);

    informe.ok('start-delivery de EmisionDeEntradas', cli(`${MDB_EMISION}:start-delivery`).includes('success'));
    const final = await emitida(v.id);
    informe.ok('al reanudarlo, la venta pasa a EMITIDA y las entradas a VALIDA con su código',
      final?.estado === 'EMITIDA' && final.entradas.every((e) => e.estado === 'VALIDA' && e.codigoQr),
      `${final?.estado} ${final?.entradas.map((e) => e.estado).join(' ')}`);
    await esperar(1000);
    const fin = contadores();
    informe.dato(`después de reanudar:  ${verContadores(fin)}`);
    informe.ok('la cola quedó sin pendientes y otra vez con consumidores', fin.colaPendientes === 0 && fin.colaConsumidores > 0,
      `pendientes ${fin.colaPendientes}, consumidores ${fin.colaConsumidores}`);
    informe.igual('una sola emisión en el log', lineasCon(marca, 'EmisionDeEntradas', v.id, 'Entradas emitidas: 2').length, 1);
  } finally {
    cli(`${MDB_EMISION}:start-delivery`);
  }
}

async function escenario11() {
  informe.escenario(11, 'Suscripción durable: con auditoría detenida se publican mensajes y al reanudarla los recibe');
  informe.ok('stop-delivery de AuditoriaDeCompras', cli(`${MDB_AUDITORIA}:stop-delivery`).includes('success'));
  try {
    const marca = marcaDelLog();
    const antes = contadores();
    const v = (await abrir(pedidoDe(1))).json;
    informe.igual('pago de una compra real', (await pagar(v.id)).estado, 201);
    const prueba = await http('POST', '/api/notificaciones/pruebas/pago-confirmado', { token: S });
    informe.igual('y un mensaje de prueba', prueba.estado, 202);
    await emitida(v.id);
    await esperar(2500);
    const durante = contadores();
    mensajes('los dos mensajes esperan en la suscripción durable',
      durante.auditoriaPendientes - antes.auditoriaPendientes, 2, ajenas(marca, [v.id]));
    informe.igual('auditoría no tiene filas de ninguno de los dos',
      (await auditoriaDe(v.id)).length + (await auditoriaDe(prueba.json.ventaId)).length, 0);
    informe.igual('el aviso (no durable) sí recibió los dos',
      lineasCon(marca, 'AvisoDeCompraMDB', 'aviso por la venta').filter((l) => l.includes(v.id) || l.includes(prueba.json.ventaId)).length, 2);
    informe.igual('la emisión no depende de auditoría: la venta ya está EMITIDA', (await ver(v.id)).json.estado, 'EMITIDA');

    informe.ok('start-delivery de AuditoriaDeCompras', cli(`${MDB_AUDITORIA}:start-delivery`).includes('success'));
    const filas = await hasta(async () => {
      const dos = [...await auditoriaDe(v.id), ...await auditoriaDe(prueba.json.ventaId)];
      return dos.length === 2 ? dos : null;
    });
    informe.ok('al reanudarla, auditoría registra los dos mensajes que se había perdido', Boolean(filas),
      (filas ?? []).map((f) => `${f.ventaId} ${f.recibidoEn}`).join(' | '));
    await esperar(1000);
    informe.igual('la suscripción durable quedó sin pendientes', contadores().auditoriaPendientes, 0);
  } finally {
    cli(`${MDB_AUDITORIA}:start-delivery`);
  }
}

async function escenario12() {
  informe.escenario(12, 'Un mensaje inválido se reintenta y termina en la DLQ sin trabar a los demás');
  const antes = contadores();
  const marca = marcaDelLog();
  const invalido = await http('POST', '/api/notificaciones/pruebas/mensaje-invalido', { token: S });
  informe.igual('POST /api/notificaciones/pruebas/mensaje-invalido', invalido.estado, 202);

  // Mientras auditoría reintenta el inválido (5 entregas, una cada 2 segundos), una compra tiene que pasar.
  await esperar(1500);
  const v = (await abrir(pedidoDe(1))).json;
  informe.igual('pago de una compra hecha durante los reintentos', (await pagar(v.id)).estado, 201);
  const final = await emitida(v.id);
  const fila = await hasta(async () => (await auditoriaDe(v.id))[0], { maximoMs: 4000 });
  const entregasEnEseMomento = lineasCon(marca, 'AuditoriaDeCompras: mensaje inválido').length;
  informe.ok('esa compra se emite y se audita enseguida, sin esperar a que terminen los reintentos',
    final?.estado === 'EMITIDA' && Boolean(fila) && entregasEnEseMomento < 5,
    `${final?.estado}, fila ${fila ? 'sí' : 'no'}, el inválido llevaba ${entregasEnEseMomento} entregas`);

  const pase = await hasta(() => lineasCon(marca, 'AMQ222149')[0], { maximoMs: 20000 });
  const avisos = lineasCon(marca, 'AuditoriaDeCompras: mensaje inválido');
  informe.igual('auditoría lo devolvió al broker 5 veces', avisos.length, 5);
  informe.igual('numeradas de la entrega 1 a la 5', avisos.map((l) => l.match(/entrega (\d)/)[1]).join(' '), '1 2 3 4 5');
  const segundos = avisos.map((l) => { const [h, m, s] = l.slice(11, 23).replace(',', '.').split(':').map(Number); return h * 3600 + m * 60 + s; });
  const pausas = segundos.slice(1).map((s, i) => (s - segundos[i]).toFixed(1));
  informe.ok('con unos 2 segundos entre una y otra', pausas.every((p) => p >= 1.8 && p <= 3.5), pausas.join(' '));
  informe.ok('el broker lo pasó a la DLQ (AMQ222149)', Boolean(pase), (pase ?? '').slice(0, 200));
  informe.igual('el aviso (no durable) lo descartó una sola vez, sin reintentar',
    lineasCon(marca, 'AvisoDeCompra: se descarta un mensaje inválido').length, 1);
  for (const l of avisos) informe.dato(l.slice(0, 190));
  await esperar(1000);
  const despues = contadores();
  // Una copia por cada suscriptor durable que lo reintenta: auditoría y facturación.
  informe.igual('DLQ, una copia por suscriptor durable (auditoría y facturación)', despues.dlq - antes.dlq, 2);
  informe.ok('nada pendiente en la suscripción de auditoría ni en la cola',
    despues.auditoriaPendientes === 0 && despues.colaPendientes === 0, `${despues.auditoriaPendientes} ${despues.colaPendientes}`);

  // El mismo texto, ahora en la cola de emisión: se lo trae de la DLQ con move-messages.
  // En el recurso jms-queue el destino va con su nombre JMS, sin el prefijo interno "jms.queue.".
  const marca2 = marcaDelLog();
  const movido = cli(`${DLQ}:move-messages(other-queue-name=cola.emision-entradas)`);
  const cuantos = Number(movido.match(/"result" => (\d+)/)?.[1] ?? -1);
  informe.ok('move-messages de la DLQ a la cola de emisión', cuantos >= 1, `${cuantos} mensaje(s)`);
  const v2 = (await abrir(pedidoDe(1))).json;
  informe.igual('pago de otra compra, con el inválido reintentándose en la cola', (await pagar(v2.id)).estado, 201);
  informe.igual('esa compra se emite igual', (await emitida(v2.id))?.estado, 'EMITIDA');
  await hasta(() => lineasCon(marca2, 'AMQ222149').length >= cuantos, { maximoMs: 20000 });
  const avisosDeEmision = lineasCon(marca2, 'EmisionDeEntradas: mensaje inválido');
  informe.igual('el consumidor de la cola también lo devuelve 5 veces por mensaje', avisosDeEmision.length, 5 * cuantos);
  await esperar(1000);
  const fin = contadores();
  informe.ok('y vuelve a la DLQ: la cola queda vacía', fin.colaPendientes === 0 && fin.dlq === despues.dlq,
    `cola pendientes ${fin.colaPendientes}, DLQ ${fin.dlq} (antes de moverlo ${despues.dlq})`);
}

async function escenario13() {
  informe.escenario(13, 'Si la transacción se deshace después de publicar, el mensaje no sale');
  const antes = contadores();
  const marca = marcaDelLog();
  const filasAntes = (await http('GET', '/api/notificaciones/auditoria', { token: S })).json.map((f) => f.mensajeId);
  const r = await http('POST', '/api/notificaciones/pruebas/pago-confirmado-con-falla', { token: S });
  esProblema(informe, r, 409, 'conflicto', '/api/notificaciones/pruebas/pago-confirmado-con-falla');
  informe.dato(`cuerpo del 409: ${r.texto}`);
  await esperar(3000);
  const despues = contadores();
  const deOtros = ajenas(marca, []);
  informe.dato(`broker: ${diferencia(antes, despues)}`);
  mensajes('cola', despues.colaAgregados - antes.colaAgregados, 0, deOtros);
  mensajes('tópico', despues.topicoAgregados - antes.topicoAgregados, 0, deOtros, COPIAS_DEL_TOPICO);
  mensajes('suscripción durable', despues.auditoriaAgregados - antes.auditoriaAgregados, 0, deOtros);
  informe.igual('DLQ', despues.dlq - antes.dlq, 0);
  informe.igual('ninguna línea nueva de los tres consumidores (salvo las de ventas ajenas)',
    logDesde(marca).filter((l) => /EmisionDeEntradas|AvisoDeCompra|AuditoriaDeCompras|Aviso al comprador/.test(l))
      .filter((l) => !deOtros.some((id) => l.includes(id))).length, 0);
  const filasDespues = (await http('GET', '/api/notificaciones/auditoria', { token: S })).json;
  informe.igual('ninguna fila nueva en auditoría (salvo las de ventas ajenas)',
    filasDespues.filter((f) => !filasAntes.includes(f.mensajeId) && !deOtros.includes(f.ventaId)).length, 0);

  // El control: el mismo endpoint sin la falla sí publica.
  const marca2 = marcaDelLog();
  const control = await http('POST', '/api/notificaciones/pruebas/pago-confirmado', { token: S });
  informe.igual('control: el mismo mensaje sin la falla (pruebas/pago-confirmado)', control.estado, 202);
  await hasta(async () => (await auditoriaDe(control.json.ventaId)).length === 1);
  await esperar(1000);
  const conControl = contadores();
  const deOtrosEnElControl = ajenas(marca2, []);
  informe.dato(`broker con el control: ${diferencia(despues, conControl)}`);
  mensajes('ese sí sale a la cola', conControl.colaAgregados - despues.colaAgregados, 1, deOtrosEnElControl);
  mensajes('y al tópico, una copia por suscripción', conControl.topicoAgregados - despues.topicoAgregados, COPIAS_DEL_TOPICO,
    deOtrosEnElControl, COPIAS_DEL_TOPICO);
  const descarte = lineasCon(marca2, 'EmisionDeEntradas: se descarta el mensaje de la venta', control.json.ventaId);
  informe.igual('y la emisión descarta ese mensaje de prueba, porque su venta no existe', descarte.length, 1);
}

// ===================================================================================== conciliación, factura, puerta y reventa

/** La herramienta de prueba de Ventas: con la propiedad en true, el pago falla después del cobro aprobado. */
const PROPIEDAD_FALLA = 'palco.ventas.fallar-despues-del-cobro';
const verPago = (clave) => http('GET', `/api/v1/pagos?claveIdempotencia=${encodeURIComponent(clave)}`, { token: S });
const facturaDe = (ventaId, token = T) => http('GET', `/api/v1/facturas/${ventaId}`, { token });
const escanear = (codigoQr, eventoId = EVENTO) => http('POST', '/api/v1/validacion/escaneos',
  { token: ST, cuerpo: { codigoQr, eventoId } });
const entrada = (entradaId, token = T) => http('GET', `/api/v1/entradas/${entradaId}`, { token });

async function escenario14() {
  informe.escenario(14, 'Falla después del cobro: se deshace la confirmación, el pago queda en Pagos y la conciliación'
    + ' termina la compra');
  const v = (await abrir(pedidoDe(1))).json;
  const cupo = await vendidas(SECTOR);
  const antes = contadores();
  const marca = marcaDelLog();
  const clave = uuid();

  informe.ok(`jboss-cli: ${PROPIEDAD_FALLA}=true`,
    cli(`/system-property=${PROPIEDAD_FALLA}:add(value=true)`).includes('success'));
  let pago;
  try {
    pago = await pagar(v.id, 'tok_test', clave);
  } finally {
    informe.ok('jboss-cli: se quita la propiedad', cli(`/system-property=${PROPIEDAD_FALLA}:remove`).includes('success'));
  }
  esProblema(informe, pago, 500, 'error-inesperado', `/api/v1/ventas/${v.id}/pagos`);
  informe.ok('el 500 trae idError y nada interno', typeof pago.json?.idError === 'string'
    && !/Exception|\bat com\.|IllegalState/.test(pago.texto), pago.texto.slice(0, 260));
  informe.igual('la falla de prueba quedó en el log', lineasCon(marca, PROPIEDAD_FALLA, v.id).length, 1);

  await esperar(1500);
  const sigue = (await ver(v.id)).json;
  informe.ok('la venta NO quedó CONFIRMADA: sigue EN_CHECKOUT, sin pago ni entradas',
    sigue?.estado === 'EN_CHECKOUT' && sigue.pagoId === null && sigue.entradas.length === 0,
    `${sigue?.estado} pagoId=${sigue?.pagoId} entradas=${sigue?.entradas.length}`);
  const enPagos = await hasta(async () => {
    for (const c of [`${v.id}:${clave}`, clave]) {
      const r = await verPago(c);
      if (r.estado === 200) return r.json;
    }
    return null;
  }, { maximoMs: 3000 });
  informe.ok('en Pagos el cobro sí quedó guardado, APROBADO (GET /api/v1/pagos?claveIdempotencia=)',
    enPagos?.estado === 'APROBADO' && enPagos.ventaId === v.id, JSON.stringify(enPagos));
  const tras = contadores();
  const deOtros = ajenas(marca, [v.id]);
  informe.dato(`broker tras la falla: ${diferencia(antes, tras)}`);
  mensajes('ningún mensaje a la cola', tras.colaAgregados - antes.colaAgregados, 0, deOtros);
  mensajes('ninguno al tópico', tras.topicoAgregados - antes.topicoAgregados, 0, deOtros, COPIAS_DEL_TOPICO);
  informe.igual('ninguna fila de auditoría de esa venta', (await auditoriaDe(v.id)).length, 0);
  informe.igual('el cupo sigue reservado: no se libera algo que pudo cobrarse', await vendidas(SECTOR), cupo);

  // La conciliación corre una vez por minuto (en el segundo 30) y toma las ventas cuyo intento de pago
  // empezó hace más de 2 minutos: puede tardar hasta unos 3 minutos y medio.
  informe.dato(`${hora()} se espera a la conciliación (hasta 4 minutos y medio)`);
  const conciliada = await hasta(async () => {
    const r = await ver(v.id);
    return r.json?.estado === 'EMITIDA' ? r.json : null;
  }, { maximoMs: 270000, cadaMs: 5000 });
  informe.dato(`${hora()} fin de la espera`);
  informe.ok('la conciliación la confirmó con ESE pago y la entrada se emitió',
    conciliada?.pagoId === enPagos?.pagoId && conciliada.entradas.length === 1
      && conciliada.entradas.every((e) => e.estado === 'VALIDA' && e.codigoQr),
    `${conciliada?.estado} ${conciliada?.pagoId} ${conciliada?.entradas.map((e) => e.estado).join(' ')}`);
  informe.igual('la conciliación lo dejó en el log',
    lineasCon(marca, 'Conciliación: la venta', v.id, 'quedó confirmada').length, 1);
  await esperar(1500);
  const fin = contadores();
  const deOtrosAlFinal = ajenas(marca, [v.id]);
  informe.dato(`broker con la conciliación: ${diferencia(antes, fin)}`);
  mensajes('ahora sí, un mensaje a la cola', fin.colaAgregados - antes.colaAgregados, 1, deOtrosAlFinal);
  mensajes('y una copia por suscripción del tópico', fin.topicoAgregados - antes.topicoAgregados,
    COPIAS_DEL_TOPICO, deOtrosAlFinal, COPIAS_DEL_TOPICO);
  informe.igual('una fila de auditoría de esa venta', (await auditoriaDe(v.id)).length, 1);
  informe.igual('el cupo no cambió', await vendidas(SECTOR), cupo);
  informe.igual('un solo pago en Pagos para esa venta (líneas de ServicioDePagos en el log)',
    lineasCon(marca, 'ServicioDePagos', `de la venta ${v.id}`).length, 1);
}

async function escenario15(compraHecha) {
  informe.escenario(15, 'Facturación: la compra confirmada se factura una sola vez, con número y CAE de ARCA (simulado)');
  const { ventaId } = compraHecha;
  const monto = (await ver(ventaId)).json.montoTotal;
  const f = (await hasta(async () => {
    const r = await facturaDe(ventaId);
    return r.estado === 200 ? r : null;
  }, { maximoMs: 30000, cadaMs: 1000 }))?.json;
  informe.ok('GET /api/v1/facturas/{ventaId} la trae (la factura es asincrónica: se reintenta hasta 30 s)',
    Boolean(f), JSON.stringify(f));
  informe.ok('factura B (6) de esa venta, por el total cobrado',
    f?.ventaId === ventaId && f.tipoComprobante === 6 && f.importe === monto, `${f?.tipoComprobante} ${f?.importe} ${monto}`);
  informe.ok('con número impreso y CAE de 14 dígitos',
    /^\d{5}-\d{8}$/.test(f?.comprobante) && /^\d{14}$/.test(f?.cae) && /^\d{4}-\d{2}-\d{2}$/.test(f?.vencimientoCae),
    `${f?.comprobante} CAE ${f?.cae} vence ${f?.vencimientoCae}`);
  const otra = (await facturaDe(ventaId, S)).json;
  informe.ok('consultada de nuevo (rol servicio) es la misma: mismo número y mismo CAE',
    otra?.comprobante === f?.comprobante && otra?.cae === f?.cae, `${otra?.comprobante} ${otra?.cae}`);
  esProblema(informe, await facturaDe(ventaId, ST), 403, 'sin-permiso', `/api/v1/facturas/${ventaId}`);
  esProblema(informe, await facturaDe('V-00000000'), 404, 'no-encontrado', '/api/v1/facturas/V-00000000');

  // Una compra nueva: su factura sale con el número siguiente del talonario y una sola vez.
  const marca2 = marcaDelLog();
  const v = (await abrir(pedidoDe(1))).json;
  informe.igual('pago de otra compra', (await pagar(v.id)).estado, 201);
  const f2 = (await hasta(async () => {
    const r = await facturaDe(v.id);
    return r.estado === 200 ? r : null;
  }, { maximoMs: 30000, cadaMs: 1000 }))?.json;
  informe.ok('la factura de la compra nueva tiene un número mayor y otro CAE',
    f2 && f2.numero > f.numero && f2.cae !== f.cae, `${f?.comprobante} -> ${f2?.comprobante}`);
  await esperar(2000);
  const emitidas = lineasCon(marca2, 'ServicioDeFacturacion', `de la venta ${v.id}`, 'CAE');
  informe.igual('una sola factura emitida para esa venta (líneas de ServicioDeFacturacion en el log)', emitidas.length, 1);
  informe.igual('y ningún aviso de mensaje repetido', lineasCon(marca2, v.id, 'ya tenía factura').length, 0);
  informe.dato(`log: ${(emitidas[0] ?? '').slice(0, 200)}`);
}

async function escenario16() {
  informe.escenario(16, 'Validación en puerta: el primer escaneo deja entrar y marca la entrada USADA; el segundo, no');
  const { entradaId } = await compraDeApoyo();
  const e = (await entrada(entradaId)).json;
  informe.ok('la entrada está VALIDA y con código', e?.estado === 'VALIDA' && e.codigoQr, `${e?.estado} ${e?.codigoQr}`);

  const otroEvento = await escanear(e.codigoQr, 'ev-1');
  informe.ok('en la puerta de otro evento: 201 OTRO_EVENTO, no entra',
    otroEvento.estado === 201 && otroEvento.json.resultado === 'OTRO_EVENTO' && otroEvento.json.puedeEntrar === false,
    `${otroEvento.estado} ${otroEvento.texto.slice(0, 200)}`);
  informe.igual('y la entrada sigue VALIDA', (await entrada(entradaId)).json.estado, 'VALIDA');

  const primero = await escanear(e.codigoQr);
  const p = primero.json;
  informe.ok('primer escaneo: 201 VALIDA, puede entrar, con la entrada y su titular',
    primero.estado === 201 && p.resultado === 'VALIDA' && p.puedeEntrar === true && p.entrada?.id === entradaId
      && p.entrada.titular?.dni === e.titular.dni, `${primero.estado} ${primero.texto.slice(0, 300)}`);
  informe.ok('Location del escaneo', primero.location?.endsWith(`/api/v1/validacion/escaneos/${p.id}`), primero.location);
  informe.igual('en Ventas la entrada quedó USADA', (await entrada(entradaId)).json.estado, 'USADA');

  const segundo = await escanear(e.codigoQr);
  const s = segundo.json;
  informe.ok('segundo escaneo: 201 YA_USADA, no entra, y dice cuál fue el uso anterior',
    segundo.estado === 201 && s.resultado === 'YA_USADA' && s.puedeEntrar === false
      && s.usoAnterior?.escaneoId === p.id && s.usoAnterior.resultado === 'VALIDA',
    `${segundo.estado} ${segundo.texto.slice(0, 300)}`);
  const inexistente = await escanear('no-es-un-codigo-de-palco');
  informe.ok('un código que no existe: 201 INEXISTENTE', inexistente.json?.resultado === 'INEXISTENTE', inexistente.texto.slice(0, 160));
  esProblema(informe, await http('POST', '/api/v1/validacion/escaneos',
    { token: T, cuerpo: { codigoQr: e.codigoQr, eventoId: EVENTO } }), 403, 'sin-permiso', '/api/v1/validacion/escaneos');

  const registro = await http('GET', `/api/v1/validacion/escaneos?eventoId=${EVENTO}`, { token: O });
  // El escaneo repetido no lleva la entrada: la referencia al uso anterior va en usoAnterior. Se buscan los dos por id.
  const deEstaEntrada = (registro.json ?? []).filter((x) => x.id === p.id || x.id === s.id).map((x) => x.resultado);
  informe.ok('el registro de la puerta (rol organizer) tiene VALIDA y YA_USADA, del más nuevo al más viejo',
    registro.estado === 200 && deEstaEntrada.join(' ') === 'YA_USADA VALIDA', deEstaEntrada.join(' '));
  informe.ok('y no muestra el código completo', !registro.texto.includes(e.codigoQr), '');
}

async function escenario17() {
  informe.escenario(17, 'Reventa de punta a punta: publicar dentro del rango, la compra de otra cuenta, la entrada nueva y su REVENTA_CONFIRMADA');
  const OT = await login('otro.titular@palco.test');
  const { entradaId } = await compraDeApoyo();
  const original = (await entrada(entradaId)).json;
  const tope = Math.floor(PRECIO * 1.1);
  const publicar = (precio, id = entradaId) => http('POST', '/api/v1/reventas', { token: T, cuerpo: { entradaId: id, precio } });
  const R = '/api/v1/reventas';

  esProblema(informe, await publicar(tope + 1), 422, 'precio-fuera-de-rango', R);
  esProblema(informe, await publicar(PRECIO / 2 - 1), 422, 'precio-fuera-de-rango', R);
  esProblema(informe, await publicar(tope, 'T-00000000'), 404, 'no-encontrado', R);
  const hecha = await publicar(tope);
  const pub = hecha.json;
  informe.ok(`publicar al tope exacto (${tope}): 201 ACTIVA, con su precioMaximo`,
    hecha.estado === 201 && pub.estado === 'ACTIVA' && pub.precio === tope && pub.precioMaximo === tope
      && pub.entradaId === entradaId && hecha.location?.endsWith(`${R}/${pub.id}`),
    `${hecha.estado} ${hecha.texto.slice(0, 300)}`);
  esProblema(informe, await publicar(tope), 409, 'entrada-no-disponible', R);
  informe.igual('en Ventas la entrada quedó PUBLICADA', (await entrada(entradaId)).json.estado, 'PUBLICADA');
  const lista = await http('GET', `${R}?eventoId=${EVENTO}`);
  informe.ok('GET /api/v1/reventas?eventoId= (sin token) la lista', lista.json?.some((x) => x.id === pub.id), lista.estado);
  const puerta = await escanear(original.codigoQr);
  informe.ok('publicada no sirve para entrar: NO_HABILITADA', puerta.json?.resultado === 'NO_HABILITADA', puerta.texto.slice(0, 160));

  const comprar = (token, clave, tokenDePago = 'tok_test') => http('POST', `${R}/${pub.id}/compra`, {
    token, clave, cuerpo: { titular: { nombre: 'Luis Pérez', dni: String(dniSiguiente++) }, medioDePago: 'tarjeta', token: tokenDePago },
  });
  esProblema(informe, await comprar(T, uuid()), 409, 'compra-propia', `${R}/${pub.id}/compra`);
  esProblema(informe, await comprar(OT, uuid(), 'tok_rechazado'), 402, 'pago-rechazado', `${R}/${pub.id}/compra`);
  const clave = uuid();
  const marcaCompra = marcaDelLog();
  const compraR = await comprar(OT, clave);
  const c = compraR.json;
  informe.ok('otra cuenta la compra: 201 con la publicación VENDIDA, la entrada nueva y su venta',
    compraR.estado === 201 && c.publicacion.estado === 'VENDIDA' && c.entrada.id !== entradaId
      && c.entrada.precioPagado === tope && c.entrada.codigoQr && /^V-[0-9a-f]{8}$/.test(c.ventaId),
    `${compraR.estado} ${compraR.texto.slice(0, 300)}`);
  const repetida = await comprar(OT, clave);
  informe.ok('repetida con la misma clave: la misma entrada, sin cobrar de nuevo',
    repetida.estado === 201 && repetida.json.entrada.id === c.entrada.id, `${repetida.estado} ${repetida.json?.entrada?.id}`);
  esProblema(informe, await comprar(OT, uuid()), 409, 'publicacion-no-activa', `${R}/${pub.id}/compra`);

  informe.igual('la entrada original quedó VENDIDA', (await entrada(entradaId)).json.estado, 'VENDIDA');
  const nueva = (await entrada(c.entrada.id, OT)).json;
  informe.ok('la nueva es del comprador: VALIDA, a nombre del titular indicado',
    nueva?.estado === 'VALIDA' && nueva.titular.nombre === 'Luis Pérez' && nueva.ventaId === c.ventaId, JSON.stringify(nueva));
  esProblema(informe, await entrada(c.entrada.id, T), 404, 'no-encontrado', `/api/v1/entradas/${c.entrada.id}`);
  informe.ok('ya no figura entre las activas', !(await http('GET', `${R}?eventoId=${EVENTO}`)).json.some((x) => x.id === pub.id), '');
  const mias = (await http('GET', `${R}/mias`, { token: T })).json;
  informe.igual('en "mis publicaciones" del vendedor figura VENDIDA', mias.find((x) => x.id === pub.id)?.estado, 'VENDIDA');
  informe.igual('el código viejo no entra: NO_HABILITADA', (await escanear(original.codigoQr)).json?.resultado, 'NO_HABILITADA');
  informe.igual('el nuevo sí: VALIDA', (await escanear(c.entrada.codigoQr)).json?.resultado, 'VALIDA');

  // La compra en la reventa publica REVENTA_CONFIRMADA en el tópico (L13): aviso, auditoría y factura.
  const filas = await hasta(async () => {
    const f = await auditoriaDe(c.ventaId);
    return f.length > 0 ? f : null;
  });
  informe.ok('la auditoría registró la venta nueva una vez, como REVENTA_CONFIRMADA',
    filas?.length === 1 && filas[0].tipoEvento === 'REVENTA_CONFIRMADA', JSON.stringify(filas));
  const aviso = await hasta(() => lineasCon(marcaCompra, 'en la reventa oficial', c.ventaId)[0]);
  informe.ok('el aviso al comprador dice que fue en la reventa oficial', Boolean(aviso), (aviso ?? '').slice(0, 200));
  const factura = (await hasta(async () => {
    const r = await facturaDe(c.ventaId, S);
    return r.estado === 200 ? r : null;
  }, { maximoMs: 30000, cadaMs: 1000 }))?.json;
  informe.ok('la venta nueva tiene su factura, por el precio de reventa más el cargo de servicio',
    factura?.ventaId === c.ventaId && factura.importe === tope + Math.round(tope * 0.1) && /^\d{14}$/.test(factura.cae),
    JSON.stringify(factura));
  informe.igual('no fue a la cola de emisión (la entrada ya estaba emitida)',
    lineasCon(marcaCompra, 'EmisionDeEntradas', c.ventaId).length, 0);
}

// ===================================================================================== corrida

const servicios = ['api/usuarios', 'api/eventos', 'api/v1', 'api/v1/pagos', 'api/v1/validacion', 'api/v1/reventas',
  'api/notificaciones', 'api/v1/facturas'];
const health = [];
for (const s of servicios) health.push(`${s}/health ${(await http('GET', `/${s}/health`)).estado}`);
console.log(`Prueba de integración del backend de Palco. ${new Date().toLocaleString('es-AR', { hour12: false })}`);
console.log(`Servicios: ${health.join(' | ')}`);

T = await login('titular@palco.test');
O = await login('organizador@palco.test');
ST = await login('puerta@palco.test');
S = await tokenDeServicio();
const inicial = contadores();
console.log(`Broker al empezar:  ${verContadores(inicial)}`);
const propio = await eventoDePrueba();
EVENTO = propio.id;
[{ id: SECTOR }, MINI, GRANDE] = propio.sectores;
console.log(`Evento de la prueba: ${EVENTO} (${propio.titulo}). Sector de las compras: ${JSON.stringify(await sector(SECTOR))}`);

let compra = null;
const pasos = [
  [1, async () => { compra = await escenario1(); }],
  [2, escenario2], [3, escenario3], [4, escenario4],
  [5, async () => { compra ??= await compraDeApoyo(); await escenario5(compra); }],
  [6, escenario6], [7, escenario7], [8, escenario8], [9, escenario9], [10, escenario10], [11, escenario11],
  [12, escenario12], [13, escenario13], [14, escenario14],
  [15, async () => { compra ??= await compraDeApoyo(); await escenario15(compra); }],
  [16, escenario16], [17, escenario17], [18, escenario18], [19, escenario19],
];

/** Si el escenario 5 o el 15 corren solos, necesitan una venta emitida del titular. También la usan el 16 y el 17. */
async function compraDeApoyo() {
  const v = (await abrir(pedidoDe(1))).json;
  const p = (await pagar(v.id)).json;
  const final = await emitida(v.id);
  return { ventaId: v.id, pagoId: p.pagoId, entradaId: final.entradas[0].id };
}

for (const [numero, paso] of pasos) {
  if (!corre(numero)) continue;
  try {
    // El token de servicio dura 5 minutos y la corrida completa tarda más (el escenario 14 espera a la
    // conciliación): se pide uno nuevo antes de cada escenario.
    S = await tokenDeServicio();
    await paso();
  } catch (e) {
    informe.ok('el escenario terminó sin errores del guion', false, e.stack ?? String(e));
  }
}

console.log(`\nBroker al terminar: ${verContadores(contadores())}`);
const fallas = informe.resumen();
console.log(fallas === 0 ? '\nTODO BIEN' : `\nHAY ${fallas} COMPROBACIONES QUE FALLAN`);
process.exit(fallas === 0 ? 0 : 1);
