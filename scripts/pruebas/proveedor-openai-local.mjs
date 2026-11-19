// Un proveedor de IA de prueba con la API de chat de OpenAI (POST /v1/chat/completions), para probar
// de punta a punta el adaptador ModeloCompatibleOpenAI y el gateway del asistente sin una clave paga
// y sin salir de esta máquina. No es un modelo de lenguaje: contesta con reglas fijas.
//
// Muestra en la consola cada pedido que recibe: las cabeceras (de Authorization solo dice si vino,
// nunca el valor), el modelo y cada mensaje con su rol. Así se ve que las instrucciones y los datos
// de Palco llegan en el mensaje system y la pregunta, sola, en el mensaje user.
//
// Uso: node scripts/pruebas/proveedor-openai-local.mjs [--puerto=8089] [--modo=normal]
// El servidor de Palco lo usa con PALCO_ASISTENTE_MODELO=openai y
// PALCO_ASISTENTE_OPENAI_URL=http://127.0.0.1:8089/v1 (ver scripts/LEEME.md).
// Solo usa módulos que trae Node: no hace falta npm install.
//
// Modos (cómo contesta POST /v1/chat/completions):
//   normal     200: cita el dato de Palco que corresponde a la pregunta, tal como llegó en el mensaje system
//   lento      como normal, pero tarda 8 segundos (más que los 5 que espera el gateway)
//   401        el error de OpenAI para una clave inválida
//   500        el error de OpenAI para una falla de su servidor
//   json-roto  200 con el JSON cortado a la mitad
//   con-dni    200 con un DNI en el texto
//   con-email  200 con un email en el texto
// Control, para asistente-proveedor.mjs o para curl:
//   POST /control/modo/<modo>[?demoraMs=N]   cambia el modo hasta el próximo cambio (demoraMs: la del modo lento)
//   GET  /control                            el modo y cuántos pedidos llegaron
//   GET  /control/recibidos[?desde=N]        los pedidos recibidos después del N.º N
import http from 'node:http';

const argumento = (nombre) => process.argv.slice(2).find((a) => a.startsWith(`--${nombre}=`))?.slice(nombre.length + 3);
const PUERTO = Number(argumento('puerto') ?? process.env.PROVEEDOR_PUERTO ?? 8089);
// Solo esta máquina. Para un servidor en Docker hace falta PROVEEDOR_HOST=0.0.0.0.
const HOST = process.env.PROVEEDOR_HOST ?? '127.0.0.1';
const DEMORA_POR_DEFECTO_MS = 8000;
const MODOS = ['normal', 'lento', '401', '500', 'json-roto', 'con-dni', 'con-email'];

const TEXTO_CON_DNI = 'La entrada E-1042 está a nombre de Juan Pérez, DNI 30.123.456.';
const TEXTO_CON_EMAIL = 'Podés escribirle al titular de esa entrada a juan.perez@ejemplo.com.';

let modo = argumento('modo') ?? 'normal';
let demoraMs = DEMORA_POR_DEFECTO_MS;
const recibidos = [];
let ultimoSystem = null;

if (!MODOS.includes(modo)) {
  console.error(`Modo desconocido: ${modo}. Los posibles son ${MODOS.join(', ')}.`);
  process.exit(2);
}

// ------------------------------------------------------------------------------------- respuestas

const normalizar = (texto) => texto.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();

/** Las líneas de "Datos de Palco que podés usar" del mensaje system, sin el guion. */
function datosDePalco(system) {
  const lineas = system.split(/\r?\n/);
  const inicio = lineas.findIndex((l) => l.includes('Datos de Palco'));
  if (inicio < 0) return [];
  return lineas.slice(inicio + 1).filter((l) => l.startsWith('- ')).map((l) => l.slice(2).trim());
}

// Qué dato de Palco corresponde a cada pregunta: el primer tema cuyas palabras aparecen en ella.
const TEMAS = [
  { palabras: ['revend', 'reventa', 'publicar', 'tope', 'maximo'], dato: 'reventa' },
  { palabras: ['cuantas entradas', 'cuantas puedo', 'por compra'], dato: 'por compra' },
  { palabras: ['minutos', 'reserva', 'tiempo para pagar'], dato: 'minutos' },
  { palabras: ['cargo', 'comision', 'me cobran'], dato: 'cargo de servicio' },
  { palabras: ['puerta', 'llevar', 'entrar al'], dato: 'puerta' },
  { palabras: ['cancel', 'reembols', 'devuel'], dato: 'cancela' },
  { palabras: ['factura'], dato: 'factura' },
  { palabras: ['mis entradas', 'mis compras', 'qr'], dato: 'mis entradas' },
];

/** La respuesta del modo normal: cita textual del dato que llegó en el mensaje system. */
function responder(system, pregunta) {
  const datos = datosDePalco(system);
  if (datos.length === 0) return 'No me llegaron los datos de Palco en las instrucciones: no puedo responder sin inventar.';
  const p = normalizar(pregunta);
  const tema = TEMAS.find((t) => t.palabras.some((palabra) => p.includes(palabra)));
  const linea = tema && datos.find((d) => normalizar(d).includes(tema.dato));
  if (!linea) return 'Con los datos que me pasó Palco no puedo responder eso. Puedo ayudarte con compras, entradas, reventa y la puerta.';
  return `Según los datos que me pasó Palco: «${linea}»`;
}

const completado = (numero, modelo, texto, pedido) => JSON.stringify({
  id: `chatcmpl-local-${numero}`,
  object: 'chat.completion',
  created: Math.floor(Date.now() / 1000),
  model: modelo ?? 'desconocido',
  choices: [{ index: 0, message: { role: 'assistant', content: texto }, finish_reason: 'stop' }],
  usage: {
    prompt_tokens: Math.ceil(pedido.length / 4),
    completion_tokens: Math.ceil(texto.length / 4),
    total_tokens: Math.ceil(pedido.length / 4) + Math.ceil(texto.length / 4),
  },
});

const errorOpenAI = (mensaje, tipo, codigo = null) => JSON.stringify({ error: { message: mensaje, type: tipo, param: null, code: codigo } });

// ------------------------------------------------------------------------------------- consola

const hora = () => new Date().toLocaleTimeString('es-AR', { hour12: false });
const log = (texto) => console.log(texto);
const OCULTAS = new Set(['authorization', 'proxy-authorization', 'cookie', 'api-key', 'x-api-key']);

/** Las cabeceras del pedido, sin las que pueden traer una clave. */
function cabecerasVisibles(cabeceras) {
  return Object.fromEntries(Object.entries(cabeceras).filter(([nombre]) => !OCULTAS.has(nombre)));
}

function mostrarPedido(r, cuerpoInvalido) {
  log(`[${hora()}] #${r.numero} ${r.ruta}, modo ${r.modo}`);
  const cabeceras = Object.entries(r.cabeceras).map(([n, v]) => `${n}=${v}`);
  cabeceras.push(`authorization=${r.autorizacion === 'presente' ? '(vino; no se muestra)' : '(no vino)'}`);
  log(`   cabeceras: ${cabeceras.join(', ')}`);
  if (cuerpoInvalido !== undefined) {
    log(`   cuerpo que no es un pedido de chat (${cuerpoInvalido.length} caracteres): ${cuerpoInvalido.slice(0, 200)}`);
    return;
  }
  log(`   model=${r.modelo}, temperature=${r.temperatura}, max_tokens=${r.maxTokens}`);
  r.mensajes.forEach((m, i) => {
    const encabezado = `   mensaje ${i + 1} de ${r.mensajes.length}, role=${m.role}`;
    if (m.role === 'system' && ultimoSystem && m.content === ultimoSystem.content) {
      log(`${encabezado}: el mismo del pedido #${ultimoSystem.numero} (${m.content.length} caracteres)`);
    } else if (m.role === 'system' || m.content.includes('\n')) {
      log(`${encabezado} (${m.content.length} caracteres):`);
      for (const linea of m.content.split(/\r?\n/)) log(`   │ ${linea}`);
      if (m.role === 'system') ultimoSystem = { numero: r.numero, content: m.content };
    } else {
      log(`${encabezado}: ${JSON.stringify(m.content)}`);
    }
  });
}

// ------------------------------------------------------------------------------------- HTTP

function leerCuerpo(req) {
  return new Promise((listo, fallo) => {
    const partes = [];
    req.on('data', (p) => partes.push(p));
    req.on('end', () => listo(Buffer.concat(partes).toString('utf8')));
    req.on('error', fallo);
  });
}

function json(res, estado, cuerpo) {
  const texto = typeof cuerpo === 'string' ? cuerpo : JSON.stringify(cuerpo);
  // Como OpenAI: application/json, sin charset (JSON va siempre en UTF-8).
  res.writeHead(estado, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(texto) });
  res.end(texto);
}

function contestar(res, r, llegada, estado, cuerpo, texto = null) {
  r.estado = estado;
  r.respuesta = texto;
  r.ms = Date.now() - llegada;
  json(res, estado, cuerpo);
  log(`   respondió ${estado} a los ${r.ms} ms: ${texto ?? cuerpo}`);
}

async function completar(req, res) {
  const llegada = Date.now();
  const cuerpo = await leerCuerpo(req);
  const r = {
    numero: recibidos.length + 1, hora: new Date(llegada).toISOString(), modo, ruta: `${req.method} ${req.url}`,
    cabeceras: cabecerasVisibles(req.headers), autorizacion: req.headers.authorization ? 'presente' : 'ausente',
    modelo: null, temperatura: null, maxTokens: null, mensajes: [], estado: null, respuesta: null, cortado: false, ms: null,
  };
  recibidos.push(r);

  let pedido = null;
  try { pedido = JSON.parse(cuerpo); } catch { /* no es JSON */ }
  if (!pedido || !Array.isArray(pedido.messages)) {
    mostrarPedido(r, cuerpo);
    return contestar(res, r, llegada, 400, errorOpenAI('We could not parse the JSON body of your request.', 'invalid_request_error'));
  }
  r.modelo = pedido.model ?? null;
  r.temperatura = pedido.temperature ?? null;
  r.maxTokens = pedido.max_tokens ?? null;
  r.mensajes = pedido.messages.map((m) => ({ role: m?.role, content: String(m?.content ?? '') }));
  mostrarPedido(r);

  const system = r.mensajes.filter((m) => m.role === 'system').map((m) => m.content).join('\n');
  const pregunta = r.mensajes.filter((m) => m.role === 'user').map((m) => m.content).join('\n');
  const ok = (texto) => contestar(res, r, llegada, 200, completado(r.numero, r.modelo, texto, cuerpo), texto);

  switch (r.modo) {
    case 'normal':
      return ok(responder(system, pregunta));
    case 'lento': {
      const espera = demoraMs;
      log(`   espera ${espera} ms antes de responder`);
      const temporizador = setTimeout(() => ok(responder(system, pregunta)), espera);
      res.on('close', () => {
        if (res.writableFinished) return;
        clearTimeout(temporizador);
        r.cortado = true;
        r.ms = Date.now() - llegada;
        log(`   #${r.numero}: el cliente cortó la conexión a los ${r.ms} ms, sin esperar la respuesta`);
      });
      return undefined;
    }
    case '401':
      return contestar(res, r, llegada, 401, errorOpenAI('Incorrect API key provided: sk-prueb****. You can find your API key at'
        + ' https://platform.openai.com/account/api-keys.', 'invalid_request_error', 'invalid_api_key'));
    case '500':
      return contestar(res, r, llegada, 500, errorOpenAI('The server had an error while processing your request. Sorry about that!',
        'server_error'));
    case 'json-roto': {
      const entero = completado(r.numero, r.modelo, responder(system, pregunta), cuerpo);
      return contestar(res, r, llegada, 200, entero.slice(0, Math.floor(entero.length / 2)));
    }
    case 'con-dni':
      return ok(TEXTO_CON_DNI);
    case 'con-email':
      return ok(TEXTO_CON_EMAIL);
    default:
      return contestar(res, r, llegada, 500, errorOpenAI(`Modo sin implementar: ${r.modo}`, 'server_error'));
  }
}

const estado = () => ({ proveedor: 'proveedor-openai-local', modo, demoraMs, recibidos: recibidos.length });

const servidor = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  try {
    if (req.method === 'POST' && url.pathname === '/v1/chat/completions') return await completar(req, res);
    if (req.method === 'GET' && url.pathname === '/control') return json(res, 200, estado());
    if (req.method === 'GET' && url.pathname === '/control/recibidos') {
      const desde = Number(url.searchParams.get('desde') ?? 0);
      return json(res, 200, recibidos.filter((r) => r.numero > desde));
    }
    const cambio = req.method === 'POST' && url.pathname.match(/^\/control\/modo\/([\w-]+)$/);
    if (cambio) {
      if (!MODOS.includes(cambio[1])) return json(res, 400, { error: `Modo desconocido: ${cambio[1]}. Los posibles son ${MODOS.join(', ')}.` });
      modo = cambio[1];
      demoraMs = Number(url.searchParams.get('demoraMs') ?? DEMORA_POR_DEFECTO_MS);
      log(`[${hora()}] modo ${modo}${modo === 'lento' ? ` (${demoraMs} ms)` : ''}`);
      return json(res, 200, estado());
    }
    // La ruta de la API es /v1/chat/completions: si llega otra, la dirección configurada en el servidor no termina en /v1.
    log(`[${hora()}] ${req.method} ${url.pathname}: 404, la API de chat está en POST /v1/chat/completions`);
    return json(res, 404, errorOpenAI(`Unknown request URL: ${req.method} ${url.pathname}.`, 'invalid_request_error', 'unknown_url'));
  } catch (e) {
    log(`[${hora()}] error atendiendo ${req.method} ${url.pathname}: ${e.message}`);
    if (!res.headersSent) json(res, 500, errorOpenAI('Error interno del proveedor de prueba.', 'server_error'));
    return undefined;
  }
});

// Node cierra una conexión keep-alive ociosa a los 5 segundos. Los clientes (el de WildFly y el del script) la
// reutilizan, y una prueba puede quedar quieta más que eso (el modo lento, una llamada a jboss-cli): con un
// minuto de margen no se encuentran con una conexión que el proveedor acaba de cerrar.
servidor.keepAliveTimeout = 60_000;

servidor.on('error', (e) => {
  console.error(e.code === 'EADDRINUSE'
    ? `El puerto ${PUERTO} está ocupado. Elegí otro con --puerto=N (y la misma dirección en PALCO_ASISTENTE_OPENAI_URL).`
    : `No se pudo levantar el proveedor de prueba: ${e.message}`);
  process.exit(1);
});

servidor.listen(PUERTO, HOST, () => {
  log(`Proveedor de prueba con la API de chat de OpenAI en http://${HOST}:${PUERTO}/v1 (modo ${modo}).`);
  log(`En el servidor: PALCO_ASISTENTE_MODELO=openai PALCO_ASISTENTE_OPENAI_URL=http://127.0.0.1:${PUERTO}/v1`);
  log(`Modos: ${MODOS.join(', ')}. Para cambiarlo: curl -X POST http://127.0.0.1:${PUERTO}/control/modo/lento`);
});

for (const senal of ['SIGINT', 'SIGTERM']) {
  process.on(senal, () => {
    servidor.close();
    process.exit(0);
  });
}
