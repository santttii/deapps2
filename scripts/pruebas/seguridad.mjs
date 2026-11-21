// Pruebas de seguridad contra el sistema en marcha: lo que puede intentar alguien con una cuenta común y un
// cliente HTTP, sin acceso al servidor. Cinco bloques:
//
//   A. Tokens JWT manipulados: vencido, de otro emisor, sin vencimiento, con alg none, con la firma alterada,
//      con un rol agregado sin volver a firmar, firmado con otra clave y con HS256 usando la clave pública como
//      secreto. Cada uno se prueba en cinco servicios (cada WAR verifica el token por su cuenta): todos 401
//      token-invalido en Problem Details, que es como escribe ese rechazo ErrorDeAutenticacionServlet (common).
//   B. Mass assignment: al registrarse, al crear y editar un evento, al abrir una compra y al publicar en la
//      reventa se mandan campos que el cliente no elige (rol, dueño, estado, precios, montos, vendidas, tope).
//      La API lee el cuerpo con JSON-B (common/rest/JsonProvider), que descarta lo que el DTO no tiene: la
//      respuesta es la de un pedido común (201 o 200) y, leyendo el recurso después, esos campos quedaron como
//      los fija el servidor.
//   C. XSS almacenado: un evento con <script> y <img onerror> en el título y la descripción, y una compra con
//      ese HTML en el nombre del titular, se guardan y se devuelven como texto, en JSON. Que el navegador los
//      muestre como texto y no los ejecute lo prueba frontend/e2e/xss.spec.ts.
//   D. XXE en el canal SOAP: sobres con DOCTYPE y entidades (externas, de parámetro e internas) a /legado/arca
//      se rechazan con un SOAP Fault sin expandirlas, y el ARCA simulado no atiende ninguno.
//   E. Cookies, cabeceras y datos personales, por nginx (https) y directo al backend. El mismo recorrido por los
//      dos caminos (el login, el perfil, el catálogo, una compra hasta la emisión y sus errores, y por nginx
//      también la página y un archivo de /assets): ninguna respuesta trae Set-Cookie, porque Palco no usa
//      cookies (el token viaja en Authorization); por nginx todas llevan CSP, HSTS y X-Content-Type-Options;
//      a un Origin ajeno CORS no le habilita nada y en ninguna respuesta Access-Control-Allow-Origin es *; el
//      catálogo y el detalle, que son públicos, no traen datos de personas; el login y el perfil no traen la
//      contraseña ni el hash, y los errores no repiten el email, la contraseña ni el token que se mandaron.
//      Que el navegador no guarde ninguna cookie lo comprueba también frontend/e2e/xss.spec.ts.
//
//   node scripts/pruebas/seguridad.mjs
//
// Sale con 0 si pasan todas las comprobaciones y con 1 si alguna falla. Solo usa módulos que trae Node: los
// tokens se arman y se firman con node:crypto. Deja en la base una cuenta nueva verificada, tres eventos de
// prueba (uno con el HTML en el título), cuatro compras emitidas de titular@palco.test (una con el HTML en el
// nombre del titular, una por nginx y otra directa al backend) y una publicación de reventa retirada.
//
// Variables de entorno:
//   PALCO_URL                 dirección del backend                                 (http://localhost:8080)
//   PALCO_JWT_CLAVE_PRIVADA   la clave con la que el servidor firma los tokens: PKCS#8 en base64, la misma
//                             variable que lee el backend (docker-compose.yml). Si no está en el entorno, se
//                             busca en backend/.env (el que carga dev.sh) y en el .env de la raíz (el del
//                             compose). Sin ella fallan los tokens bien firmados del bloque A.
//   PALCO_SECRETO_SERVICIOS   el secreto entre servicios: el dueño de un evento solo lo lee el rol servicio.
//                             Si no está, se busca donde lo buscan los demás guiones (ver lib.mjs).
//   PALCO_LOG                 el server.log: de ahí salen el código de verificación de la cuenta del bloque B
//                             y la prueba de que el ARCA simulado no atendió nada en el bloque D
//   PALCO_WEB_URL             la app por nginx, para el bloque E                    (https://localhost)
//                             Su origen es el que el compose pone en PALCO_CORS_ORIGENES (PALCO_URL_PUBLICA).
//                             Sin nginx (con dev.sh) fallan las comprobaciones de ese camino.
import crypto from 'node:crypto';
import fs from 'node:fs';
import httpPlano from 'node:http';
import https from 'node:https';
import path from 'node:path';
import {
  BASE, Informe, LOG, REPO, dniAlAzar, esProblema, eventoPropio, hasta, http, logDesde, marcaDelLog,
  tokenDeServicio, uuid,
} from './lib.mjs';

const informe = new Informe();
const PASSWORD = 'palco1234';

/** Login que devuelve también el id de la cuenta: hace falta para mandarlo como dueño falso. */
async function sesion(email, password = PASSWORD) {
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

/** Paga una compra con el simulador de la pasarela y espera a que se emita (la emisión es asincrónica). */
async function pagarYEsperar(token, ventaId) {
  await http('POST', `/api/v1/ventas/${ventaId}/pagos`, {
    token, clave: uuid(), cuerpo: { medioDePago: 'tarjeta', token: 'tok_test' },
  });
  return hasta(async () => {
    const v = (await http('GET', `/api/v1/ventas/${ventaId}`, { token })).json;
    return v?.estado === 'EMITIDA' ? v : null;
  }, { maximoMs: 30000 });
}

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

/** El valor de PALCO_JWT_CLAVE_PRIVADA en un archivo .env, o '' si el archivo no está o no la define. */
function deArchivoEnv(archivo) {
  if (!fs.existsSync(archivo)) return '';
  const linea = fs.readFileSync(archivo, 'utf8').match(/^PALCO_JWT_CLAVE_PRIVADA=(.*)$/m);
  return (linea?.[1] ?? '').trim().replace(/^["']|["']$/g, '');
}

/** La clave del servidor: del entorno, de backend/.env o del .env de la raíz. Si no está, null y qué faltó. */
function claveDelServidor() {
  const valor = process.env.PALCO_JWT_CLAVE_PRIVADA?.trim()
    || deArchivoEnv(path.join(REPO, 'backend', '.env')) || deArchivoEnv(path.join(REPO, '.env'));
  if (!valor) return { clave: null, problema: 'no está PALCO_JWT_CLAVE_PRIVADA (ni en el entorno ni en los .env)' };
  try {
    return { clave: crypto.createPrivateKey({ key: Buffer.from(valor, 'base64'), format: 'der', type: 'pkcs8' }) };
  } catch (e) {
    return { clave: null, problema: `PALCO_JWT_CLAVE_PRIVADA no es una clave PKCS#8 en base64 (${e.code ?? e.message})` };
  }
}

/** Un GET con el token. Devuelve también WWW-Authenticate, que el 401 por token inválido tiene que traer. */
async function conToken(ruta, token) {
  const r = await fetch(BASE + ruta, { headers: { Authorization: `Bearer ${token}` } });
  const texto = await r.text();
  let json = null;
  try { json = texto ? JSON.parse(texto) : null; } catch { /* no es JSON */ }
  return {
    estado: r.status, tipo: r.headers.get('content-type') ?? '', desafio: r.headers.get('www-authenticate') ?? '',
    texto, json,
  };
}

/** Un GET protegido por servicio, cada uno con la cuenta que tiene el rol que pide. */
const PROTEGIDOS = [
  { servicio: 'Usuarios', ruta: '/api/usuarios/yo', cuenta: 'titular' },
  { servicio: 'Eventos', ruta: '/api/eventos/mios', cuenta: 'organizador' },
  { servicio: 'Ventas', ruta: '/api/v1/ventas', cuenta: 'titular' },
  { servicio: 'Reventa', ruta: '/api/v1/reventas/mias', cuenta: 'titular' },
  { servicio: 'Validación', ruta: '/api/v1/validacion/puertas/mias', cuenta: 'puerta' },
];

/**
 * El rechazo de un token que no se puede verificar: lo emite el mecanismo MP-JWT de WildFly antes de llegar a
 * JAX-RS y lo escribe ErrorDeAutenticacionServlet, como Problem Details token-invalido de la ruta pedida y con
 * WWW-Authenticate: Bearer error="invalid_token".
 */
const rechazado = (v) => v.estado === 401 && v.tipo.startsWith('application/problem+json')
  && v.json?.type === '/problemas/token-invalido' && v.json?.instance === v.ruta
  && v.desafio.includes('invalid_token') && sinStackTrace(v.texto);

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
      vistos.push({ servicio: p.servicio, ruta: p.ruta, ...(await conToken(p.ruta, fabricar(tokens[p.cuenta]))) });
    }
    return vistos;
  }
  const codigos = (vistos) => vistos
    .map((v) => `${v.servicio} ${v.estado}${v.json?.type ? ` ${v.json.type}` : ''}`).join(', ');
  const todos = (vistos, estado) => vistos.every((v) => v.estado === estado);

  const conLogin = await probar((t) => t);
  informe.ok('con el token que da el login: 200 en los cinco servicios', todos(conLogin, 200), codigos(conLogin));

  // Un 401 a un token mal firmado no dice nada de la fecha ni del emisor: el servidor lo rechaza por la firma
  // antes de mirarlos. Para probar esas reglas el token tiene que estar bien firmado, con la clave del
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

  // Los que piden la clave cambian una sola cosa del token válido y lo vuelven a firmar bien.
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
    // JSON.stringify no escribe las claves undefined: el token sale sin exp.
    conClave('sin fecha de vencimiento (sin exp), bien firmado', () => ({ exp: undefined })),
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
    const descripcion = `token ${nombre}: 401 token-invalido en Problem Details en los cinco, sin stack trace`;
    if (pideLaClave && !firmaElGuion) {
      informe.ok(descripcion, false, 'sin la clave del servidor no se puede saber si el 401 sería por esto o por la firma');
      continue;
    }
    const vistos = await probar(fabricar);
    informe.ok(descripcion, vistos.every(rechazado), codigos(vistos));
  }
}

// ===================================================================================== B. mass assignment

/** El precio del sector: con el 10 % de cargo de servicio, 22.000 por entrada; el tope de reventa también es 22.000. */
const PRECIO = 20000;
const CON_CARGO = 22000;

/**
 * Una cuenta nueva que se registra eligiendo su id, su rol y el email ya verificado. RegistroDto no tiene esos
 * campos y ServicioDeUsuarios la crea siempre con rol user y sin verificar.
 */
async function registroConCamposDeMas() {
  const email = `seguridad.${Date.now()}@palco.test`;
  const hayLog = fs.existsSync(LOG);
  const marca = hayLog ? marcaDelLog() : 0;
  let registro;
  // El DNI sale al azar: si justo ya tiene cuenta, se prueba con otro.
  for (let intento = 1; intento <= 3; intento++) {
    registro = await http('POST', '/api/usuarios/registro', {
      cuerpo: {
        nombre: 'Prueba', apellido: 'Seguridad', dni: dniAlAzar(), fechaNacimiento: '1995-05-17', email,
        password: PASSWORD,
        id: 'user-elegido', rol: 'organizer', groups: ['organizer', 'staff', 'servicio'], emailVerificado: true,
      },
    });
    if (registro.estado !== 400 || !registro.texto.includes('DNI')) break;
  }
  informe.igual('POST /api/usuarios/registro con id, rol, groups y emailVerificado', registro.estado, 201);
  if (registro.estado !== 201) return;

  const antes = await http('POST', '/api/usuarios/login', { cuerpo: { email, password: PASSWORD } });
  informe.ok('el emailVerificado mandado no cuenta: el login responde 409 conflicto (falta verificar el email)',
    antes.estado === 409 && antes.json?.type === '/problemas/conflicto', `${antes.estado} ${antes.texto.slice(0, 200)}`);

  const descripcion = 'al verificar el email, la cuenta es rol user y con el id que le dio el servidor';
  if (!hayLog) {
    informe.ok(descripcion, false, `no está el server.log, de donde sale el código (${LOG}): definí PALCO_LOG`);
    return;
  }
  // Sin SMTP el email con el código queda en el log (CorreoAlLog, con PALCO_CORREO_LOG_COMPLETO=true).
  const linea = await hasta(() => logDesde(marca).find((l) => l.includes(`Email para ${email} (`)));
  const codigo = linea?.match(/el email es (\d{6})\./)?.[1];
  if (!codigo) {
    informe.ok(descripcion, false, `no está el código de ${email} en ${LOG}`);
    return;
  }
  const verificada = await http('POST', '/api/usuarios/verificacion', { cuerpo: { email, codigo } });
  const usuario = verificada.json?.usuario;
  informe.ok(descripcion, verificada.estado === 200 && usuario?.rol === 'user' && usuario?.id !== 'user-elegido',
    `${verificada.estado} id ${usuario?.id}, rol ${usuario?.rol}`);
  const token = verificada.json?.token;
  const grupos = token ? abrir(token).payload.groups : null;
  informe.ok('su token lleva solo el grupo user', JSON.stringify(grupos) === '["user"]', JSON.stringify(grupos));
  informe.igual('con ese token, POST /api/eventos (es del rol organizer)',
    (await http('POST', '/api/eventos', { token, cuerpo: {} })).estado, 403);
}

async function bloqueB() {
  // ---- Registrarse con id, rol y email verificado elegidos por el cliente.
  await registroConCamposDeMas();

  const O = await sesion('organizador@palco.test');
  const O2 = await sesion('otro.organizador@palco.test');
  const T = await sesion('titular@palco.test');
  const T2 = await sesion('otro.titular@palco.test');
  const S = await tokenDeServicio();
  const dueno = async (eventoId) => (await http('GET', `/api/eventos/${eventoId}/organizador`, { token: S })).json?.organizadorId;
  const enMios = async (token, eventoId) => ids((await http('GET', '/api/eventos/mios', { token })).json).includes(eventoId);

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
  informe.igual('POST /api/eventos con id, slug, organizadorId, destacado, cancelado y vendidas', alta.estado, 201);
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
  informe.igual('PUT /api/eventos/{id} con organizadorId, vendidas, cancelado y destacado', edicion.estado, 200);
  leido = (await http('GET', `/api/eventos/${evento.slug}`)).json;
  informe.ok('después del PUT: el título cambió y cancelado, destacado y vendidas siguen como estaban',
    leido.titulo === `${titulo} (editado)` && !leido.cancelado && !leido.destacado
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
  informe.igual('POST /api/v1/ventas con estado, montoTotal, compradorId, precioUnitario y pagoId', pedido.estado, 201);
  if (pedido.estado !== 201) throw new Error(`Sin la compra no se puede seguir: ${pedido.estado} ${pedido.texto}`);
  const venta = (await http('GET', `/api/v1/ventas/${pedido.json.id}`, { token: T.token })).json;
  informe.ok(`la compra: EN_CHECKOUT, sin pago y con los montos del servidor (2 × ${PRECIO} + 10 % = ${2 * CON_CARGO})`,
    venta.id !== 'V-elegido' && venta.estado === 'EN_CHECKOUT' && venta.pagoId === null && venta.entradas.length === 0
      && venta.items[0].precioUnitario === PRECIO && venta.subtotal === 2 * PRECIO
      && venta.cargoServicio === 2 * (CON_CARGO - PRECIO) && venta.montoTotal === 2 * CON_CARGO,
    `${venta.id} ${venta.estado}, pagoId ${venta.pagoId}, unitario ${venta.items[0].precioUnitario}, `
      + `subtotal ${venta.subtotal}, cargo ${venta.cargoServicio}, total ${venta.montoTotal}`);
  // Para la otra cuenta la compra no existe: ServicioDeVentas responde 404 a la venta de otro comprador.
  const deOtro = await http('GET', `/api/v1/ventas/${venta.id}`, { token: T2.token });
  const listaDelOtro = ids((await http('GET', '/api/v1/ventas', { token: T2.token })).json);
  const listaPropia = ids((await http('GET', '/api/v1/ventas', { token: T.token })).json);
  informe.ok('la compra es de quien la abrió: está en su lista, y la cuenta del compradorId mandado no la ve (404)',
    listaPropia.includes(venta.id) && !listaDelOtro.includes(venta.id) && deOtro.estado === 404,
    `la otra cuenta: GET ${deOtro.estado}, en su lista ${listaDelOtro.includes(venta.id)}`);

  // ---- Pagarla y publicar una de las entradas con estado, vendedor y tope elegidos por el cliente.
  const emitida = await pagarYEsperar(T.token, venta.id);
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
  informe.igual('POST /api/v1/reventas con estado, vendedorId, precioMaximo, id y eventoId', publicacion.estado, 201);
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
  informe.igual('la publicación de la prueba se retira', (await http('DELETE', `/api/v1/reventas/${pub.id}`, { token: T.token })).estado, 204);
}

// ===================================================================================== C. XSS almacenado

const SCRIPT = '<script>alert(1)</script>';
const IMAGEN = '<img src=x onerror=alert(1)>';

async function bloqueC() {
  const O = await sesion('organizador@palco.test');
  const marca = String(Date.now());
  const titulo = `${SCRIPT} ${IMAGEN} ${marca}`;
  const descripcion = `Descripción con ${SCRIPT} y ${IMAGEN} adentro.`;
  // EventosResource no filtra el texto (NuevoEventoDto solo limita el largo): se guarda tal cual.
  const alta = await http('POST', '/api/eventos', {
    token: O.token,
    cuerpo: {
      titulo, descripcion, categoria: 'Teatro', venue: 'Sala de prueba', fechaISO: '2027-12-31T20:00:00',
      sectores: [{ nombre: 'Platea', precio: PRECIO, cupo: 10 }],
    },
  });
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

  // ---- Un comprador también guarda texto que ven otros: el nombre del titular sale en su entrada y en la
  // puerta. ServicioDeVentas solo le rechaza los caracteres de control (los saltos de línea fingirían renglones
  // del log); el HTML se guarda y se devuelve como texto.
  const T = await sesion('titular@palco.test');
  const nombre = `${IMAGEN} ${SCRIPT} Prueba`;
  const compra = await http('POST', '/api/v1/ventas', {
    token: T.token, clave: uuid(),
    cuerpo: {
      eventoId: alta.json.id, items: [{ sectorId: alta.json.sectores[0].id, cantidad: 1 }],
      titulares: [{ nombre, dni: dniAlAzar() }],
    },
  });
  informe.igual('una compra con HTML en el nombre del titular', compra.estado, 201);
  if (compra.estado !== 201) return;
  const emitida = await pagarYEsperar(T.token, compra.json.id);
  const entrada = emitida ? await http('GET', `/api/v1/entradas/${emitida.entradas[0].id}`, { token: T.token }) : null;
  informe.ok('la entrada emitida: JSON con el nombre del titular como texto',
    entrada?.estado === 200 && entrada.tipo.startsWith('application/json') && entrada.json.titular?.nombre === nombre,
    emitida ? `${entrada.estado} ${entrada.tipo} "${entrada.json?.titular?.nombre}"` : 'la compra no se emitió en 30 segundos');
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

  // JBossWS (Apache CXF) lee el sobre con StAX: ReadHeadersInterceptor busca el primer elemento con nextTag(),
  // que corta con un error al encontrar la DTD. CXF lo responde como SOAP Fault de tipo Client (HTTP 500 en
  // SOAP 1.1) antes de leer el cuerpo, así que ninguna entidad se expande y el pedido no llega a ArcaSimulado.
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
    informe.ok(`${nombre}: 500 con SOAP Fault, sin respuesta del servicio ni contenido expandido`,
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

// ===================================================================================== E. cookies, cabeceras y datos personales

/** La app por nginx, con TLS, como la abre quien usa Palco con el compose. */
const WEB = (process.env.PALCO_WEB_URL ?? 'https://localhost').replace(/\/$/, '');
/** Un origen que no está en PALCO_CORS_ORIGENES. */
const AJENO = 'https://sitio-ajeno.example';

/**
 * Un pedido que devuelve todas las cabeceras de la respuesta, Set-Cookie incluida: http() de lib.mjs devuelve solo
 * las que usan los escenarios. Va con node:http y node:https porque el fetch de Node no acepta el certificado de
 * nginx, que es autofirmado y se genera al arrancar el contenedor: por https no se lo verifica, porque lo que se
 * prueba son las cabeceras y no el certificado.
 */
function crudo(base, metodo, ruta, { token, clave, cuerpo, cabeceras = {} } = {}) {
  const url = new URL(base + ruta);
  const h = { ...cabeceras };
  if (token) h.Authorization = `Bearer ${token}`;
  if (clave) h['Idempotency-Key'] = clave;
  const body = cuerpo === undefined ? undefined : JSON.stringify(cuerpo);
  if (body !== undefined) {
    h['Content-Type'] = 'application/json';
    h['Content-Length'] = Buffer.byteLength(body);
  }
  const seguro = url.protocol === 'https:';
  const opciones = { method: metodo, headers: h, ...(seguro ? { rejectUnauthorized: false } : {}) };
  return new Promise((listo, falla) => {
    const pedido = (seguro ? https : httpPlano).request(url, opciones, (r) => {
      const partes = [];
      r.on('data', (parte) => partes.push(parte));
      r.on('end', () => {
        const texto = Buffer.concat(partes).toString('utf8');
        let json = null;
        try { json = texto ? JSON.parse(texto) : null; } catch { /* no es JSON */ }
        listo({ estado: r.statusCode, cabeceras: r.headers, texto, json });
      });
    });
    pedido.setTimeout(30000, () => pedido.destroy(new Error(`${metodo} ${url} no respondió en 30 segundos`)));
    pedido.on('error', falla);
    pedido.end(body);
  });
}

/** Un camino hasta la API (nginx o directo al backend) y cada respuesta que dio, con el nombre de su pedido. */
function camino(nombre, base) {
  const vistas = [];
  const pedir = async (metodo, ruta, opciones = {}, etiqueta = `${metodo} ${ruta}`) => {
    const r = await crudo(base, metodo, ruta, opciones);
    vistas.push({ etiqueta, ...r });
    return r;
  };
  return { nombre, base, vistas, pedir };
}

const cookiesDe = (r) => r.cabeceras['set-cookie'] ?? [];

/** Los atributos que pide la cátedra y que le faltan a una cookie (el texto de un Set-Cookie). */
function lesFalta(cookie) {
  const atributos = cookie.split(';').slice(1).map((a) => a.trim().split('=')[0].toLowerCase());
  return ['Secure', 'HttpOnly', 'SameSite'].filter((a) => !atributos.includes(a.toLowerCase()));
}

/** Las rutas (como "usuario.dni" o "[3].organizadorId") de las claves que cumplen el patrón, a cualquier profundidad. */
function clavesQue(valor, patron, ruta = '') {
  if (Array.isArray(valor)) return valor.flatMap((v, i) => clavesQue(v, patron, `${ruta}[${i}]`));
  if (!valor || typeof valor !== 'object') return [];
  return Object.entries(valor).flatMap(([clave, v]) => {
    const aca = ruta ? `${ruta}.${clave}` : clave;
    return [...(patron.test(clave) ? [aca] : []), ...clavesQue(v, patron, aca)];
  });
}

/** Claves que no pueden salir nunca en una respuesta. */
const SECRETAS = /pass|hash|contras|salt|secreto/i;
/** Claves con datos de una persona: no van en lo que es público (EventoDto no tiene ninguna). */
const PERSONALES = new RegExp('^(email|dni|apellido|telefono|fechaNacimiento|usuario|usuarioId|organizadorId|compradorId'
  + '|vendedorId|titular|titulares)$', 'i');
/** Los campos de UsuarioDto: los de la propia cuenta, sin contraseña ni hash. */
const CAMPOS_DE_LA_CUENTA = ['apellido', 'dni', 'email', 'id', 'identidadVerificada', 'nombre', 'rol'];
const soloLaCuenta = (u) => Boolean(u) && JSON.stringify(Object.keys(u).sort()) === JSON.stringify(CAMPOS_DE_LA_CUENTA);

const verCors = (r) => `${r.estado}, Allow-Origin ${r.cabeceras['access-control-allow-origin'] ?? '(no vino)'}, `
  + `Allow-Credentials ${r.cabeceras['access-control-allow-credentials'] ?? '(no vino)'}`;

/**
 * El recorrido de un camino: lo que hace quien usa Palco (ingresar, mirar el catálogo, comprar) y los errores más
 * comunes. Las comprobaciones de los datos de cada respuesta van acá; las de cookies y cabeceras, sobre todas las
 * respuestas juntas, en bloqueE.
 */
async function recorrer(c, evento, organizador) {
  // La página y uno de sus archivos los sirve nginx: /assets tiene sus propios add_header (nginx.conf).
  if (c.base === WEB) {
    const pagina = await c.pedir('GET', '/');
    const archivo = pagina.texto.match(/(?:src|href)="(\/assets\/[^"]+)"/)?.[1];
    informe.ok(`${c.nombre}: GET / sirve la app y enlaza sus archivos de /assets`,
      pagina.estado === 200 && Boolean(archivo), `${pagina.estado} ${archivo ?? 'sin archivos de /assets'}`);
    if (archivo) await c.pedir('GET', archivo, {}, 'GET /assets/(archivo de la app)');
  }

  // Ingresar mal, con una cuenta que existe y con una que no: el mismo 401, que no repite lo que se mandó. Un
  // intento fallido cuenta para el bloqueo (5 en 15 minutos); el ingreso bueno de abajo pone la cuenta en cero.
  const claveMala = `clave-equivocada-${Date.now()}`;
  const inexistente = `nadie.${Date.now()}@palco.test`;
  const malIngreso = await c.pedir('POST', '/api/usuarios/login',
    { cuerpo: { email: 'titular@palco.test', password: claveMala } }, 'POST /api/usuarios/login (contraseña incorrecta)');
  const sinCuenta = await c.pedir('POST', '/api/usuarios/login',
    { cuerpo: { email: inexistente, password: claveMala } }, 'POST /api/usuarios/login (cuenta que no existe)');
  informe.ok(`${c.nombre}: el login fallido es el mismo 401 exista o no la cuenta, sin el email ni la contraseña`,
    [malIngreso, sinCuenta].every((r) => r.estado === 401 && !r.texto.includes(claveMala) && !r.texto.includes('@'))
      && malIngreso.json?.type === sinCuenta.json?.type && malIngreso.json?.detail === sinCuenta.json?.detail,
    `${malIngreso.estado} "${malIngreso.json?.detail}" / ${sinCuenta.estado} "${sinCuenta.json?.detail}"`);

  const ingreso = await c.pedir('POST', '/api/usuarios/login', { cuerpo: { email: 'titular@palco.test', password: PASSWORD } });
  if (ingreso.estado !== 200) throw new Error(`login de titular@palco.test: ${ingreso.estado} ${ingreso.texto.slice(0, 200)}`);
  const { token } = ingreso.json;
  const yo = await c.pedir('GET', '/api/usuarios/yo', { token });
  const secretas = [...clavesQue(ingreso.json, SECRETAS), ...clavesQue(yo.json, SECRETAS)];
  informe.ok(`${c.nombre}: el login y GET /api/usuarios/yo traen solo los datos de la propia cuenta, sin contraseña ni hash`,
    yo.estado === 200 && soloLaCuenta(ingreso.json.usuario) && soloLaCuenta(yo.json) && secretas.length === 0
      && !ingreso.texto.includes(PASSWORD) && !yo.texto.includes(PASSWORD),
    `${yo.estado}, campos ${Object.keys(yo.json ?? {}).join(', ')}${secretas.length ? `; de más: ${secretas.join(', ')}` : ''}`);

  // Un token con la firma cambiada: el 401 no devuelve el token ni su contenido (que lleva el email de la cuenta).
  const [cabecera, contenido, firma] = token.split('.');
  const alterado = `${cabecera}.${contenido}.${firma.startsWith('A') ? 'B' : 'A'}${firma.slice(1)}`;
  const rechazo = await c.pedir('GET', '/api/usuarios/yo', { token: alterado }, 'GET /api/usuarios/yo (token alterado)');
  informe.ok(`${c.nombre}: el 401 por un token alterado no repite el token ni su contenido`,
    rechazo.estado === 401 && !rechazo.texto.includes(contenido) && !rechazo.texto.includes(firma.slice(1)),
    `${rechazo.estado} ${rechazo.texto.slice(0, 160)}`);

  // Lo público, sin sesión: el catálogo y el detalle de un evento.
  const catalogo = await c.pedir('GET', '/api/eventos');
  const detalle = await c.pedir('GET', `/api/eventos/${evento.slug}`, {}, 'GET /api/eventos/{slug}');
  const personales = [...clavesQue(catalogo.json, PERSONALES), ...clavesQue(detalle.json, PERSONALES)];
  const delOrganizador = [['id', organizador.id], ['email', organizador.email], ['DNI', organizador.dni]]
    .filter(([, dato]) => catalogo.texto.includes(dato) || detalle.texto.includes(dato)).map(([nombre]) => nombre);
  informe.ok(`${c.nombre}: el catálogo y el detalle (públicos) no traen datos de personas, ni el id, el email o el DNI `
    + 'del organizador dueño de los eventos',
    catalogo.estado === 200 && detalle.estado === 200 && personales.length === 0 && delOrganizador.length === 0,
    `${catalogo.estado}/${detalle.estado}, ${Array.isArray(catalogo.json) ? catalogo.json.length : 0} eventos; `
      + `campos personales: ${personales.slice(0, 5).join(', ') || 'ninguno'}; `
      + `del organizador: ${delOrganizador.join(', ') || 'nada'}`);

  // Una compra de punta a punta: abrirla, pagarla con el simulador y esperar la emisión, y leer la entrada.
  const venta = await c.pedir('POST', '/api/v1/ventas', {
    token, clave: uuid(),
    cuerpo: {
      eventoId: evento.id, items: [{ sectorId: evento.sectores[0].id, cantidad: 1 }],
      titulares: [{ nombre: 'Prueba Cookies', dni: dniAlAzar() }],
    },
  });
  if (venta.estado !== 201) throw new Error(`POST /api/v1/ventas: ${venta.estado} ${venta.texto.slice(0, 200)}`);
  const ventaId = venta.json.id;
  await c.pedir('POST', `/api/v1/ventas/${ventaId}/pagos`,
    { token, clave: uuid(), cuerpo: { medioDePago: 'tarjeta', token: 'tok_test' } }, 'POST /api/v1/ventas/{id}/pagos');
  const emitida = await hasta(async () => {
    const v = await c.pedir('GET', `/api/v1/ventas/${ventaId}`, { token }, 'GET /api/v1/ventas/{id}');
    return v.json?.estado === 'EMITIDA' ? v : null;
  }, { maximoMs: 30000 });
  informe.ok(`${c.nombre}: la compra de una entrada se abre, se paga y se emite`, Boolean(emitida),
    emitida ? `${ventaId} EMITIDA` : `${ventaId} no se emitió en 30 segundos`);
  if (emitida) {
    await c.pedir('GET', `/api/v1/entradas/${emitida.json.entradas[0].id}`, { token }, 'GET /api/v1/entradas/{id}');
  }

  // CORS: un origen ajeno, en un GET y en el preflight de una compra, y el origen de la app.
  const ajeno = await c.pedir('GET', '/api/eventos', { cabeceras: { Origin: AJENO } }, `GET /api/eventos (Origin ${AJENO})`);
  const preflight = await c.pedir('OPTIONS', '/api/v1/ventas', {
    cabeceras: {
      Origin: AJENO, 'Access-Control-Request-Method': 'POST',
      'Access-Control-Request-Headers': 'authorization, content-type, idempotency-key',
    },
  }, `OPTIONS /api/v1/ventas (preflight, Origin ${AJENO})`);
  informe.ok(`${c.nombre}: a un Origin ajeno no le responde Access-Control-Allow-Origin ni Allow-Credentials, ni en un GET `
    + 'ni en el preflight de una compra',
    [ajeno, preflight].every((r) => !r.cabeceras['access-control-allow-origin'] && !r.cabeceras['access-control-allow-credentials']),
    `GET ${verCors(ajeno)} | OPTIONS ${verCors(preflight)}`);
  const permitido = new URL(WEB).origin;
  const propio = await c.pedir('GET', '/api/eventos', { cabeceras: { Origin: permitido } }, `GET /api/eventos (Origin ${permitido})`);
  informe.ok(`${c.nombre}: al origen de la app (${permitido}) le responde ese origen exacto y no *, con Allow-Credentials `
    + 'y Vary: Origin',
    propio.cabeceras['access-control-allow-origin'] === permitido
      && propio.cabeceras['access-control-allow-credentials'] === 'true' && /\borigin\b/i.test(propio.cabeceras.vary ?? ''),
    `${verCors(propio)}, Vary ${propio.cabeceras.vary ?? '(no vino)'}`);
}

/** Las tres cabeceras que pide la cátedra. nginx.conf las pone con "always": también en los errores. */
const CABECERAS = [
  ['Content-Security-Policy', "default-src 'self' y script-src sin 'unsafe-inline', 'unsafe-eval' ni comodines", (valor) => {
    const directivas = Object.fromEntries((valor ?? '').split(';').map((d) => d.trim().split(/\s+/))
      .filter(([nombre]) => nombre).map(([nombre, ...fuentes]) => [nombre.toLowerCase(), fuentes]));
    const script = directivas['script-src'] ?? directivas['default-src'] ?? [];
    return directivas['default-src']?.join(' ') === "'self'" && script.length > 0
      && !script.some((f) => ["'unsafe-inline'", "'unsafe-eval'", '*', 'data:', 'http:', 'https:'].includes(f));
  }],
  ['Strict-Transport-Security', 'max-age de un año o más',
    (valor) => Number(/max-age=(\d+)/i.exec(valor ?? '')?.[1] ?? 0) >= 31536000],
  ['X-Content-Type-Options', 'nosniff', (valor) => (valor ?? '').trim().toLowerCase() === 'nosniff'],
];

async function bloqueE() {
  const web = camino(`por nginx (${WEB})`, WEB);
  const directo = camino(`directo al backend (${BASE})`, BASE);
  // El dueño de los eventos de las migraciones y de los de prueba: sus datos no pueden salir en lo público.
  const organizador = (await http('POST', '/api/usuarios/login',
    { cuerpo: { email: 'organizador@palco.test', password: PASSWORD } })).json?.usuario;
  if (!organizador) throw new Error('No se pudo ingresar con organizador@palco.test');
  const evento = await eventoPropio('Prueba cookies');

  for (const c of [web, directo]) {
    try {
      await recorrer(c, evento, organizador);
    } catch (e) {
      const sinNginx = c.base === WEB && c.vistas.length === 0;
      informe.ok(`${c.nombre}: el recorrido terminó sin errores`, false,
        `${e.message || e.code || e}${sinNginx ? ' (nginx no respondió: levantá el compose o definí PALCO_WEB_URL)' : ''}`);
    }
  }

  // Cookies: Palco no usa ninguna. Si alguna respuesta trae Set-Cookie, se muestra su nombre (no su valor) y qué
  // le falta de Secure, HttpOnly y SameSite.
  for (const c of [web, directo]) {
    if (c.vistas.length === 0) continue;
    const conCookie = c.vistas.filter((v) => cookiesDe(v).length > 0);
    informe.ok(`${c.nombre}: ninguna de las ${c.vistas.length} respuestas del recorrido trae Set-Cookie`, conCookie.length === 0,
      conCookie.length === 0
        ? [...new Set(c.vistas.map((v) => v.etiqueta))].join(', ')
        : conCookie.map((v) => `${v.etiqueta}: ${cookiesDe(v).map((k) => `${k.split('=')[0]} (le falta: `
          + `${lesFalta(k).join(', ') || 'nada'})`).join('; ')}`).join(' | '));
  }

  // Cabeceras de seguridad: en todo lo que responde nginx, la página, /assets, la API y sus errores.
  if (web.vistas.length > 0) {
    for (const [nombre, regla, cumple] of CABECERAS) {
      const clave = nombre.toLowerCase();
      const sinEllas = web.vistas.filter((v) => !cumple(v.cabeceras[clave]));
      informe.ok(`${web.nombre}: ${nombre} (${regla}) en las ${web.vistas.length} respuestas`, sinEllas.length === 0,
        sinEllas.length === 0
          ? web.vistas[0].cabeceras[clave]
          : sinEllas.map((v) => `${v.etiqueta}: ${v.cabeceras[clave] ?? 'no vino'}`).join(' | '));
    }
  }

  // CORS con comodín: en ninguna respuesta de los dos caminos, y menos junto con Allow-Credentials.
  const todas = [...web.vistas, ...directo.vistas];
  const comodin = todas.filter((v) => (v.cabeceras['access-control-allow-origin'] ?? '').trim() === '*');
  informe.ok(`en ninguna de las ${todas.length} respuestas Access-Control-Allow-Origin es * (con o sin Allow-Credentials)`,
    todas.length > 0 && comodin.length === 0,
    comodin.map((v) => `${v.etiqueta}: ${verCors(v)}`).join(' | ') || 'ninguna');
}

// ===================================================================================== corrida

console.log(`Pruebas de seguridad de Palco. Servidor: ${BASE}. ${new Date().toLocaleString('es-AR', { hour12: false })}`);
await bloque('A', 'Tokens JWT manipulados: 401 en cada servicio', bloqueA);
await bloque('B', 'Mass assignment: los campos que fija el servidor no los cambia el cliente', bloqueB);
await bloque('C', 'XSS almacenado: el HTML de un evento y de un titular se guarda y se devuelve como texto', bloqueC);
await bloque('D', 'XXE en el canal SOAP (/legado/arca): el DOCTYPE se rechaza sin expandir las entidades', bloqueD);
await bloque('E', 'Sin cookies, con CSP, HSTS y nosniff, CORS sin comodín y sin datos personales en las respuestas',
  bloqueE);

const fallas = informe.resumen();
const total = informe.escenarios.reduce((suma, e) => suma + e.comprobaciones.length, 0);
console.log(`\n${total - fallas} de ${total} comprobaciones`);
console.log(fallas === 0 ? 'TODO BIEN' : `HAY ${fallas} COMPROBACIONES QUE FALLAN`);
process.exit(fallas === 0 ? 0 : 1);
