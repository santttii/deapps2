// Pruebas de seguridad contra el sistema en marcha: lo que puede intentar alguien con una cuenta común y un
// cliente HTTP, sin acceso al servidor. Cuatro bloques:
//
//   A. Tokens JWT manipulados: vencido, de otro emisor, con alg none, con la firma alterada, con un rol
//      agregado sin volver a firmar, firmado con otra clave y con HS256 usando la clave pública como secreto.
//      Cada uno se prueba en cinco servicios (cada WAR verifica el token por su cuenta): todos 401.
//   B. Mass assignment: al crear y editar un evento, al abrir una compra y al publicar en la reventa se mandan
//      campos que el cliente no elige (dueño, estado, montos, vendidas, tope). La respuesta es 2xx o 400 y,
//      leyendo el recurso después, esos campos quedaron como los fija el servidor.
//   C. XSS almacenado: un evento con <script> y <img onerror> en el título y la descripción se guarda y se
//      devuelve como texto (o se rechaza con 400). Cómo lo muestra el navegador lo prueba frontend/e2e/xss.spec.ts.
//   D. XXE en el canal SOAP: sobres con DOCTYPE y entidades (externas, de parámetro e internas) a /legado/arca
//      se rechazan sin expandirlas, y el ARCA simulado no autoriza nada con ellos.
//
//   node scripts/pruebas/seguridad.mjs
//
// Sale con 0 si pasan todas las comprobaciones y con 1 si alguna falla. Solo usa módulos que trae Node: los
// tokens se arman y se firman con node:crypto. Deja en la base dos eventos de prueba (uno con el HTML en el
// título), una compra emitida de titular@palco.test y una publicación de reventa retirada.
//
// Variables de entorno:
//   PALCO_URL                 dirección del backend                                 (http://localhost:8080)
//   PALCO_JWT_CLAVE_PRIVADA   la clave con la que el servidor firma los tokens: PKCS#8 en base64, la misma
//                             variable que lee el backend (docker-compose.yml). Sin ella fallan el token
//                             vencido y el de otro emisor (ver el bloque A).
//   PALCO_SECRETO_SERVICIOS   el secreto entre servicios: el dueño de un evento solo lo lee el rol servicio
//   PALCO_LOG                 el server.log, para ver que el ARCA simulado no autorizó nada en el bloque D
import crypto from 'node:crypto';
import fs from 'node:fs';
import {
  BASE, Informe, LOG, dniAlAzar, esProblema, hasta, http, logDesde, marcaDelLog, tokenDeServicio, uuid,
} from './lib.mjs';

const informe = new Informe();

/** Login que devuelve también el id de la cuenta: hace falta para mandarlo como dueño falso. */
async function sesion(email, password = 'palco1234') {
  const r = await http('POST', '/api/usuarios/login', { cuerpo: { email, password } });
  if (r.estado !== 200) throw new Error(`login de ${email}: ${r.estado} ${r.texto}`);
  return { token: r.json.token, id: r.json.usuario.id, email };
}

/** Corre un bloque; si el guion tira una excepción, cuenta como una comprobación que falla y sigue con el próximo. */
async function bloque(letra, nombre, cuerpo) {
  informe.escenario(letra, nombre);
  try {
    await cuerpo();
  } catch (e) {
    informe.ok('el bloque terminó sin errores del guion', false, e.stack ?? String(e));
  }
}

/** Una respuesta de error no tiene que mostrar cómo está hecho el servidor: ni excepciones ni renglones de Java. */
const sinStackTrace = (texto) => !/Exception|\bat (java|jakarta|org|com|io|sun)\.[\w$.]+\(/.test(texto);

const ids = (lista) => (Array.isArray(lista) ? lista.map((x) => x.id) : []);

// ===================================================================================== A. tokens JWT

const b64url = (datos) => Buffer.from(datos).toString('base64url');
const parte = (objeto) => b64url(JSON.stringify(objeto));
const leerParte = (texto) => JSON.parse(Buffer.from(texto, 'base64url').toString('utf8'));
const ahora = () => Math.floor(Date.now() / 1000);

/** Las tres partes de un token: cabecera y contenido leídos, y cada parte como vino. */
function abrir(token) {
  const [h, p, f] = token.split('.');
  return { header: leerParte(h), payload: leerParte(p), h, p, f };
}

/** RS256, como Tokens.firmar del servicio de Usuarios: SHA-256 con RSA (PKCS#1 v1.5) sobre "cabecera.contenido". */
function firmarRs256(header, payload, clave) {
  const contenido = `${parte(header)}.${parte(payload)}`;
  return `${contenido}.${b64url(crypto.sign('sha256', Buffer.from(contenido), clave))}`;
}

/** La clave del servidor, de PALCO_JWT_CLAVE_PRIVADA. Devuelve null si no está y un texto si no se puede leer. */
function claveDelServidor() {
  const valor = process.env.PALCO_JWT_CLAVE_PRIVADA?.trim();
  if (!valor) return { clave: null, problema: 'no está definida PALCO_JWT_CLAVE_PRIVADA' };
  try {
    return { clave: crypto.createPrivateKey({ key: Buffer.from(valor, 'base64'), format: 'der', type: 'pkcs8' }) };
  } catch (e) {
    return { clave: null, problema: `PALCO_JWT_CLAVE_PRIVADA no es una clave PKCS#8 en base64 (${e.code ?? e.message})` };
  }
}

/** Un GET protegido por servicio, cada uno con la cuenta que tiene el rol que pide. */
const PROTEGIDOS = [
  { servicio: 'Usuarios', ruta: '/api/usuarios/yo', cuenta: 'titular' },
  { servicio: 'Eventos', ruta: '/api/eventos/mios', cuenta: 'organizador' },
  { servicio: 'Ventas', ruta: '/api/v1/ventas', cuenta: 'titular' },
  { servicio: 'Reventa', ruta: '/api/v1/reventas/mias', cuenta: 'titular' },
  { servicio: 'Validación', ruta: '/api/v1/validacion/puertas/mias', cuenta: 'puerta' },
];

async function bloqueA() {
  const tokens = {
    titular: (await sesion('titular@palco.test')).token,
    organizador: (await sesion('organizador@palco.test')).token,
    puerta: (await sesion('puerta@palco.test')).token,
  };

  /** Pide cada ruta protegida con el token que arma `fabricar` a partir del token válido de su cuenta. */
  async function probar(fabricar) {
    const vistos = [];
    for (const p of PROTEGIDOS) {
      const r = await http('GET', p.ruta, { token: fabricar(tokens[p.cuenta]) });
      vistos.push({ servicio: p.servicio, estado: r.estado, texto: r.texto });
    }
    return vistos;
  }
  const codigos = (vistos) => vistos.map((v) => `${v.servicio} ${v.estado}`).join(', ');
  const todos = (vistos, estado) => vistos.every((v) => v.estado === estado);

  const conLogin = await probar((t) => t);
  informe.ok('con el token que da el login: 200 en los cinco servicios', todos(conLogin, 200), codigos(conLogin));

  // Un 401 a un token mal firmado no dice nada de la fecha ni del emisor: el servidor lo rechaza por la firma
  // antes de mirarlos. Para probar esas dos reglas el token tiene que estar bien firmado, con la clave del
  // servidor. Primero se comprueba que es esa clave: su parte pública es la que publica /api/usuarios/jwks, y
  // un token que el guion firma con los mismos datos que uno válido pasa.
  const { clave, problema } = claveDelServidor();
  const jwks = await http('GET', '/api/usuarios/jwks');
  const publicada = jwks.json?.keys?.[0];
  const esLaDelServidor = Boolean(clave && publicada)
    && crypto.createPublicKey(clave).export({ format: 'jwk' }).n === publicada.n;
  informe.ok('la clave de PALCO_JWT_CLAVE_PRIVADA es la del servidor (su parte pública es la de /api/usuarios/jwks)',
    esLaDelServidor, clave ? `kid publicado ${publicada?.kid}` : problema);

  let firmaElGuion = false;
  if (esLaDelServidor) {
    const refirmado = await probar((t) => {
      const { header, payload } = abrir(t);
      return firmarRs256(header, { ...payload, jti: uuid() }, clave);
    });
    firmaElGuion = todos(refirmado, 200);
    informe.ok('el mismo token, vuelto a firmar por el guion: 200 en los cinco (el guion firma como el servidor)',
      firmaElGuion, codigos(refirmado));
  }

  const conClave = (nombre, cambios) => [nombre, (t) => {
    const { header, payload } = abrir(t);
    return firmarRs256(header, { ...payload, jti: uuid(), ...cambios() }, clave);
  }, true];
  const otraClave = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 }).privateKey;
  const publicaEnPem = publicada
    ? crypto.createPublicKey({ key: publicada, format: 'jwk' }).export({ type: 'spki', format: 'pem' })
    : '';

  const manipulados = [
    // MicroProfile JWT tolera 60 segundos de diferencia de reloj: una hora de vencido queda bien afuera.
    conClave('vencido hace una hora, bien firmado', () => ({ iat: ahora() - 7200, exp: ahora() - 3600 })),
    conClave('de otro emisor (iss distinto de "palco"), bien firmado', () => ({ iss: 'https://otro-emisor.example' })),
    ['con alg none y sin firma', (t) => `${parte({ alg: 'none', typ: 'JWT' })}.${abrir(t).p}.`],
    ['con la firma alterada (un byte cambiado)', (t) => {
      // Un byte del medio: el último carácter en base64url lleva bits de relleno y cambiarlo puede no cambiar nada.
      const { h, p, f } = abrir(t);
      const firma = Buffer.from(f, 'base64url');
      firma[firma.length >> 1] ^= 0xff;
      return `${h}.${p}.${b64url(firma)}`;
    }],
    ['con el rol servicio agregado y la firma original', (t) => {
      const { h, payload, f } = abrir(t);
      return `${h}.${parte({ ...payload, groups: [...payload.groups, 'servicio'] })}.${f}`;
    }],
    ['firmado con otra clave RSA (con el kid del servidor)', (t) => {
      const { header, payload } = abrir(t);
      return firmarRs256(header, { ...payload, jti: uuid() }, otraClave);
    }],
    ['con HS256 y la clave pública del servidor como secreto (confusión de algoritmo)', (t) => {
      const { header, payload } = abrir(t);
      const contenido = `${parte({ ...header, alg: 'HS256' })}.${parte({ ...payload, jti: uuid() })}`;
      return `${contenido}.${b64url(crypto.createHmac('sha256', publicaEnPem).update(contenido).digest())}`;
    }],
  ];

  for (const [nombre, fabricar, pideLaClave] of manipulados) {
    if (pideLaClave && !firmaElGuion) {
      informe.ok(`token ${nombre}: 401 en los cinco`, false,
        'sin la clave del servidor no se puede saber si el 401 sería por esto o por la firma');
      continue;
    }
    const vistos = await probar(fabricar);
    informe.ok(`token ${nombre}: 401 en los cinco, sin stack trace`,
      todos(vistos, 401) && vistos.every((v) => sinStackTrace(v.texto)), codigos(vistos));
  }
}

// ===================================================================================== B. mass assignment

/** El precio del sector: con el 10 % de cargo de servicio, 22.000 por entrada; el tope de reventa también es 22.000. */
const PRECIO = 20000;
const CON_CARGO = 22000;

async function bloqueB() {
  const O = await sesion('organizador@palco.test');
  const O2 = await sesion('otro.organizador@palco.test');
  const T = await sesion('titular@palco.test');
  const T2 = await sesion('otro.titular@palco.test');
  const S = await tokenDeServicio();
  const dueno = async (eventoId) => (await http('GET', `/api/eventos/${eventoId}/organizador`, { token: S })).json?.organizadorId;
  const enMios = async (token, eventoId) => ids((await http('GET', '/api/eventos/mios', { token })).json).includes(eventoId);
  const aceptado = (r, exito) => r.estado === exito || r.estado === 400;

  // ---- Crear un evento con dueño, estado y vendidas elegidos por el cliente.
  const titulo = `Prueba seguridad ${Date.now()}`;
  const alta = await http('POST', '/api/eventos', {
    token: O.token,
    cuerpo: {
      titulo, categoria: 'Teatro', venue: 'Sala de prueba', fechaISO: '2027-12-31T20:00:00',
      descripcion: 'Evento creado por la prueba de seguridad.',
      sectores: [{ nombre: 'Platea', precio: PRECIO, cupo: 100, id: 'sec-elegido', vendidas: 90 }],
      id: 'ev-elegido', slug: 'elegido-por-el-cliente', organizadorId: O2.id,
      destacado: true, cancelado: true, cancelacionMotivo: 'Inventado por el cliente.',
    },
  });
  informe.ok('POST /api/eventos con id, slug, organizadorId, destacado, cancelado y vendidas: 201 o 400',
    aceptado(alta, 201), `${alta.estado}`);
  if (alta.estado !== 201) throw new Error(`Sin el evento no se puede seguir: ${alta.estado} ${alta.texto}`);
  const evento = alta.json;
  let leido = (await http('GET', `/api/eventos/${evento.slug}`)).json;
  informe.ok('el evento creado: id, slug, destacado, cancelado y vendidas los puso el servidor',
    leido.id !== 'ev-elegido' && leido.slug !== 'elegido-por-el-cliente' && !leido.destacado && !leido.cancelado
      && leido.cancelacionMotivo === null && leido.sectores[0].id !== 'sec-elegido' && leido.sectores[0].vendidas === 0,
    `id ${leido.id}, slug ${leido.slug}, destacado ${leido.destacado}, cancelado ${leido.cancelado}, `
      + `sector ${leido.sectores[0].id} con ${leido.sectores[0].vendidas} vendidas`);
  informe.ok('el evento creado es del organizador del token, no del organizadorId mandado',
    (await dueno(evento.id)) === O.id && (await enMios(O.token, evento.id)) && !(await enMios(O2.token, evento.id)),
    `dueño ${await dueno(evento.id)}`);

  // ---- Editarlo (PUT) mandando otro dueño, cancelado, destacado y vendidas, además de un cambio legítimo.
  const sector = leido.sectores[0];
  const edicion = await http('PUT', `/api/eventos/${evento.id}`, {
    token: O.token,
    cuerpo: {
      titulo: `${titulo} (editado)`, categoria: leido.categoria, venue: leido.venue, fechaISO: leido.fechaISO,
      descripcion: leido.descripcion, imagenUrl: leido.imagenUrl,
      sectores: [{ id: sector.id, nombre: sector.nombre, precio: sector.precio, cupo: sector.cupo, vendidas: 999 }],
      organizadorId: O2.id, vendidas: 999, cancelado: true, cancelacionMotivo: 'Inventado por el cliente.', destacado: true,
    },
  });
  informe.ok('PUT /api/eventos/{id} con organizadorId, vendidas, cancelado y destacado: 200 o 400',
    aceptado(edicion, 200), `${edicion.estado}`);
  leido = (await http('GET', `/api/eventos/${evento.slug}`)).json;
  informe.ok('después del PUT: el título cambió y cancelado, destacado y vendidas siguen como estaban',
    (edicion.estado !== 200 || leido.titulo === `${titulo} (editado)`) && !leido.cancelado && !leido.destacado
      && leido.sectores.every((s) => s.vendidas === 0),
    `"${leido.titulo}", cancelado ${leido.cancelado}, destacado ${leido.destacado}, `
      + `vendidas ${leido.sectores.map((s) => s.vendidas).join('/')}`);
  informe.ok('después del PUT el dueño sigue siendo el mismo (y el otro organizador no lo ve)',
    (await dueno(evento.id)) === O.id && (await enMios(O.token, evento.id)) && !(await enMios(O2.token, evento.id)),
    `dueño ${await dueno(evento.id)}`);

  // ---- Abrir una compra con estado, montos, precio unitario y comprador elegidos por el cliente.
  const pedido = await http('POST', '/api/v1/ventas', {
    token: T.token, clave: uuid(),
    cuerpo: {
      eventoId: evento.id,
      items: [{ sectorId: sector.id, cantidad: 2, precioUnitario: 1, sector: 'VIP' }],
      titulares: [{ nombre: 'Prueba Seguridad', dni: dniAlAzar() }, { nombre: 'Prueba Seguridad', dni: dniAlAzar() }],
      id: 'V-elegido', estado: 'EMITIDA', compradorId: T2.id, pagoId: 'P-inventado',
      subtotal: 1, cargoServicio: 0, montoTotal: 1,
    },
  });
  informe.ok('POST /api/v1/ventas con estado, montoTotal, compradorId, precioUnitario y pagoId: 201 o 400',
    aceptado(pedido, 201), `${pedido.estado}`);
  if (pedido.estado !== 201) throw new Error(`Sin la compra no se puede seguir: ${pedido.estado} ${pedido.texto}`);
  const venta = (await http('GET', `/api/v1/ventas/${pedido.json.id}`, { token: T.token })).json;
  informe.ok(`la compra: EN_CHECKOUT, sin pago y con los montos del servidor (2 × ${PRECIO} + 10 % = ${2 * CON_CARGO})`,
    venta.id !== 'V-elegido' && venta.estado === 'EN_CHECKOUT' && venta.pagoId === null && venta.entradas.length === 0
      && venta.items[0].precioUnitario === PRECIO && venta.subtotal === 2 * PRECIO
      && venta.cargoServicio === 2 * (CON_CARGO - PRECIO) && venta.montoTotal === 2 * CON_CARGO,
    `${venta.id} ${venta.estado}, pagoId ${venta.pagoId}, unitario ${venta.items[0].precioUnitario}, `
      + `subtotal ${venta.subtotal}, cargo ${venta.cargoServicio}, total ${venta.montoTotal}`);
  const deOtro = await http('GET', `/api/v1/ventas/${venta.id}`, { token: T2.token });
  const listaDelOtro = ids((await http('GET', '/api/v1/ventas', { token: T2.token })).json);
  const listaPropia = ids((await http('GET', '/api/v1/ventas', { token: T.token })).json);
  informe.ok('la compra es de quien la abrió: está en su lista y la cuenta del compradorId mandado no la ve',
    listaPropia.includes(venta.id) && !listaDelOtro.includes(venta.id) && [403, 404].includes(deOtro.estado),
    `la otra cuenta: GET ${deOtro.estado}, en su lista ${listaDelOtro.includes(venta.id)}`);

  // ---- Pagarla y publicar una de las entradas con estado, vendedor y tope elegidos por el cliente.
  await http('POST', `/api/v1/ventas/${venta.id}/pagos`, {
    token: T.token, clave: uuid(), cuerpo: { medioDePago: 'tarjeta', token: 'tok_test' },
  });
  const emitida = await hasta(async () => {
    const v = (await http('GET', `/api/v1/ventas/${venta.id}`, { token: T.token })).json;
    return v?.estado === 'EMITIDA' ? v : null;
  }, { maximoMs: 30000 });
  if (!emitida) throw new Error(`La compra ${venta.id} no se emitió en 30 segundos`);
  const [primera, segunda] = emitida.entradas.map((e) => e.id);

  const publicacion = await http('POST', '/api/v1/reventas', {
    token: T.token,
    cuerpo: {
      entradaId: primera, precio: PRECIO,
      id: 'R-elegido', estado: 'VENDIDA', vendedorId: T2.id, precioMaximo: 999999, eventoId: 'ev-otro',
      publicada: '2000-01-01T00:00:00Z',
    },
  });
  informe.ok('POST /api/v1/reventas con estado, vendedorId, precioMaximo, id y eventoId: 201 o 400',
    aceptado(publicacion, 201), `${publicacion.estado}`);
  if (publicacion.estado !== 201) throw new Error(`Sin la publicación no se puede seguir: ${publicacion.estado} ${publicacion.texto}`);
  const pub = publicacion.json;
  const mias = (await http('GET', '/api/v1/reventas/mias', { token: T.token })).json ?? [];
  const guardada = mias.find((x) => x.id === pub.id);
  informe.ok(`la publicación: ACTIVA, del evento de la entrada y con el tope del servidor (${CON_CARGO})`,
    pub.id !== 'R-elegido' && guardada?.estado === 'ACTIVA' && guardada?.precioMaximo === CON_CARGO
      && guardada?.eventoId === evento.id,
    `${pub.id} ${guardada?.estado}, precioMaximo ${guardada?.precioMaximo}, evento ${guardada?.eventoId}`);
  const delOtro = ids((await http('GET', '/api/v1/reventas/mias', { token: T2.token })).json);
  const publicas = (await http('GET', `/api/v1/reventas?eventoId=${evento.id}`)).json ?? [];
  informe.ok('la publicación es de quien la publicó: está en sus publicaciones y no en las del vendedorId mandado',
    Boolean(guardada) && !delOtro.includes(pub.id) && publicas.some((x) => x.id === pub.id && x.precioMaximo === CON_CARGO),
    `en las propias ${Boolean(guardada)}, en las de la otra cuenta ${delOtro.includes(pub.id)}`);

  // El tope que vale es el del servidor: un precioMaximo más alto no habilita un precio arriba del real.
  const caro = await http('POST', '/api/v1/reventas', {
    token: T.token, cuerpo: { entradaId: segunda, precio: 30000, precioMaximo: 40000 },
  });
  esProblema(informe, caro, 422, 'precio-fuera-de-rango', '/api/v1/reventas');
  const propias = (await http('GET', '/api/v1/reventas/mias', { token: T.token })).json ?? [];
  informe.ok('precio 30000 con un precioMaximo inventado de 40000: la entrada no quedó publicada',
    !propias.some((x) => x.entradaId === segunda), `${propias.length} publicaciones propias`);

  // La publicación se retira: no queda a la venta en el servidor.
  await http('DELETE', `/api/v1/reventas/${pub.id}`, { token: T.token });
}

// ===================================================================================== C. XSS almacenado

const SCRIPT = '<script>alert(1)</script>';
const IMAGEN = '<img src=x onerror=alert(1)>';

async function bloqueC() {
  const O = await sesion('organizador@palco.test');
  const marca = String(Date.now());
  const titulo = `${SCRIPT} ${IMAGEN} ${marca}`;
  const descripcion = `Descripción con ${SCRIPT} y ${IMAGEN} adentro.`;
  const alta = await http('POST', '/api/eventos', {
    token: O.token,
    cuerpo: {
      titulo, descripcion, categoria: 'Teatro', venue: 'Sala de prueba', fechaISO: '2027-12-31T20:00:00',
      sectores: [{ nombre: 'Platea', precio: PRECIO, cupo: 10 }],
    },
  });
  if (alta.estado === 400) {
    informe.ok('el evento con HTML en el título y la descripción se rechaza con 400', true, alta.texto.slice(0, 200));
    return;
  }
  informe.ok('el evento con HTML en el título y la descripción se guarda: 201 con el texto tal cual',
    alta.estado === 201 && alta.json.titulo === titulo && alta.json.descripcion === descripcion,
    `${alta.estado} "${alta.json?.titulo}"`);
  if (alta.estado !== 201) return;

  const { slug } = alta.json;
  informe.ok('el slug, que va en la URL, sale solo con letras, números y guiones', /^[a-z0-9-]+$/.test(slug), slug);
  const detalle = await http('GET', `/api/eventos/${slug}`);
  informe.ok('GET del detalle: JSON (no HTML) con el título y la descripción como texto',
    detalle.estado === 200 && detalle.tipo.startsWith('application/json')
      && detalle.json.titulo === titulo && detalle.json.descripcion === descripcion,
    `${detalle.estado} ${detalle.tipo}`);
  const busqueda = await http('GET', `/api/eventos?q=${marca}`);
  const enCatalogo = (busqueda.json ?? []).find((e) => e.slug === slug);
  informe.ok('en el catálogo (búsqueda) vuelve con el mismo texto', enCatalogo?.titulo === titulo,
    `${busqueda.estado} "${enCatalogo?.titulo}"`);
  informe.dato(`para verlo en el navegador: /evento/${slug}`);
}

// ===================================================================================== D. XXE en el canal SOAP

/** Puntos de venta que Palco no usa (Facturación pide con el 1): las líneas del log de esta prueba se reconocen por ellos. */
const PTO_CONTROL = 9001;
const PTO_ATAQUE = 9002;

/** El pedido que manda Facturación (ArcaSimuladoPorSoapTest), con una cabecera DOCTYPE opcional antes del sobre. */
const sobre = (puntoDeVenta, idVenta, doctype = '') => `<?xml version="1.0" encoding="UTF-8"?>
${doctype}<S:Envelope xmlns:S="http://schemas.xmlsoap.org/soap/envelope/">
  <S:Body>
    <a:FECAESolicitar xmlns:a="urn:palco:legado:arca:wsfe">
      <CbteTipo>6</CbteTipo>
      <PtoVta>${puntoDeVenta}</PtoVta>
      <ImpTotal>100</ImpTotal>
      <CbteFch>20261120</CbteFch>
      <IdVenta>${idVenta}</IdVenta>
    </a:FECAESolicitar>
  </S:Body>
</S:Envelope>`;

async function soap(cuerpo) {
  const r = await fetch(`${BASE}/legado/arca`, {
    method: 'POST', headers: { 'Content-Type': 'text/xml; charset=utf-8', SOAPAction: '""' }, body: cuerpo,
  });
  return { estado: r.status, texto: await r.text() };
}

async function bloqueD() {
  const wsdl = await http('GET', '/legado/arca?wsdl');
  informe.ok('el canal está arriba: GET /legado/arca?wsdl publica FECAESolicitar',
    wsdl.estado === 200 && wsdl.texto.includes('FECAESolicitar'), `${wsdl.estado}`);
  const hayLog = fs.existsSync(LOG);
  const marca = hayLog ? marcaDelLog() : 0;

  const control = await soap(sobre(PTO_CONTROL, `V-seguridad-${Date.now()}`));
  informe.ok('el mismo sobre sin DOCTYPE se atiende: 200 con Resultado A',
    control.estado === 200 && control.texto.includes('<Resultado>A</Resultado>'), `${control.estado}`);

  const ataques = [
    ['entidad externa a un archivo del servidor (file:///etc/passwd)',
      '<!DOCTYPE S:Envelope [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>\n', '&xxe;'],
    ['DTD externa por una entidad de parámetro (pedido al propio servidor)',
      '<!DOCTYPE S:Envelope [<!ENTITY % remota SYSTEM "http://127.0.0.1:8080/legado/arca?wsdl"> %remota;]>\n', 'V-xxe-parametro'],
    ['entidades internas anidadas (expansión en cadena)',
      '<!DOCTYPE S:Envelope [<!ENTITY a "V-xxe"><!ENTITY b "&a;&a;&a;">]>\n', '&b;'],
  ];
  for (const [nombre, doctype, idVenta] of ataques) {
    const r = await soap(sobre(PTO_ATAQUE, idVenta, doctype));
    informe.ok(`${nombre}: SOAP Fault sin respuesta del servicio ni contenido expandido`,
      r.estado === 500 && r.texto.includes('Fault') && !r.texto.includes('FECAESolicitarResponse')
        && !r.texto.includes('root:') && sinStackTrace(r.texto),
      `${r.estado} ${r.texto.replace(/\s+/g, ' ').slice(0, 220)}`);
  }

  // El log del ARCA simulado confirma que ningún sobre con DOCTYPE llegó a la operación: el control sí aparece.
  if (!hayLog) {
    informe.ok('el ARCA simulado no atendió ningún sobre con DOCTYPE (server.log)', false,
      'no está el server.log: definí PALCO_LOG');
    return;
  }
  const delControl = await hasta(() => logDesde(marca).some((l) => l.includes(`comprobante 6 ${PTO_CONTROL}-`)),
    { maximoMs: 5000 });
  const lineas = logDesde(marca).filter((l) => l.includes('ARCA simulado'));
  const deAtaques = lineas.filter((l) => l.includes(`comprobante 6 ${PTO_ATAQUE}-`) || l.includes('comprobante rechazado'));
  informe.ok('en el server.log está el control y ningún comprobante de los sobres con DOCTYPE',
    Boolean(delControl) && deAtaques.length === 0,
    `control ${delControl ? 'sí' : 'no'}, de los ataques ${deAtaques.length}`);
}

// ===================================================================================== corrida

console.log(`Pruebas de seguridad de Palco. Servidor: ${BASE}. ${new Date().toLocaleString('es-AR', { hour12: false })}`);
await bloque('A', 'Tokens JWT manipulados: 401 en cada servicio', bloqueA);
await bloque('B', 'Mass assignment: los campos que fija el servidor no los cambia el cliente', bloqueB);
await bloque('C', 'XSS almacenado: el HTML de un evento se guarda y se devuelve como texto', bloqueC);
await bloque('D', 'XXE en el canal SOAP (/legado/arca): el DOCTYPE se rechaza sin expandir las entidades', bloqueD);

const fallas = informe.resumen();
const total = informe.escenarios.reduce((suma, e) => suma + e.comprobaciones.length, 0);
console.log(`\n${total - fallas} de ${total} comprobaciones`);
console.log(fallas === 0 ? 'TODO BIEN' : `HAY ${fallas} COMPROBACIONES QUE FALLAN`);
process.exit(fallas === 0 ? 0 : 1);
