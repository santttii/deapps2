// Genera docs/api/palco-api.postman_collection.json, para no escribir a mano el JSON de la colección.
// La colección del repo sale siempre de acá: un cambio se hace en este archivo y se regenera.
//
//   node scripts/postman/generar.js                 # escribe docs/api/palco-api.postman_collection.json
//   node scripts/postman/generar.js "<otra ruta>"   # la escribe en otro lado (por ejemplo, para comparar)
//
// Solo usa Node: ningún paquete de npm.
const fs = require('fs');
const path = require('path');

const salida = process.argv[2]
  || path.join(__dirname, '..', '..', 'docs', 'api', 'palco-api.postman_collection.json');

// ---------------------------------------------------------------------------------------------
// Ayudas para armar los items

const JSON_CT = { key: 'Content-Type', value: 'application/json' };
const bearer = (variable) => ({ key: 'Authorization', value: 'Bearer {{' + variable + '}}' });
const clave = (valor) => ({ key: 'Idempotency-Key', value: valor });

function url(base, ruta = [], query = []) {
  const raw = '{{' + base + '}}' + (ruta.length ? '/' + ruta.join('/') : '')
    + (query.filter((q) => !q.disabled).length
      ? '?' + query.filter((q) => !q.disabled).map((q) => q.key + '=' + q.value).join('&')
      : '');
  return { raw, host: ['{{' + base + '}}'], path: ruta, query };
}

function pedido(nombre, metodo, direccion, opciones = {}) {
  const item = {
    name: nombre,
    request: {
      method: metodo,
      header: opciones.cabeceras || [],
      url: direccion,
    },
    event: [],
  };
  if (opciones.descripcion) {
    item.request.description = opciones.descripcion;
  }
  if (opciones.cuerpo !== undefined) {
    item.request.body = {
      mode: 'raw',
      raw: typeof opciones.cuerpo === 'string' ? opciones.cuerpo : JSON.stringify(opciones.cuerpo, null, 2),
      options: { raw: { language: 'json' } },
    };
  }
  if (opciones.antes) {
    item.event.push({ listen: 'prerequest', script: { type: 'text/javascript', exec: opciones.antes } });
  }
  item.event.push({ listen: 'test', script: { type: 'text/javascript', exec: opciones.tests || [] } });
  return item;
}

const carpeta = (nombre, descripcion, items) => ({ name: nombre, description: descripcion, item: items });

// ---------------------------------------------------------------------------------------------
// Fragmentos de tests que se repiten

const estado = (codigo) => `pm.test('responde ${codigo}', () => pm.response.to.have.status(${codigo}));`;

const esJson = "pm.test('responde application/json', () => pm.expect(pm.response.headers.get('Content-Type')).to.include('application/json'));";

const esProblema = "pm.test('el error viene como application/problem+json', () => pm.expect(pm.response.headers.get('Content-Type')).to.include('application/problem+json'));";

/** Un error del contrato: el código, application/problem+json y el type. */
const problema = (codigo, tipo) => [
  estado(codigo),
  esProblema,
  `pm.test('type es /problemas/${tipo}', () => {`,
  '  const p = pm.response.json();',
  `  pm.expect(p.type).to.eql('/problemas/${tipo}');`,
  `  pm.expect(p.status).to.eql(${codigo});`,
  "  pm.expect(p.title).to.be.a('string');",
  "  pm.expect(p.detail).to.be.a('string');",
  '});',
];

/** Sigue la corrida en otro request (o la termina, con null). Sirve en el Runner y en Newman. */
const saltarA = (destino) => [
  'if (pm.execution && pm.execution.setNextRequest) {',
  `  pm.execution.setNextRequest(${destino});`,
  '} else {',
  `  postman.setNextRequest(${destino});`,
  '}',
];

const CAMPOS_VENTA = "'id', 'estado', 'facturaEstado', 'eventoId', 'items', 'subtotal', 'cargoServicio', 'montoTotal', 'creada', 'venceReserva', 'pagoId', 'entradas', '_links'";
const CAMPOS_ENTRADA = "'id', 'ventaId', 'eventoId', 'sectorId', 'sector', 'titular', 'precioPagado', 'estado', 'codigoQr', 'emitida', '_links'";
const INSTANTE_UTC = '/^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$/';

const nuevaClave = (variable) => [
  '// Una clave de idempotencia nueva en cada corrida. El request que repite el pedido usa la misma.',
  `pm.collectionVariables.set('${variable}', pm.variables.replaceIn('{{$guid}}'));`,
];

// ---------------------------------------------------------------------------------------------
// Datos de prueba

const TITULAR = { email: 'titular@palco.test', password: 'palco1234' };
const OTRO_COMPRADOR = { email: 'otro.titular@palco.test', password: 'palco1234' };
const ORGANIZADOR = { email: 'organizador@palco.test', password: 'palco1234' };
const STAFF = { email: 'puerta@palco.test', password: 'palco1234' };

const registro = (email, password) => ({
  nombre: 'Ana',
  apellido: 'Gomez',
  dni: '{{dniRegistro}}',
  fechaNacimiento: '1995-05-10',
  email,
  telefono: '1155550000',
  password,
});

const eventoDePrueba = (titulo, sectores) => ({
  titulo,
  categoria: 'Música',
  venue: 'Luna Park',
  fechaISO: '2027-12-31T21:00:00',
  descripcion: 'Evento creado por la colección de Postman. Se puede ignorar.',
  sectores,
});

const compra = (cantidad, titulares) => ({
  eventoId: '{{eventoId}}',
  items: [{ sectorId: '{{sectorId}}', cantidad }],
  titulares,
});

const ANA = { nombre: 'Ana Gómez', dni: '30123456' };
const LUIS = { nombre: 'Luis Pérez', dni: '31234567' };

const login = (nombre, cuenta, rol, variable, descripcion) => pedido(nombre, 'POST', url('baseUsuarios', ['login']), {
  descripcion,
  cabeceras: [JSON_CT],
  cuerpo: cuenta,
  tests: [
    estado(200),
    'const sesion = pm.response.json();',
    "pm.test('trae el token y cuánto dura', () => {",
    "  pm.expect(sesion.token).to.be.a('string').and.not.empty;",
    '  pm.expect(sesion.expiraEnSegundos).to.be.above(0);',
    '});',
    `pm.test('la cuenta es ${cuenta.email}, con rol ${rol}', () => {`,
    `  pm.expect(sesion.usuario.email).to.eql('${cuenta.email}');`,
    `  pm.expect(sesion.usuario.rol).to.eql('${rol}');`,
    '});',
    `pm.collectionVariables.set('${variable}', sesion.token);`,
  ],
});

// ---------------------------------------------------------------------------------------------
// 1 Usuarios

const PRIMERO_DE_EVENTOS = 'Listar eventos (200) guarda slug';

const usuarios = carpeta('1 Usuarios (implementado)',
  'Registro, login, perfil y claves públicas. Corre entera sin intervención. La subcarpeta "Pasos manuales" queda '
  + 'fuera de la corrida: necesita el código de verificación, que solo sale en el log del servidor.',
  [
    pedido('Registro (201)', 'POST', url('baseUsuarios', ['registro']), {
      descripcion: 'Registra una cuenta nueva. El email y el DNI se generan en cada corrida, así el request se puede '
        + 'repetir sin reiniciar el servidor. La cuenta queda sin verificar: para verificarla, ver "Pasos manuales".',
      cabeceras: [JSON_CT],
      cuerpo: registro('{{emailRegistro}}', 'Clave1234'),
      antes: [
        '// Email y DNI nuevos en cada corrida: el servicio rechaza los que ya están registrados.',
        "const sufijo = Date.now().toString(36) + Math.floor(Math.random() * 1000);",
        "pm.collectionVariables.set('emailRegistro', 'postman-' + sufijo + '@palco.test');",
        "pm.collectionVariables.set('dniRegistro', String(50000000 + Math.floor(Math.random() * 49999999)));",
      ],
      tests: [
        estado(201),
        "pm.test('devuelve el email registrado y un mensaje', () => {",
        '  const r = pm.response.json();',
        "  pm.expect(r.email).to.eql(pm.collectionVariables.get('emailRegistro'));",
        "  pm.expect(r.mensaje).to.be.a('string').and.not.empty;",
        '});',
      ],
    }),
    pedido('Registro inválido (400)', 'POST', url('baseUsuarios', ['registro']), {
      descripcion: 'Email mal formado y contraseña corta: responde 400 y dice qué campo está mal.',
      cabeceras: [JSON_CT],
      cuerpo: registro('mal', '123'),
      tests: [
        ...problema(400, 'datos-invalidos'),
        "pm.test('errores nombra el email y la contraseña', () => {",
        "  pm.expect(pm.response.json().errores).to.include.all.keys('email', 'password');",
        '});',
      ],
    }),
    pedido('Login con el email sin verificar (409)', 'POST', url('baseUsuarios', ['login']), {
      descripcion: 'La cuenta recién registrada todavía no verificó su email: no puede iniciar sesión.',
      cabeceras: [JSON_CT],
      cuerpo: { email: '{{emailRegistro}}', password: 'Clave1234' },
      tests: [
        ...problema(409, 'conflicto'),
        "pm.test('no entrega un token', () => pm.expect(pm.response.json()).to.not.have.property('token'));",
      ],
    }),
    login('Login (200) guarda token', TITULAR, 'user', 'token',
      'Inicia sesión con la cuenta de prueba de comprador y guarda el token en {{token}}. Como toda cuenta que '
      + 'completó el registro, tiene la identidad verificada y puede comprar.'),
    pedido('Login incorrecto (401)', 'POST', url('baseUsuarios', ['login']), {
      cabeceras: [JSON_CT],
      cuerpo: { email: TITULAR.email, password: 'incorrecta1' },
      tests: problema(401, 'no-autenticado'),
    }),
    pedido('Mi perfil (200)', 'GET', url('baseUsuarios', ['yo']), {
      cabeceras: [bearer('token')],
      tests: [
        estado(200),
        "pm.test('es el perfil del dueño del token, sin la contraseña', () => {",
        '  const u = pm.response.json();',
        "  pm.expect(u).to.include.all.keys('id', 'nombre', 'apellido', 'dni', 'email', 'rol', 'identidadVerificada');",
        `  pm.expect(u.email).to.eql('${TITULAR.email}');`,
        "  pm.expect(u.rol).to.eql('user');",
        '  pm.expect(u.identidadVerificada).to.eql(true);',
        "  pm.expect(u).to.not.have.any.keys('password', 'passwordHash');",
        '});',
      ],
    }),
    pedido('Mi perfil sin token (401)', 'GET', url('baseUsuarios', ['yo']), {
      tests: problema(401, 'no-autenticado'),
    }),
    login('Login staff (200) guarda tokenStaff', STAFF, 'staff', 'tokenStaff',
      'Cuenta de prueba del personal de puerta. El token queda en {{tokenStaff}}: con él se prueba que staff no '
      + 've compras (carpeta 3) y se escanea en la puerta (carpeta 5).'),
    pedido('Token de servicio con secreto incorrecto (401)', 'POST', url('baseUsuarios', ['servicios', 'token']), {
      descripcion: 'El token de rol servicio se pide con el secreto compartido. Con otro secreto responde 401.',
      cabeceras: [JSON_CT],
      cuerpo: { secreto: 'no-es-el-secreto' },
      tests: problema(401, 'no-autenticado'),
    }),
    pedido('JWKS claves públicas (200)', 'GET', url('baseUsuarios', ['jwks']), {
      descripcion: 'La clave pública con la que los demás servicios verifican los tokens.',
      tests: [
        estado(200),
        "pm.test('trae al menos una clave RSA para firmar', () => {",
        '  const claves = pm.response.json().keys;',
        "  pm.expect(claves).to.be.an('array').that.is.not.empty;",
        "  pm.expect(claves[0].kty).to.eql('RSA');",
        "  pm.expect(claves[0].kid).to.be.a('string');",
        '});',
        '',
        '// Último request automático de la carpeta: la corrida sigue en Eventos y saltea "Pasos manuales".',
        ...saltarA(`'${PRIMERO_DE_EVENTOS}'`),
      ],
    }),
    carpeta('Pasos manuales (fuera de la corrida)',
      'No corren solos. Necesitan el código de 6 dígitos, que es aleatorio y solo sale en el log del servidor '
      + '("Código de verificación para <email>: 123456"). Pasos: correr "Registro (201)", buscar el código en el log, '
      + 'cargarlo en la variable codigoVerificacion de la colección y enviar "Verificar email (200)". '
      + '"Reenviar código (204)" exige que hayan pasado 30 segundos desde el registro o el último reenvío.',
      [
        pedido('MANUAL · Reenviar código (204)', 'POST', url('baseUsuarios', ['verificacion', 'reenvio']), {
          descripcion: 'Pide un código nuevo para la cuenta de "Registro (201)". Si pasaron menos de 30 segundos '
            + 'desde el registro responde 409. El código nuevo también sale en el log del servidor.',
          cabeceras: [JSON_CT],
          cuerpo: { email: '{{emailRegistro}}' },
          tests: [estado(204)],
        }),
        pedido('MANUAL · Verificar email (200)', 'POST', url('baseUsuarios', ['verificacion']), {
          descripcion: 'Antes de enviarlo, cargá en la variable codigoVerificacion el código que salió en el log '
            + 'del servidor. Devuelve la sesión de la cuenta recién verificada. Con esto el registro queda completo '
            + 'y la cuenta sale con la identidad verificada: ya puede comprar.',
          cabeceras: [JSON_CT],
          cuerpo: { email: '{{emailRegistro}}', codigo: '{{codigoVerificacion}}' },
          tests: [
            estado(200),
            "pm.test('devuelve la sesión de la cuenta verificada', () => {",
            '  const sesion = pm.response.json();',
            "  pm.expect(sesion.token).to.be.a('string').and.not.empty;",
            "  pm.expect(sesion.usuario.email).to.eql(pm.collectionVariables.get('emailRegistro'));",
            '  pm.expect(sesion.usuario.identidadVerificada).to.eql(true);',
            '});',
          ],
        }),
      ]),
  ]);

// ---------------------------------------------------------------------------------------------
// 2 Eventos

const cupo = (nombre, variableToken, cantidad, opciones) => pedido(nombre, 'POST',
  url('baseEventos', ['{{eventoCreadoId}}', 'sectores', '{{sectorCreadoId}}', 'cupo']), {
    cabeceras: [bearer(variableToken), JSON_CT],
    cuerpo: { cantidad },
    ...opciones,
  });

const sectorConVendidas = (vendidas) => [
  estado(200),
  `pm.test('devuelve el sector con ${vendidas} vendidas', () => {`,
  '  const s = pm.response.json();',
  "  pm.expect(s).to.include.all.keys('id', 'nombre', 'precio', 'cupo', 'vendidas');",
  "  pm.expect(s.id).to.eql(pm.collectionVariables.get('sectorCreadoId'));",
  '  pm.expect(s.precio).to.eql(10000);',
  '  pm.expect(s.cupo).to.eql(100);',
  `  pm.expect(s.vendidas).to.eql(${vendidas});`,
  '});',
];

const eventos = carpeta('2 Eventos (implementado)',
  'Catálogo público, alta de eventos (rol organizer) y cambio de cupo (rol servicio). Corre entera sin '
  + 'intervención: pide sus propios tokens. El cupo se prueba sobre el evento que crea la misma carpeta, así '
  + 'no cambia el de los eventos de la demo.',
  [
    pedido(PRIMERO_DE_EVENTOS, 'GET', url('baseEventos', [], [
      { key: 'categoria', value: 'Música', disabled: true },
      { key: 'q', value: 'kiroshi', disabled: true },
    ]), {
      descripcion: 'El catálogo completo. Los filtros categoria y q están en Params, desactivados.',
      tests: [
        estado(200),
        'const lista = pm.response.json();',
        "pm.test('devuelve eventos con sus sectores', () => {",
        "  pm.expect(lista).to.be.an('array').that.is.not.empty;",
        "  pm.expect(lista[0]).to.include.all.keys('id', 'slug', 'titulo', 'categoria', 'venue', 'fechaISO', 'sectores');",
        "  pm.expect(lista[0].sectores).to.be.an('array').that.is.not.empty;",
        '});',
        "pm.collectionVariables.set('slug', lista[0].slug);",
      ],
    }),
    pedido('Filtrar por categoría (200)', 'GET', url('baseEventos', [], [{ key: 'categoria', value: 'Música' }]), {
      tests: [
        estado(200),
        "pm.test('todos los eventos son de Música', () => {",
        '  const lista = pm.response.json();',
        "  pm.expect(lista).to.be.an('array').that.is.not.empty;",
        "  lista.forEach((e) => pm.expect(e.categoria).to.eql('Música'));",
        '});',
      ],
    }),
    pedido('Detalle por slug (200)', 'GET', url('baseEventos', ['{{slug}}']), {
      tests: [
        estado(200),
        "pm.test('es el evento pedido, con sus sectores', () => {",
        '  const e = pm.response.json();',
        "  pm.expect(e.slug).to.eql(pm.collectionVariables.get('slug'));",
        "  pm.expect(e.sectores[0]).to.include.all.keys('id', 'nombre', 'precio', 'cupo', 'vendidas');",
        '});',
      ],
    }),
    pedido('Detalle inexistente (404)', 'GET', url('baseEventos', ['no-existe-xyz']), {
      tests: problema(404, 'no-encontrado'),
    }),
    pedido('Crear evento sin token (401)', 'POST', url('baseEventos'), {
      cabeceras: [JSON_CT],
      cuerpo: eventoDePrueba('Evento de prueba Postman', [{ nombre: 'General', precio: 10000, cupo: 100 }]),
      tests: problema(401, 'no-autenticado'),
    }),
    login('Login organizador (200) guarda tokenOrganizador', ORGANIZADOR, 'organizer', 'tokenOrganizador',
      'Crear eventos exige rol organizer: se usa la cuenta de prueba de organizador, con su propio token.'),
    pedido('Crear evento, rol organizer (201) guarda eventoCreadoId', 'POST', url('baseEventos'), {
      descripcion: 'Crea un evento de prueba con un sector de 100 lugares. Cada corrida agrega uno al catálogo '
        + '("Evento de prueba Postman", con fecha 31/12/2027 para que quede último): no hay operación para borrarlo; '
        + 'se van al reiniciar el servidor, que vacía la base.',
      cabeceras: [bearer('tokenOrganizador'), JSON_CT],
      cuerpo: eventoDePrueba('Evento de prueba Postman', [{ nombre: 'General', precio: 10000, cupo: 100 }]),
      tests: [
        estado(201),
        'const creado = pm.response.json();',
        "pm.test('Location apunta al evento creado', () => {",
        "  const location = pm.response.headers.get('Location');",
        "  pm.expect(location, 'falta la cabecera Location').to.be.a('string');",
        "  pm.expect(location.endsWith('/api/eventos/' + creado.slug), location).to.be.true;",
        '});',
        "pm.test('devuelve el evento con su sector sin vender', () => {",
        "  pm.expect(creado.id).to.be.a('string').and.not.empty;",
        "  pm.expect(creado.titulo).to.eql('Evento de prueba Postman');",
        '  pm.expect(creado.sectores).to.have.lengthOf(1);',
        '  pm.expect(creado.sectores[0].cupo).to.eql(100);',
        '  pm.expect(creado.sectores[0].vendidas).to.eql(0);',
        '});',
        "pm.collectionVariables.set('eventoCreadoId', creado.id);",
        "pm.collectionVariables.set('sectorCreadoId', creado.sectores[0].id);",
      ],
    }),
    pedido('Crear evento inválido (400)', 'POST', url('baseEventos'), {
      descripcion: 'Sin título y sin sectores. Va con el token de organizador: el rol se controla antes que el '
        + 'cuerpo, y con el token de un comprador respondería 403.',
      cabeceras: [bearer('tokenOrganizador'), JSON_CT],
      cuerpo: eventoDePrueba('', []),
      tests: [
        ...problema(400, 'datos-invalidos'),
        "pm.test('errores nombra el título y los sectores', () => {",
        "  pm.expect(pm.response.json().errores).to.include.all.keys('titulo', 'sectores');",
        '});',
      ],
    }),
    pedido('Token de servicio (200) guarda tokenServicio', 'POST', url('baseUsuarios', ['servicios', 'token']), {
      descripcion: 'Cambiar el cupo es una operación entre servicios (rol servicio). El token se pide con el '
        + 'secreto compartido de desarrollo y dura 5 minutos: por eso se pide justo antes de usarlo.',
      cabeceras: [JSON_CT],
      cuerpo: { secreto: '{{secretoServicios}}' },
      tests: [
        estado(200),
        'const respuesta = pm.response.json();',
        "pm.test('trae el token y cuánto dura', () => {",
        "  pm.expect(respuesta.token).to.be.a('string').and.not.empty;",
        '  pm.expect(respuesta.expiraEnSegundos).to.be.above(0);',
        '});',
        "pm.collectionVariables.set('tokenServicio', respuesta.token);",
      ],
    }),
    cupo('Cambiar cupo con token de organizador (403)', 'tokenOrganizador', 2, {
      descripcion: 'El cupo solo lo cambia el rol servicio.',
      tests: problema(403, 'sin-permiso'),
    }),
    cupo('Reservar cupo, rol servicio (200)', 'tokenServicio', 2, {
      descripcion: 'Cantidad positiva: reserva. Es la llamada que hace Ventas al abrir una compra.',
      tests: sectorConVendidas(2),
    }),
    cupo('Reservar más que el cupo (409)', 'tokenServicio', 99, {
      descripcion: 'El sector tiene 100 lugares y 2 reservados: 99 más no entran.',
      tests: problema(409, 'conflicto'),
    }),
    cupo('Liberar cupo, rol servicio (200)', 'tokenServicio', -2, {
      descripcion: 'Cantidad negativa: libera. Deja el sector como estaba antes de la reserva.',
      tests: sectorConVendidas(0),
    }),
  ]);

// ---------------------------------------------------------------------------------------------
// 3 Diseño v1: Ventas

const ventaEnCheckout = (cantidad, titularesEsperados) => [
  'const v = pm.response.json();',
  "pm.test('la venta trae los campos del contrato', () => {",
  `  pm.expect(v).to.include.all.keys(${CAMPOS_VENTA});`,
  "  pm.expect(v.id).to.match(/^V-[0-9a-f]{8}$/);",
  "  pm.expect(v.eventoId).to.eql(pm.collectionVariables.get('eventoId'));",
  '});',
  "pm.test('está EN_CHECKOUT, sin factura, sin pago y sin entradas', () => {",
  "  pm.expect(v.estado).to.eql('EN_CHECKOUT');",
  "  pm.expect(v.facturaEstado).to.eql('NO_APLICA');",
  '  pm.expect(v.pagoId).to.be.null;',
  "  pm.expect(v.entradas).to.be.an('array').that.is.empty;",
  '});',
  "pm.test('el renglón trae el sector, la cantidad y el precio que dio Eventos', () => {",
  '  pm.expect(v.items).to.have.lengthOf(1);',
  "  pm.expect(v.items[0].sectorId).to.eql(pm.collectionVariables.get('sectorId'));",
  "  pm.expect(v.items[0].sector).to.be.a('string').and.not.empty;",
  `  pm.expect(v.items[0].cantidad).to.eql(${cantidad});`,
  '  pm.expect(v.items[0].precioUnitario).to.be.above(0);',
  '});',
  "pm.test('subtotal, cargo de servicio del 10 % y total', () => {",
  `  pm.expect(v.subtotal).to.eql(${cantidad} * v.items[0].precioUnitario);`,
  '  pm.expect(v.cargoServicio).to.eql(Math.round(v.subtotal * 0.10));',
  '  pm.expect(v.montoTotal).to.eql(v.subtotal + v.cargoServicio);',
  '});',
  "pm.test('la reserva vence dentro de los próximos 10 minutos, en UTC', () => {",
  `  pm.expect(v.venceReserva).to.match(${INSTANTE_UTC});`,
  '  const faltan = Date.parse(v.venceReserva) - Date.now();',
  '  pm.expect(faltan).to.be.above(0);',
  '  pm.expect(faltan).to.be.at.most(11 * 60 * 1000);',
  '});',
  "pm.test('trae la fecha de creación, en UTC, 10 minutos antes del vencimiento', () => {",
  `  pm.expect(v.creada).to.match(${INSTANTE_UTC});`,
  '  pm.expect(Date.parse(v.venceReserva) - Date.parse(v.creada)).to.eql(10 * 60 * 1000);',
  '});',
  "pm.test('_links: self, pagar y cancelar, con rutas sin host', () => {",
  "  pm.expect(v._links.self).to.eql({ href: '/api/v1/ventas/' + v.id, method: 'GET' });",
  "  pm.expect(v._links.pagar).to.eql({ href: '/api/v1/ventas/' + v.id + '/pagos', method: 'POST' });",
  "  pm.expect(v._links.cancelar).to.eql({ href: '/api/v1/ventas/' + v.id, method: 'DELETE' });",
  '});',
];

const locationDeLaVenta = [
  "pm.test('Location apunta a la venta', () => {",
  "  const location = pm.response.headers.get('Location');",
  "  pm.expect(location, 'falta la cabecera Location').to.be.a('string');",
  "  pm.expect(location.endsWith('/api/v1/ventas/' + v.id), location).to.be.true;",
  '});',
];

const soloSelf = [
  "pm.test('_links: solo self; ya no se puede pagar ni cancelar', () => {",
  "  pm.expect(v._links.self).to.eql({ href: '/api/v1/ventas/' + v.id, method: 'GET' });",
  "  pm.expect(v._links).to.not.have.any.keys('pagar', 'cancelar');",
  '});',
];

const entradasDeLaVenta = (estadoEsperado) => [
  `pm.test('trae las dos entradas, una por titular y en el orden del pedido, ${estadoEsperado}', () => {`,
  '  pm.expect(v.entradas).to.have.lengthOf(2);',
  `  const titulares = ${JSON.stringify([ANA, LUIS])};`,
  '  v.entradas.forEach((e, i) => {',
  "    pm.expect(e.id).to.match(/^T-[0-9a-f]{8}$/);",
  '    pm.expect(e.sector).to.eql(v.items[0].sector);',
  '    pm.expect(e.titular).to.eql(titulares[i]);',
  `    pm.expect(e.estado).to.eql('${estadoEsperado}');`,
  "    pm.expect(e._links.self).to.eql({ href: '/api/v1/entradas/' + e.id, method: 'GET' });",
  '  });',
  '});',
  "pm.test('cada entrada trae su sector, lo que se pagó por ella y su código', () => {",
  '  v.entradas.forEach((e) => {',
  "    pm.expect(e).to.include.all.keys('id', 'sectorId', 'sector', 'titular', 'precioPagado', 'estado', 'codigoQr', '_links');",
  '    pm.expect(e.sectorId).to.eql(v.items[0].sectorId);',
  '    pm.expect(e.precioPagado).to.eql(v.items[0].precioUnitario);',
  estadoEsperado === 'VALIDA'
    ? "    pm.expect(e.codigoQr).to.be.a('string').and.not.empty;"
    : '    pm.expect(e.codigoQr).to.be.null;',
  '  });',
  '});',
];

const ventaPaga = (estadosPosibles) => [
  'const v = pm.response.json();',
  "pm.test('es la misma venta, con los campos del contrato', () => {",
  `  pm.expect(v).to.include.all.keys(${CAMPOS_VENTA});`,
  "  pm.expect(v.id).to.eql(pm.collectionVariables.get('ventaId'));",
  "  pm.expect(v.facturaEstado).to.eql('NO_APLICA');",
  "  pm.expect(v.montoTotal).to.eql(Number(pm.collectionVariables.get('montoTotal')));",
  '});',
  estadosPosibles.length === 1
    ? `pm.test('la venta está ${estadosPosibles[0]}', () => pm.expect(v.estado).to.eql('${estadosPosibles[0]}'));`
    : `pm.test('la venta está ${estadosPosibles.join(' o ')}', () => pm.expect(v.estado).to.be.oneOf(${JSON.stringify(estadosPosibles)}));`,
];

const entradaCompleta = [
  'const e = pm.response.json();',
  "pm.test('la entrada trae los campos del contrato', () => {",
  `  pm.expect(e).to.include.all.keys(${CAMPOS_ENTRADA});`,
  "  pm.expect(e.id).to.eql(pm.collectionVariables.get('entradaId'));",
  "  pm.expect(e.ventaId).to.eql(pm.collectionVariables.get('ventaId'));",
  "  pm.expect(e.eventoId).to.eql(pm.collectionVariables.get('eventoId'));",
  "  pm.expect(e.sectorId).to.eql(pm.collectionVariables.get('sectorId'));",
  "  pm.expect(e.sector).to.be.a('string').and.not.empty;",
  `  pm.expect(e.titular).to.eql(${JSON.stringify(ANA)});`,
  "  pm.expect(e._links.self).to.eql({ href: '/api/v1/entradas/' + e.id, method: 'GET' });",
  '});',
  "pm.test('precioPagado es el precio del sector, sin el cargo de servicio', () => {",
  "  pm.expect(e.precioPagado).to.eql(Number(pm.collectionVariables.get('precioUnitario')));",
  '});',
];

const entradaEmitida = [
  "pm.test('está VALIDA, con su código QR y la fecha de emisión en UTC', () => {",
  "  pm.expect(e.estado).to.eql('VALIDA');",
  "  pm.expect(e.codigoQr).to.be.a('string').and.not.empty;",
  '  pm.expect(e.codigoQr.length).to.be.at.most(200);',
  `  pm.expect(e.emitida).to.match(${INSTANTE_UTC});`,
  '});',
];

const PRIMERO_DE_SEGURIDAD = 'SEC Login cuenta A (200) guarda tokenA';

const ventas = carpeta('3 Diseño v1 (Ventas implementado)',
  'La compra completa contra /api/v1: abrir la compra con reserva de cupo, pagarla, ver la venta y la entrada '
  + '(incluida la emisión asincrónica) y cancelar otra compra. También muestra el aislamiento entre usuarios '
  + '(una segunda cuenta, otro.titular@palco.test, no ve las compras del titular) y el control de rol (organizer '
  + 'y staff reciben 403). Corre entera sin intervención: empieza con su '
  + 'propio login. Los ids salen de las respuestas y las claves de idempotencia se generan en cada corrida. '
  + 'Cada corrida deja vendidas 2 entradas de Platea B (sec-1-4). La primera entrada y su código quedan en entradaId '
  + 'y codigoQr para la validación en puerta (carpeta 5), y la venta en ventaId para la factura (carpeta 7).',
  [
    login('Login comprador (200) guarda token', TITULAR, 'user', 'token',
      'Comprar exige rol user y la identidad verificada: se usa la cuenta de prueba titular@palco.test.'),
    pedido('POST /v1/ventas sin token (401)', 'POST', url('baseApi', ['ventas']), {
      cabeceras: [clave('{{$guid}}'), JSON_CT],
      cuerpo: compra(2, [ANA, LUIS]),
      tests: problema(401, 'no-autenticado'),
    }),
    pedido('POST /v1/ventas sin Idempotency-Key (400)', 'POST', url('baseApi', ['ventas']), {
      descripcion: 'La cabecera Idempotency-Key es obligatoria y tiene que ser un UUID. Sin ella no se reserva nada.',
      cabeceras: [bearer('token'), JSON_CT],
      cuerpo: compra(2, [ANA, LUIS]),
      tests: [
        ...problema(400, 'datos-invalidos'),
        "pm.test('errores nombra la cabecera Idempotency-Key', () => {",
        "  pm.expect(pm.response.json().errores).to.have.property('Idempotency-Key');",
        '});',
      ],
    }),
    pedido('POST /v1/ventas con el DNI repetido (422)', 'POST', url('baseApi', ['ventas']), {
      descripcion: 'Cada entrada va a nombre de una persona distinta. Es una regla de negocio: responde 422 y '
        + 'no reserva cupo.',
      cabeceras: [bearer('token'), clave('{{$guid}}'), JSON_CT],
      cuerpo: compra(2, [ANA, { nombre: 'Otra Persona', dni: ANA.dni }]),
      tests: problema(422, 'dni-repetido'),
    }),
    pedido('POST /v1/ventas (201) guarda ventaId', 'POST', url('baseApi', ['ventas']), {
      descripcion: 'Abre la compra: 2 entradas de Platea B (sec-1-4) del evento ev-1, y reserva el cupo por 10 '
        + 'minutos. Con el precio de prueba, 2 × 27.500 más el 10 % de cargo da 60.500.',
      cabeceras: [bearer('token'), clave('{{claveVenta}}'), JSON_CT],
      cuerpo: compra(2, [ANA, LUIS]),
      antes: nuevaClave('claveVenta'),
      tests: [
        estado(201),
        esJson,
        ...ventaEnCheckout(2),
        ...locationDeLaVenta,
        "pm.test('con el precio de prueba de Platea B el total es 60.500', () => {",
        '  pm.expect(v.items[0].precioUnitario).to.eql(27500);',
        '  pm.expect(v.subtotal).to.eql(55000);',
        '  pm.expect(v.cargoServicio).to.eql(5500);',
        '  pm.expect(v.montoTotal).to.eql(60500);',
        '});',
        "pm.collectionVariables.set('ventaId', v.id);",
        "pm.collectionVariables.set('montoTotal', v.montoTotal);",
        "pm.collectionVariables.set('precioUnitario', v.items[0].precioUnitario);",
      ],
    }),
    pedido('POST /v1/ventas repetido con la misma clave (201, la misma venta)', 'POST', url('baseApi', ['ventas']), {
      descripcion: 'Idempotencia: repetir el pedido con la misma Idempotency-Key devuelve la venta que ya existe, '
        + 'sin reservar otra vez.',
      cabeceras: [bearer('token'), clave('{{claveVenta}}'), JSON_CT],
      cuerpo: compra(2, [ANA, LUIS]),
      tests: [
        estado(201),
        ...ventaEnCheckout(2),
        ...locationDeLaVenta,
        "pm.test('es la misma venta, no una nueva', () => {",
        "  pm.expect(v.id).to.eql(pm.collectionVariables.get('ventaId'));",
        '});',
      ],
    }),
    pedido('POST /v1/ventas misma clave con otro pedido (409)', 'POST', url('baseApi', ['ventas']), {
      descripcion: 'La misma Idempotency-Key con otro pedido (1 entrada en vez de 2) no devuelve la venta vieja: '
        + 'responde 409 clave-reutilizada y no reserva nada.',
      cabeceras: [bearer('token'), clave('{{claveVenta}}'), JSON_CT],
      cuerpo: compra(1, [ANA]),
      tests: problema(409, 'clave-reutilizada'),
    }),
    pedido('GET /v1/ventas/{id} (200, EN_CHECKOUT)', 'GET', url('baseApi', ['ventas', '{{ventaId}}']), {
      cabeceras: [bearer('token')],
      tests: [
        estado(200),
        esJson,
        ...ventaEnCheckout(2),
        "pm.test('es la venta pedida', () => pm.expect(v.id).to.eql(pm.collectionVariables.get('ventaId')));",
      ],
    }),
    pedido('GET /v1/ventas/{id} inexistente (404)', 'GET', url('baseApi', ['ventas', 'V-00000000']), {
      descripcion: 'Una venta que no existe y una venta de otro usuario responden lo mismo: 404.',
      cabeceras: [bearer('token')],
      tests: problema(404, 'no-encontrado'),
    }),
    pedido('POST /v1/ventas/{id}/pagos con el cobro rechazado (402)', 'POST',
      url('baseApi', ['ventas', '{{ventaId}}', 'pagos']), {
        descripcion: 'El token de prueba tok_rechazado hace que el simulador de cobro rechace el pago. La compra '
          + 'sigue abierta y se puede pagar con otro medio.',
        cabeceras: [bearer('token'), clave('{{$guid}}'), JSON_CT],
        cuerpo: { medioDePago: 'tarjeta', token: 'tok_rechazado' },
        tests: problema(402, 'pago-rechazado'),
      }),
    pedido('POST /v1/ventas/{id}/pagos (201) guarda entradaId', 'POST',
      url('baseApi', ['ventas', '{{ventaId}}', 'pagos']), {
        descripcion: 'Paga la compra con el token de prueba tok_test (aprobado). Devuelve la venta CONFIRMADA con '
          + 'sus entradas, todavía sin código: la emisión es asincrónica.',
        cabeceras: [bearer('token'), clave('{{clavePago}}'), JSON_CT],
        cuerpo: { medioDePago: 'tarjeta', token: 'tok_test' },
        antes: nuevaClave('clavePago'),
        tests: [
          estado(201),
          esJson,
          ...ventaPaga(['CONFIRMADA']),
          "pm.test('trae el id del pago', () => pm.expect(v.pagoId).to.match(/^P-[0-9a-f]{8}$/));",
          "pm.test('Location apunta al pago de la venta', () => {",
          "  const location = pm.response.headers.get('Location');",
          "  pm.expect(location, 'falta la cabecera Location').to.be.a('string');",
          "  pm.expect(location.endsWith('/api/v1/ventas/' + v.id + '/pagos/' + v.pagoId), location).to.be.true;",
          '});',
          ...entradasDeLaVenta('PENDIENTE_EMISION'),
          ...soloSelf,
          "pm.collectionVariables.set('pagoId', v.pagoId);",
          "pm.collectionVariables.set('entradaId', v.entradas[0].id);",
          "pm.collectionVariables.set('intentosEmision', 0);",
        ],
      }),
    pedido('POST /v1/ventas/{id}/pagos repetido con la misma clave (201, el mismo pago)', 'POST',
      url('baseApi', ['ventas', '{{ventaId}}', 'pagos']), {
        descripcion: 'Idempotencia: repetir el pago con la misma Idempotency-Key no cobra de nuevo. Devuelve la '
          + 'venta como está ahora, que ya puede haber pasado a EMITIDA.',
        cabeceras: [bearer('token'), clave('{{clavePago}}'), JSON_CT],
        cuerpo: { medioDePago: 'tarjeta', token: 'tok_test' },
        tests: [
          estado(201),
          ...ventaPaga(['CONFIRMADA', 'EMITIDA']),
          "pm.test('es el mismo pago: no se cobró dos veces', () => {",
          "  pm.expect(v.pagoId).to.eql(pm.collectionVariables.get('pagoId'));",
          '});',
          "pm.test('son las mismas dos entradas', () => {",
          '  pm.expect(v.entradas).to.have.lengthOf(2);',
          "  pm.expect(v.entradas[0].id).to.eql(pm.collectionVariables.get('entradaId'));",
          '});',
        ],
      }),
    pedido('GET /v1/entradas/{id} (200)', 'GET', url('baseApi', ['entradas', '{{entradaId}}']), {
      descripcion: 'La entrada apenas pagada. Puede estar PENDIENTE_EMISION (sin código) o ya VALIDA, según '
        + 'cuánto tardó el consumidor de la cola.',
      cabeceras: [bearer('token')],
      tests: [
        estado(200),
        esJson,
        ...entradaCompleta,
        "pm.test('está PENDIENTE_EMISION sin código, o VALIDA con código', () => {",
        "  pm.expect(e.estado).to.be.oneOf(['PENDIENTE_EMISION', 'VALIDA']);",
        "  if (e.estado === 'PENDIENTE_EMISION') {",
        '    pm.expect(e.codigoQr).to.be.null;',
        '    pm.expect(e.emitida).to.be.null;',
        '  } else {',
        "    pm.expect(e.codigoQr).to.be.a('string').and.not.empty;",
        `    pm.expect(e.emitida).to.match(${INSTANTE_UTC});`,
        '  }',
        '});',
      ],
    }),
    pedido('GET /v1/entradas/{id} emitida (200, VALIDA con codigoQr)', 'GET',
      url('baseApi', ['entradas', '{{entradaId}}']), {
        descripcion: 'Comprueba el proceso asincrónico: el pago publicó PAGO_CONFIRMADO en la cola y el '
          + 'consumidor emite las entradas. En una corrida espera 1 segundo y, si la entrada todavía no está '
          + 'VALIDA, se repite hasta 10 veces. Enviado a mano no se repite solo: se vuelve a enviar.',
        cabeceras: [bearer('token')],
        antes: [
          '// La emisión es asincrónica: se espera 1 segundo antes de preguntar.',
          'setTimeout(() => {}, 1000);',
        ],
        tests: [
          'const MAXIMO_DE_INTENTOS = 10;',
          "const intento = Number(pm.collectionVariables.get('intentosEmision') || 0) + 1;",
          "const pendiente = pm.response.code === 200 && pm.response.json().estado === 'PENDIENTE_EMISION';",
          '',
          'if (pendiente && intento < MAXIMO_DE_INTENTOS) {',
          '  // Todavía no se emitió: se repite este mismo request, sin evaluar nada.',
          "  pm.collectionVariables.set('intentosEmision', intento);",
          ...saltarA('pm.info.requestName').map((linea) => '  ' + linea),
          '} else {',
          "  pm.collectionVariables.set('intentosEmision', 0);",
          '  ' + estado(200),
          ...entradaCompleta.map((linea) => '  ' + linea),
          ...entradaEmitida.map((linea) => '  ' + linea),
          "  pm.collectionVariables.set('codigoQr', e.codigoQr);",
          '}',
        ],
      }),
    pedido('GET /v1/ventas/{id} (200, EMITIDA)', 'GET', url('baseApi', ['ventas', '{{ventaId}}']), {
      descripcion: 'Después de la emisión la venta queda EMITIDA y sus entradas VALIDA.',
      cabeceras: [bearer('token')],
      tests: [
        estado(200),
        ...ventaPaga(['EMITIDA']),
        "pm.test('conserva el id del pago', () => pm.expect(v.pagoId).to.eql(pm.collectionVariables.get('pagoId')));",
        ...entradasDeLaVenta('VALIDA'),
        ...soloSelf,
      ],
    }),
    pedido('GET /v1/ventas mis compras (200)', 'GET', url('baseApi', ['ventas']), {
      descripcion: 'Las compras del dueño del token, de la más nueva a la más vieja, cada una con sus entradas. '
        + 'De quién son lo dice el token: no se le pasa ningún id de usuario. Es lo que usa "Mis entradas".',
      cabeceras: [bearer('token')],
      tests: [
        estado(200),
        esJson,
        'const ventas = pm.response.json();',
        "pm.test('es una lista de ventas con los campos del contrato', () => {",
        "  pm.expect(ventas).to.be.an('array').that.is.not.empty;",
        `  ventas.forEach((venta) => pm.expect(venta).to.include.all.keys(${CAMPOS_VENTA}));`,
        '});',
        "pm.test('viene de la más nueva a la más vieja', () => {",
        '  const fechas = ventas.map((venta) => venta.creada);',
        '  pm.expect(fechas).to.eql([...fechas].sort().reverse());',
        '});',
        "const v = ventas.find((venta) => venta.id === pm.collectionVariables.get('ventaId'));",
        "pm.test('trae la venta de esta corrida, EMITIDA y con su pago', () => {",
        "  pm.expect(v, 'la venta de la corrida no está en la lista').to.be.an('object');",
        "  pm.expect(v.estado).to.eql('EMITIDA');",
        "  pm.expect(v.pagoId).to.eql(pm.collectionVariables.get('pagoId'));",
        '});',
        ...entradasDeLaVenta('VALIDA'),
        "pm.test('el código de la entrada es el mismo que da GET /entradas/{id}', () => {",
        "  pm.expect(v.entradas[0].codigoQr).to.eql(pm.collectionVariables.get('codigoQr'));",
        '});',
        '// Las ventas del titular quedan anotadas para comprobar que la otra cuenta no ve ninguna.',
        "pm.collectionVariables.set('ventasDelTitular', JSON.stringify(ventas.map((venta) => venta.id)));",
      ],
    }),
    pedido('GET /v1/ventas mis compras sin token (401)', 'GET', url('baseApi', ['ventas']), {
      tests: problema(401, 'no-autenticado'),
    }),
    login('Login segunda cuenta compradora (200) guarda tokenOtroComprador', OTRO_COMPRADOR, 'user',
      'tokenOtroComprador',
      'Otra cuenta de comprador, también verificada. Sirve para mostrar el aislamiento entre usuarios: con su '
      + 'token no se ve ninguna compra de titular@palco.test.'),
    pedido('GET /v1/ventas/{id} de otro comprador (404)', 'GET', url('baseApi', ['ventas', '{{ventaId}}']), {
      descripcion: 'La venta que pagó titular@palco.test, pedida con el token de la segunda cuenta. Responde lo '
        + 'mismo que una venta que no existe (404), así no se puede saber si el id existe.',
      cabeceras: [bearer('tokenOtroComprador')],
      tests: [
        ...problema(404, 'no-encontrado'),
        "pm.test('no muestra nada de la venta ajena: solo los campos del error', () => {",
        "  pm.expect(Object.keys(pm.response.json())).to.have.members(['type', 'title', 'status', 'detail', 'instance']);",
        '});',
      ],
    }),
    pedido('GET /v1/ventas de otro comprador (200, sin ventas del titular)', 'GET', url('baseApi', ['ventas']), {
      descripcion: 'Las compras de la segunda cuenta. De quién son lo dice el token, así que no aparece ninguna de '
        + 'titular@palco.test: ni la de esta corrida ni las de corridas anteriores.',
      cabeceras: [bearer('tokenOtroComprador')],
      tests: [
        estado(200),
        esJson,
        'const ventas = pm.response.json();',
        "const delTitular = JSON.parse(pm.collectionVariables.get('ventasDelTitular') || '[]');",
        "pm.test('es una lista', () => pm.expect(ventas).to.be.an('array'));",
        "pm.test('no trae ninguna venta del titular', () => {",
        "  pm.expect(delTitular, 'falta la lista del titular: correr antes \"GET /v1/ventas mis compras\"').to.not.be.empty;",
        '  const ajenas = ventas.filter((venta) => delTitular.includes(venta.id));',
        '  pm.expect(ajenas.map((venta) => venta.id)).to.be.empty;',
        '});',
      ],
    }),
    login('Login organizador en Ventas (200) guarda tokenOrganizador', ORGANIZADOR, 'organizer', 'tokenOrganizador',
      'Para el control de rol: Ventas atiende solo al rol user.'),
    pedido('GET /v1/ventas con token de organizador (403)', 'GET', url('baseApi', ['ventas']), {
      descripcion: 'Un token válido, pero de rol organizer: las compras son solo del rol user. Responde 403, no 401.',
      cabeceras: [bearer('tokenOrganizador')],
      tests: problema(403, 'sin-permiso'),
    }),
    login('Login staff en Ventas (200) guarda tokenStaff', STAFF, 'staff', 'tokenStaff',
      'Para el control de rol: el personal de puerta no consulta ventas.'),
    pedido('GET /v1/ventas/{id} con token de staff (403)', 'GET', url('baseApi', ['ventas', '{{ventaId}}']), {
      descripcion: 'La venta existe, pero el rol staff no puede consultarla: responde 403 antes de buscarla.',
      cabeceras: [bearer('tokenStaff')],
      tests: [
        ...problema(403, 'sin-permiso'),
        "pm.test('no muestra nada de la venta: solo los campos del error', () => {",
        "  pm.expect(Object.keys(pm.response.json())).to.have.members(['type', 'title', 'status', 'detail', 'instance']);",
        '});',
      ],
    }),
    pedido('GET /v1/entradas/{id} inexistente (404)', 'GET', url('baseApi', ['entradas', 'T-00000000']), {
      cabeceras: [bearer('token')],
      tests: problema(404, 'no-encontrado'),
    }),
    pedido('DELETE /v1/ventas/{id} de una venta paga (409)', 'DELETE', url('baseApi', ['ventas', '{{ventaId}}']), {
      descripcion: 'Una venta paga no se cancela.',
      cabeceras: [bearer('token')],
      tests: problema(409, 'venta-ya-paga'),
    }),
    pedido('POST /v1/ventas para cancelar (201) guarda ventaCanceladaId', 'POST', url('baseApi', ['ventas']), {
      descripcion: 'Abre otra compra, de 1 entrada, para probar la cancelación.',
      cabeceras: [bearer('token'), clave('{{claveVentaCancelada}}'), JSON_CT],
      cuerpo: compra(1, [ANA]),
      antes: nuevaClave('claveVentaCancelada'),
      tests: [
        estado(201),
        ...ventaEnCheckout(1),
        ...locationDeLaVenta,
        "pm.test('es otra venta, no la que ya se pagó', () => {",
        "  pm.expect(v.id).to.not.eql(pm.collectionVariables.get('ventaId'));",
        '});',
        "pm.collectionVariables.set('ventaCanceladaId', v.id);",
      ],
    }),
    pedido('DELETE /v1/ventas/{id} (204)', 'DELETE', url('baseApi', ['ventas', '{{ventaCanceladaId}}']), {
      descripcion: 'Cancela la compra sin pagar y devuelve su cupo a Eventos.',
      cabeceras: [bearer('token')],
      tests: [
        estado(204),
        "pm.test('no trae cuerpo', () => pm.expect(pm.response.text()).to.eql(''));",
      ],
    }),
    pedido('DELETE /v1/ventas/{id} repetido (204)', 'DELETE', url('baseApi', ['ventas', '{{ventaCanceladaId}}']), {
      descripcion: 'Cancelar es idempotente: repetirlo también responde 204.',
      cabeceras: [bearer('token')],
      tests: [estado(204)],
    }),
    pedido('GET /v1/ventas/{id} cancelada (200, CANCELADA)', 'GET', url('baseApi', ['ventas', '{{ventaCanceladaId}}']), {
      cabeceras: [bearer('token')],
      tests: [
        estado(200),
        'const v = pm.response.json();',
        "pm.test('la venta está CANCELADA, sin pago y sin entradas', () => {",
        "  pm.expect(v.id).to.eql(pm.collectionVariables.get('ventaCanceladaId'));",
        "  pm.expect(v.estado).to.eql('CANCELADA');",
        '  pm.expect(v.pagoId).to.be.null;',
        "  pm.expect(v.entradas).to.be.an('array').that.is.empty;",
        '});',
        ...soloSelf,
      ],
    }),
    pedido('POST /v1/ventas/{id}/pagos de una venta cancelada (409)', 'POST',
      url('baseApi', ['ventas', '{{ventaCanceladaId}}', 'pagos']), {
        descripcion: 'Una compra cancelada ya no se puede pagar: hay que empezar otra.',
        cabeceras: [bearer('token'), clave('{{$guid}}'), JSON_CT],
        cuerpo: { medioDePago: 'tarjeta', token: 'tok_test' },
        tests: problema(409, 'reserva-vencida'),
      }),
  ]);

// ---------------------------------------------------------------------------------------------
// 4 Seguridad: los criterios de la matriz de la clase 11 que se prueban por HTTP

/** Email nuevo en cada corrida: así los intentos fallidos de una corrida no se suman a los de la siguiente. */
const emailNuevo = (variable, prefijo) => [
  "const sufijo = Date.now().toString(36) + Math.floor(Math.random() * 1000);",
  `pm.collectionVariables.set('${variable}', '${prefijo}-' + sufijo + '@palco.test');`,
];

const sinCamposDeLaEntrada = "pm.test('no filtra la entrada', () => pm.expect(pm.response.text()).to.not.match(/codigoQr|titular|\"estado\"/));";

const sinInformacionInterna = "pm.test('sin información interna', () => pm.expect(pm.response.text()).to.not.match(/Exception|\\bat com\\.|org\\.jboss|SELECT |stack/i));";

const seguridad = carpeta('4 Seguridad (clase 11)',
  'Los criterios de la matriz de seguridad de la clase 11 que se comprueban desde afuera, con el id de cada uno '
  + '(SEC-...). Usa dos cuentas compradoras verificadas: A es titular@palco.test y B, otro.titular@palco.test. '
  + 'La compra de A no se crea acá: se toma una compra emitida de A, como la que deja la carpeta 3. Corre entera '
  + 'sin intervención. SEC-W04 usa un email propio, nuevo en cada corrida, para no bloquear las cuentas de prueba.',
  [
    login(PRIMERO_DE_SEGURIDAD, TITULAR, 'user', 'tokenA', 'Cuenta A de la matriz.'),
    login('SEC Login cuenta B (200) guarda tokenB', OTRO_COMPRADOR, 'user', 'tokenB', 'Cuenta B de la matriz.'),
    pedido('SEC GET /v1/ventas de A (200) guarda ventaIdA y entradaIdA', 'GET', url('baseApi', ['ventas']), {
      descripcion: 'Toma la compra más nueva de A que ya tiene entradas. En una corrida completa es la que pagó la '
        + 'carpeta 3; corriendo solo esta carpeta, sirve cualquier compra emitida de A.',
      cabeceras: [bearer('tokenA')],
      tests: [
        estado(200),
        "const v = pm.response.json().find((venta) => venta.entradas.length > 0);",
        "pm.test('A tiene una compra con entradas', () => {",
        "  pm.expect(v, 'A no tiene compras con entradas: correr antes la carpeta 3').to.be.an('object');",
        '});',
        "pm.collectionVariables.set('ventaIdA', v.id);",
        "pm.collectionVariables.set('entradaIdA', v.entradas[0].id);",
      ],
    }),
    pedido('SEC-W01 GET /v1/entradas/{id} de A con el token de B (404, sin datos)', 'GET',
      url('baseApi', ['entradas', '{{entradaIdA}}']), {
        descripcion: 'Aislamiento entre usuarios: la entrada ajena responde como una que no existe.',
        cabeceras: [bearer('tokenB')],
        tests: [...problema(404, 'no-encontrado'), sinCamposDeLaEntrada],
      }),
    pedido('SEC-W01b GET /v1/ventas/{id} de A con el token de B (404)', 'GET',
      url('baseApi', ['ventas', '{{ventaIdA}}']), {
        cabeceras: [bearer('tokenB')],
        tests: problema(404, 'no-encontrado'),
      }),
    pedido('SEC-W02 Crear evento con rol user (403)', 'POST', url('baseEventos'), {
      descripcion: 'Un token válido de rol user en una operación de organizer: 403, no 401. No crea el evento.',
      cabeceras: [bearer('tokenB'), JSON_CT],
      cuerpo: eventoDePrueba('SEC-W02 no se crea', [{ nombre: 'General', precio: 10000, cupo: 100 }]),
      tests: problema(403, 'sin-permiso'),
    }),
    pedido('SEC-W02b GET /v1/ventas sin token (401, WWW-Authenticate)', 'GET', url('baseApi', ['ventas']), {
      tests: [
        ...problema(401, 'no-autenticado'),
        "pm.test('WWW-Authenticate: Bearer', () => pm.response.to.have.header('WWW-Authenticate', 'Bearer'));",
      ],
    }),
    pedido('SEC-W03a Login con un email que no existe (401, genérico)', 'POST', url('baseUsuarios', ['login']), {
      descripcion: 'Guarda el detail del error para compararlo con el de la clave incorrecta. El email es nuevo en '
        + 'cada corrida.',
      cabeceras: [JSON_CT],
      cuerpo: { email: '{{emailInexistente}}', password: 'Clave1234' },
      antes: emailNuevo('emailInexistente', 'no-existe'),
      tests: [
        ...problema(401, 'no-autenticado'),
        "pm.collectionVariables.set('detalleInexistente', pm.response.json().detail);",
      ],
    }),
    pedido('SEC-W03b Login con la clave incorrecta (401, el mismo mensaje)', 'POST', url('baseUsuarios', ['login']), {
      descripcion: 'Un email que existe con la clave mal: el mismo error que un email que no existe, así no se '
        + 'puede saber qué cuentas hay. Usa la cuenta B, porque la carpeta 1 ya prueba una clave mal con la A.',
      cabeceras: [JSON_CT],
      cuerpo: { email: OTRO_COMPRADOR.email, password: 'incorrecta1' },
      tests: [
        ...problema(401, 'no-autenticado'),
        "pm.test('el mismo detail que con un email que no existe', () => {",
        "  pm.expect(pm.response.json().detail).to.eql(pm.collectionVariables.get('detalleInexistente'));",
        '});',
      ],
    }),
    pedido('SEC-W05a GET /yo con la firma del token alterada (401)', 'GET', url('baseUsuarios', ['yo']), {
      descripcion: 'El servidor rechaza el token antes de llegar a la aplicación; la página de error de backend/common '
        + '(ErrorDeAutenticacionServlet) lo responde como Problem Details, con WWW-Authenticate y CORS.',
      cabeceras: [{ key: 'Authorization', value: 'Bearer {{tokenA}}x' }],
      tests: [estado(401), esProblema],
    }),
    pedido('SEC-W05b GET /yo con un token alg=none (401)', 'GET', url('baseUsuarios', ['yo']), {
      descripcion: 'Un token sin firma que dice ser de organizer: el servidor lo rechaza con un 401 en Problem Details.',
      cabeceras: [bearer('tokenNone')],
      antes: [
        '// Arma un JWT con alg=none: cabecera y cuerpo en base64url y la firma vacía.',
        "const b = (o) => btoa(JSON.stringify(o)).replace(/=/g, '').replace(/\\+/g, '-').replace(/\\//g, '_');",
        "pm.collectionVariables.set('tokenNone', b({ alg: 'none', typ: 'JWT' }) + '.' + b({ sub: 'x', iss: 'palco', groups: ['organizer'], exp: 9999999999 }) + '.');",
      ],
      tests: [estado(401), esProblema],
    }),
    pedido('SEC-W05c Login: la sesión dura 8 horas', 'POST', url('baseUsuarios', ['login']), {
      descripcion: 'El criterio de la matriz pide una vigencia corta. El grupo decidió el 16/10 mantener la sesión '
        + 'en 8 horas, sin refresh token, y justificarlo: comprueba que el token dura exactamente eso.',
      cabeceras: [JSON_CT],
      cuerpo: TITULAR,
      tests: [
        estado(200),
        "const p = JSON.parse(atob(pm.response.json().token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));",
        "pm.test('exp - iat = 8 h (28.800 s)', () => pm.expect(p.exp - p.iat).to.eql(28800));",
      ],
    }),
    pedido('SEC-W06 POST /v1/ventas sin Idempotency-Key (400, sin información interna)', 'POST',
      url('baseApi', ['ventas']), {
        cabeceras: [bearer('tokenA'), JSON_CT],
        cuerpo: compra(1, [ANA]),
        tests: [...problema(400, 'datos-invalidos'), sinInformacionInterna],
      }),
    pedido('SEC-D02 GET /yo no devuelve la contraseña ni el hash', 'GET', url('baseUsuarios', ['yo']), {
      cabeceras: [bearer('tokenA')],
      tests: [
        estado(200),
        "pm.test('sin password ni hash', () => pm.expect(pm.response.text()).to.not.match(/password|hash|pbkdf2/i));",
      ],
    }),
    pedido('SEC-W04 Sexto login fallido en 15 minutos (429, Retry-After)', 'POST', url('baseUsuarios', ['login']), {
      descripcion: 'Antes de enviarse, manda cinco intentos fallidos seguidos, uno después del otro, con un email '
        + 'nuevo en cada corrida; este es el sexto. Prueba el límite de intentos de login (5 fallidos en 15 minutos). Va '
        + 'último en la colección para que el límite no toque a ningún otro request.',
      cabeceras: [JSON_CT],
      cuerpo: { email: '{{emailIntentos}}', password: 'incorrecta1' },
      antes: [
        ...emailNuevo('emailIntentos', 'intentos'),
        "const email = pm.collectionVariables.get('emailIntentos');",
        'const codigos = [];',
        'const intentar = () => {',
        '  if (codigos.length === 5) {',
        "    pm.collectionVariables.set('codigosPrevios', JSON.stringify(codigos));",
        '    return;',
        '  }',
        '  pm.sendRequest({',
        "    url: pm.collectionVariables.get('baseUsuarios') + '/login',",
        "    method: 'POST',",
        "    header: { 'Content-Type': 'application/json' },",
        "    body: { mode: 'raw', raw: JSON.stringify({ email, password: 'incorrecta1' }) },",
        "  }, (err, res) => { codigos.push(err ? 'error' : res.code); intentar(); });",
        '};',
        'intentar();',
      ],
      tests: [
        "pm.test('los cinco intentos anteriores responden 401', () => {",
        "  pm.expect(JSON.parse(pm.collectionVariables.get('codigosPrevios'))).to.eql([401, 401, 401, 401, 401]);",
        '});',
        ...problema(429, 'demasiados-intentos'),
        "pm.test('trae Retry-After', () => pm.response.to.have.header('Retry-After'));",
      ],
    }),
  ]);

// ---------------------------------------------------------------------------------------------
// Esperas de los procesos asincrónicos

/**
 * Tests de un request que se repite hasta que la condición se cumpla o se acaben los intentos. Antes de
 * cada intento el request espera 1 segundo (ver esperaDeUnSegundo). Enviado a mano no se repite solo.
 */
const repetirHasta = (contador, condicion, maximo, testsFinales) => [
  `const MAXIMO_DE_INTENTOS = ${maximo};`,
  `const intento = Number(pm.collectionVariables.get('${contador}') || 0) + 1;`,
  `const todaviaNo = !(${condicion});`,
  '',
  'if (todaviaNo && intento < MAXIMO_DE_INTENTOS) {',
  '  // Todavía no: se repite este mismo request, sin evaluar nada.',
  `  pm.collectionVariables.set('${contador}', intento);`,
  ...saltarA('pm.info.requestName').map((linea) => '  ' + linea),
  '} else {',
  `  pm.collectionVariables.set('${contador}', 0);`,
  ...testsFinales.map((linea) => '  ' + linea),
  '}',
];

const esperaDeUnSegundo = [
  '// El proceso es asincrónico: se espera 1 segundo antes de preguntar.',
  'setTimeout(() => {}, 1000);',
];

// ---------------------------------------------------------------------------------------------
// 5 Validación en puerta

const CAMPOS_ESCANEO = "'id', 'resultado', 'puedeEntrar', 'mensaje', 'eventoId', 'entrada', 'usoAnterior', 'escaneadoPor', 'escaneado'";

const escaneo = (resultado, puedeEntrar) => [
  'const r = pm.response.json();',
  "pm.test('el escaneo trae los campos del contrato', () => {",
  `  pm.expect(r).to.include.all.keys(${CAMPOS_ESCANEO});`,
  "  pm.expect(r.id).to.be.a('string').and.not.empty;",
  "  pm.expect(r.mensaje).to.be.a('string').and.not.empty;",
  `  pm.expect(r.escaneado).to.match(${INSTANTE_UTC});`,
  '});',
  `pm.test('resultado ${resultado}, puedeEntrar ${puedeEntrar}', () => {`,
  `  pm.expect(r.resultado).to.eql('${resultado}');`,
  `  pm.expect(r.puedeEntrar).to.eql(${puedeEntrar});`,
  '});',
  "pm.test('Location apunta al escaneo', () => {",
  "  const location = pm.response.headers.get('Location');",
  "  pm.expect(location, 'falta la cabecera Location').to.be.a('string');",
  "  pm.expect(location.endsWith('/api/v1/validacion/escaneos/' + r.id), location).to.be.true;",
  '});',
];

const escanear = (nombre, variableToken, cuerpo, opciones) => pedido(nombre, 'POST',
  url('baseValidacion', ['escaneos']), {
    cabeceras: variableToken ? [bearer(variableToken), JSON_CT] : [JSON_CT],
    cuerpo,
    ...opciones,
  });

const validacion = carpeta('5 Validación en puerta (implementado)',
  'El personal de puerta (puerta@palco.test, rol staff) escanea el código QR de la entrada que pagó la carpeta 3 '
  + '({{codigoQr}}): primero en la puerta de otro evento (no entra y la entrada sigue sirviendo), después en la '
  + 'suya (VALIDA, y Ventas la marca USADA) y una vez más (YA_USADA, con el uso anterior). Todo escaneo responde 201: '
  + 'lo que se crea es el renglón del registro; si la persona entra lo dicen resultado y puedeEntrar. Corre entera '
  + 'sin intervención, después de la carpeta 3.',
  [
    login('Login staff (200) guarda tokenStaff', STAFF, 'staff', 'tokenStaff',
      'Escanear exige rol staff: se usa la cuenta de prueba de puerta.'),
    escanear('POST /v1/validacion/escaneos sin token (401)', null,
      { codigoQr: '{{codigoQr}}', eventoId: '{{eventoId}}' }, {
        tests: problema(401, 'no-autenticado'),
      }),
    escanear('POST /v1/validacion/escaneos con rol user (403)', 'token',
      { codigoQr: '{{codigoQr}}', eventoId: '{{eventoId}}' }, {
        descripcion: 'Un comprador no puede validar entradas, ni la suya.',
        tests: problema(403, 'sin-permiso'),
      }),
    escanear('POST /v1/validacion/escaneos sin evento (400)', 'tokenStaff', { codigoQr: '{{codigoQr}}' }, {
      tests: [
        ...problema(400, 'datos-invalidos'),
        "pm.test('errores nombra el evento', () => pm.expect(JSON.stringify(pm.response.json().errores)).to.include('eventoId'));",
      ],
    }),
    escanear('POST /v1/validacion/escaneos código inexistente (201, INEXISTENTE)', 'tokenStaff',
      { codigoQr: 'no-es-un-codigo-de-palco', eventoId: '{{eventoId}}' }, {
        tests: [
          estado(201),
          esJson,
          ...escaneo('INEXISTENTE', false),
          "pm.test('sin entrada', () => pm.expect(r.entrada).to.be.null);",
        ],
      }),
    escanear('POST /v1/validacion/escaneos en otro evento (201, OTRO_EVENTO)', 'tokenStaff',
      { codigoQr: '{{codigoQr}}', eventoId: 'ev-2' }, {
        descripcion: 'La entrada es de ev-1 y la puerta controla ev-2: no entra, y la entrada no se marca usada.',
        tests: [estado(201), ...escaneo('OTRO_EVENTO', false)],
      }),
    escanear('POST /v1/validacion/escaneos (201, VALIDA) guarda escaneoId', 'tokenStaff',
      { codigoQr: '{{codigoQr}}', eventoId: '{{eventoId}}' }, {
        descripcion: 'El primer escaneo en la puerta de su evento: la persona entra y Ventas marca la entrada USADA.',
        tests: [
          estado(201),
          esJson,
          ...escaneo('VALIDA', true),
          "pm.test('trae la entrada y su titular, para contrastarlo con el documento', () => {",
          "  pm.expect(r.entrada.id).to.eql(pm.collectionVariables.get('entradaId'));",
          "  pm.expect(r.entrada.titular).to.include.all.keys('nombre', 'dni');",
          '  pm.expect(r.usoAnterior).to.be.null;',
          '});',
          "pm.collectionVariables.set('escaneoId', r.id);",
        ],
      }),
    escanear('POST /v1/validacion/escaneos repetido (201, YA_USADA)', 'tokenStaff',
      { codigoQr: '{{codigoQr}}', eventoId: '{{eventoId}}' }, {
        descripcion: 'El mismo código otra vez: no entra, y la respuesta dice cuándo y quién lo usó.',
        tests: [
          estado(201),
          ...escaneo('YA_USADA', false),
          "pm.test('usoAnterior es el escaneo VALIDA de recién', () => {",
          "  pm.expect(r.usoAnterior.escaneoId).to.eql(pm.collectionVariables.get('escaneoId'));",
          "  pm.expect(r.usoAnterior.resultado).to.eql('VALIDA');",
          '});',
        ],
      }),
    pedido('GET /v1/entradas/{id} usada (200, USADA)', 'GET', url('baseApi', ['entradas', '{{entradaId}}']), {
      descripcion: 'Del lado de Ventas la entrada quedó USADA.',
      cabeceras: [bearer('token')],
      tests: [
        estado(200),
        "pm.test('la entrada está USADA', () => pm.expect(pm.response.json().estado).to.eql('USADA'));",
      ],
    }),
    pedido('GET /v1/validacion/escaneos?eventoId (200)', 'GET',
      url('baseValidacion', ['escaneos'], [{ key: 'eventoId', value: '{{eventoId}}' }]), {
        descripcion: 'El registro de la puerta, del más nuevo al más viejo. El código no viaja completo.',
        cabeceras: [bearer('tokenStaff')],
        tests: [
          estado(200),
          'const lista = pm.response.json();',
          "pm.test('incluye el escaneo VALIDA, sin el código completo', () => {",
          "  const fila = lista.find((e) => e.id === pm.collectionVariables.get('escaneoId'));",
          "  pm.expect(fila, 'no está el escaneo').to.be.an('object');",
          "  pm.expect(fila).to.include.all.keys('id', 'eventoId', 'codigoParcial', 'resultado', 'entradaId', 'escaneadoPor', 'escaneado');",
          "  pm.expect(fila.resultado).to.eql('VALIDA');",
          "  pm.expect(pm.response.text()).to.not.include(pm.collectionVariables.get('codigoQr'));",
          '});',
        ],
      }),
    pedido('GET /v1/validacion/escaneos sin eventoId (400)', 'GET', url('baseValidacion', ['escaneos']), {
      cabeceras: [bearer('tokenStaff')],
      tests: problema(400, 'datos-invalidos'),
    }),
    pedido('GET /v1/validacion/escaneos/{id} (200)', 'GET', url('baseValidacion', ['escaneos', '{{escaneoId}}']), {
      cabeceras: [bearer('tokenStaff')],
      tests: [
        estado(200),
        "pm.test('es el escaneo VALIDA', () => {",
        '  const e = pm.response.json();',
        "  pm.expect(e.id).to.eql(pm.collectionVariables.get('escaneoId'));",
        "  pm.expect(e.resultado).to.eql('VALIDA');",
        "  pm.expect(e.entradaId).to.eql(pm.collectionVariables.get('entradaId'));",
        '});',
      ],
    }),
  ]);

// ---------------------------------------------------------------------------------------------
// 6 Reventa

const CAMPOS_PUBLICACION = "'id', 'entradaId', 'eventoId', 'sectorId', 'sector', 'precio', 'precioMaximo', 'estado', 'publicada'";

const publicar = (nombre, entrada, precio, opciones) => pedido(nombre, 'POST', url('baseReventas'), {
  cabeceras: [bearer('token'), JSON_CT],
  cuerpo: `{\n  "entradaId": "${entrada}",\n  "precio": ${precio}\n}`,
  ...opciones,
});

const publicacionActiva = (variableEntrada) => [
  'const p = pm.response.json();',
  "pm.test('la publicación trae los campos del contrato', () => {",
  `  pm.expect(p).to.include.all.keys(${CAMPOS_PUBLICACION});`,
  "  pm.expect(p.id).to.match(/^R-[0-9a-f]{8}$/);",
  `  pm.expect(p.entradaId).to.eql(pm.collectionVariables.get('${variableEntrada}'));`,
  `  pm.expect(p.publicada).to.match(${INSTANTE_UTC});`,
  '});',
  "pm.test('ACTIVA, a 29.000 y con el tope de esa entrada (27.500 + 10 %)', () => {",
  "  pm.expect(p.estado).to.eql('ACTIVA');",
  '  pm.expect(p.precio).to.eql(29000);',
  '  pm.expect(p.precioMaximo).to.eql(30250);',
  '});',
];

const comprarReventa = (nombre, variableToken, claveCompra, tokenDePago, opciones) => pedido(nombre, 'POST',
  url('baseReventas', ['{{publicacionId}}', 'compra']), {
    cabeceras: [bearer(variableToken), ...(claveCompra ? [clave(claveCompra)] : []), JSON_CT],
    cuerpo: { titular: LUIS, medioDePago: 'tarjeta', token: tokenDePago },
    ...opciones,
  });

const entradaEnEstado = (nombre, variableToken, variableEntrada, estadoEsperado, descripcion) => pedido(nombre, 'GET',
  url('baseApi', ['entradas', `{{${variableEntrada}}}`]), {
    descripcion,
    cabeceras: [bearer(variableToken)],
    tests: [
      estado(200),
      `pm.test('la entrada está ${estadoEsperado}', () => pm.expect(pm.response.json().estado).to.eql('${estadoEsperado}'));`,
    ],
  });

const reventa = carpeta('6 Reventa (implementado)',
  'La reventa oficial de punta a punta. titular@palco.test compra 2 entradas de Platea B (27.500 cada una), '
  + 'publica la primera dentro del rango (del 50 % al 110 % de lo pagado: de 13.750 a 30.250) y '
  + 'otro.titular@palco.test la compra: la original queda VENDIDA y el comprador recibe una entrada nueva a su '
  + 'nombre. También prueba los errores del contrato (precio fuera de rango, entrada ya publicada, compra propia, '
  + 'publicación ya vendida) y retira la segunda entrada. Corre entera sin intervención. Cada corrida deja '
  + 'vendidas 2 entradas más de Platea B (sec-1-4).',
  [
    login('Login comprador (200) guarda token', TITULAR, 'user', 'token', 'Quien publica: titular@palco.test.'),
    login('Login segunda cuenta compradora (200) guarda tokenOtroComprador', OTRO_COMPRADOR, 'user',
      'tokenOtroComprador', 'Quien compra en la reventa: otro.titular@palco.test.'),
    pedido('POST /v1/ventas para revender (201) guarda ventaReventaId', 'POST', url('baseApi', ['ventas']), {
      descripcion: 'La compra de las 2 entradas que se van a publicar.',
      cabeceras: [bearer('token'), clave('{{claveVentaReventa}}'), JSON_CT],
      cuerpo: compra(2, [ANA, LUIS]),
      antes: nuevaClave('claveVentaReventa'),
      tests: [
        estado(201),
        "pm.collectionVariables.set('ventaReventaId', pm.response.json().id);",
      ],
    }),
    pedido('POST /v1/ventas/{id}/pagos para revender (201) guarda las entradas', 'POST',
      url('baseApi', ['ventas', '{{ventaReventaId}}', 'pagos']), {
        cabeceras: [bearer('token'), clave('{{$guid}}'), JSON_CT],
        cuerpo: { medioDePago: 'tarjeta', token: 'tok_test' },
        tests: [
          estado(201),
          'const v = pm.response.json();',
          "pm.test('dos entradas', () => pm.expect(v.entradas).to.have.lengthOf(2));",
          "pm.collectionVariables.set('entradaReventaId', v.entradas[0].id);",
          "pm.collectionVariables.set('entradaRetiroId', v.entradas[1].id);",
          "pm.collectionVariables.set('intentosEmision', 0);",
        ],
      }),
    pedido('GET /v1/ventas/{id} emitida (200, EMITIDA)', 'GET', url('baseApi', ['ventas', '{{ventaReventaId}}']), {
      descripcion: 'Solo se publica una entrada VALIDA: se espera la emisión asincrónica. En una corrida se repite '
        + 'hasta 10 veces, con 1 segundo entre una y otra.',
      cabeceras: [bearer('token')],
      antes: esperaDeUnSegundo,
      tests: repetirHasta('intentosEmision', "pm.response.code === 200 && pm.response.json().estado === 'EMITIDA'", 10, [
        estado(200),
        "pm.test('la venta está EMITIDA', () => pm.expect(pm.response.json().estado).to.eql('EMITIDA'));",
      ]),
    }),
    pedido('POST /v1/reventas sin token (401)', 'POST', url('baseReventas'), {
      cabeceras: [JSON_CT],
      cuerpo: '{\n  "entradaId": "{{entradaReventaId}}",\n  "precio": 29000\n}',
      tests: problema(401, 'no-autenticado'),
    }),
    publicar('POST /v1/reventas de una entrada que no existe (404)', 'T-00000000', 29000, {
      tests: problema(404, 'no-encontrado'),
    }),
    publicar('POST /v1/reventas sobre el tope (422)', '{{entradaReventaId}}', 30251, {
      descripcion: 'El tope es lo pagado más el 10 %: 30.250 para una entrada de 27.500. Un peso más ya no entra.',
      tests: [
        ...problema(422, 'precio-fuera-de-rango'),
        "pm.test('el detalle dice el rango', () => pm.expect(pm.response.json().detail).to.include('30250'));",
      ],
    }),
    publicar('POST /v1/reventas bajo el mínimo (422)', '{{entradaReventaId}}', 13749, {
      descripcion: 'El mínimo es el 50 % de lo pagado: 13.750.',
      tests: problema(422, 'precio-fuera-de-rango'),
    }),
    publicar('POST /v1/reventas (201) guarda publicacionId', '{{entradaReventaId}}', 29000, {
      descripcion: 'Publica la entrada a 29.000, dentro del rango. Ventas la deja PUBLICADA.',
      tests: [
        estado(201),
        esJson,
        ...publicacionActiva('entradaReventaId'),
        "pm.test('Location apunta a la publicación', () => {",
        "  const location = pm.response.headers.get('Location');",
        "  pm.expect(location, 'falta la cabecera Location').to.be.a('string');",
        "  pm.expect(location.endsWith('/api/v1/reventas/' + p.id), location).to.be.true;",
        '});',
        "pm.collectionVariables.set('publicacionId', p.id);",
      ],
    }),
    publicar('POST /v1/reventas de la misma entrada (409)', '{{entradaReventaId}}', 29000, {
      descripcion: 'La entrada ya está PUBLICADA: no se publica dos veces.',
      tests: problema(409, 'entrada-no-disponible'),
    }),
    entradaEnEstado('GET /v1/entradas/{id} publicada (200, PUBLICADA)', 'token', 'entradaReventaId', 'PUBLICADA',
      'Mientras está publicada, la entrada no sirve para entrar.'),
    pedido('GET /v1/reventas?eventoId público (200)', 'GET',
      url('baseReventas', [], [{ key: 'eventoId', value: '{{eventoId}}' }]), {
        descripcion: 'Las publicaciones activas del evento. Público, como el catálogo: sin token.',
        tests: [
          estado(200),
          "pm.test('incluye la publicación, ACTIVA', () => {",
          "  const p = pm.response.json().find((x) => x.id === pm.collectionVariables.get('publicacionId'));",
          "  pm.expect(p, 'no está la publicación').to.be.an('object');",
          "  pm.expect(p.estado).to.eql('ACTIVA');",
          '});',
          "pm.test('todas están ACTIVA', () => pm.expect(pm.response.json().every((x) => x.estado === 'ACTIVA')).to.be.true);",
        ],
      }),
    pedido('GET /v1/reventas/mias (200)', 'GET', url('baseReventas', ['mias']), {
      cabeceras: [bearer('token')],
      tests: [
        estado(200),
        "pm.test('incluye la publicación', () => {",
        "  const ids = pm.response.json().map((x) => x.id);",
        "  pm.expect(ids).to.include(pm.collectionVariables.get('publicacionId'));",
        '});',
      ],
    }),
    pedido('GET /v1/reventas/mias de otra cuenta (200, sin esa publicación)', 'GET', url('baseReventas', ['mias']), {
      cabeceras: [bearer('tokenOtroComprador')],
      tests: [
        estado(200),
        "pm.test('no incluye la publicación del titular', () => {",
        "  const ids = pm.response.json().map((x) => x.id);",
        "  pm.expect(ids).to.not.include(pm.collectionVariables.get('publicacionId'));",
        '});',
      ],
    }),
    comprarReventa('POST /v1/reventas/{id}/compra propia (409)', 'token', '{{$guid}}', 'tok_test', {
      descripcion: 'Nadie compra su propia publicación.',
      tests: problema(409, 'compra-propia'),
    }),
    comprarReventa('POST /v1/reventas/{id}/compra sin Idempotency-Key (400)', 'tokenOtroComprador', null, 'tok_test', {
      tests: problema(400, 'datos-invalidos'),
    }),
    comprarReventa('POST /v1/reventas/{id}/compra con el cobro rechazado (402)', 'tokenOtroComprador', '{{$guid}}',
      'tok_rechazado', {
        descripcion: 'El cobro se rechaza: la publicación sigue ACTIVA y se puede comprar con otro medio.',
        tests: problema(402, 'pago-rechazado'),
      }),
    comprarReventa('POST /v1/reventas/{id}/compra (201) guarda entradaCompradaId', 'tokenOtroComprador',
      '{{claveCompraReventa}}', 'tok_test', {
        descripcion: 'otro.titular@palco.test compra la entrada a nombre de Luis Pérez. Se le cobran 29.000 más el '
          + '10 % de cargo. La publicación queda VENDIDA y el comprador recibe una entrada nueva, VALIDA.',
        antes: nuevaClave('claveCompraReventa'),
        tests: [
          estado(201),
          esJson,
          'const c = pm.response.json();',
          "pm.test('trae la publicación, la entrada nueva y la venta', () => pm.expect(c).to.include.all.keys('publicacion', 'entrada', 'ventaId'));",
          "pm.test('la publicación quedó VENDIDA', () => {",
          "  pm.expect(c.publicacion.id).to.eql(pm.collectionVariables.get('publicacionId'));",
          "  pm.expect(c.publicacion.estado).to.eql('VENDIDA');",
          '});',
          "pm.test('la entrada nueva es otra, a nombre del comprador, con código y al precio de la reventa', () => {",
          "  pm.expect(c.entrada.id).to.not.eql(pm.collectionVariables.get('entradaReventaId'));",
          `  pm.expect(c.entrada.titular).to.eql(${JSON.stringify(LUIS)});`,
          "  pm.expect(c.entrada.codigoQr).to.be.a('string').and.not.empty;",
          '  pm.expect(c.entrada.precioPagado).to.eql(29000);',
          '});',
          "pm.test('Location apunta a la entrada nueva', () => {",
          "  const location = pm.response.headers.get('Location');",
          "  pm.expect(location, 'falta la cabecera Location').to.be.a('string');",
          "  pm.expect(location.endsWith('/api/v1/entradas/' + c.entrada.id), location).to.be.true;",
          '});',
          "pm.collectionVariables.set('entradaCompradaId', c.entrada.id);",
          "pm.collectionVariables.set('ventaCompradaId', c.ventaId);",
        ],
      }),
    comprarReventa('POST /v1/reventas/{id}/compra repetida con la misma clave (201, la misma)', 'tokenOtroComprador',
      '{{claveCompraReventa}}', 'tok_test', {
        descripcion: 'Idempotencia: repetir la compra con la misma clave no cobra de nuevo y devuelve lo mismo.',
        tests: [
          estado(201),
          "pm.test('es la misma entrada', () => pm.expect(pm.response.json().entrada.id).to.eql(pm.collectionVariables.get('entradaCompradaId')));",
        ],
      }),
    comprarReventa('POST /v1/reventas/{id}/compra ya vendida (409)', 'tokenOtroComprador', '{{$guid}}', 'tok_test', {
      descripcion: 'Con otra clave es otra compra, y la publicación ya se vendió.',
      tests: problema(409, 'publicacion-no-activa'),
    }),
    pedido('DELETE /v1/reventas/{id} ya vendida (409)', 'DELETE', url('baseReventas', ['{{publicacionId}}']), {
      cabeceras: [bearer('token')],
      tests: problema(409, 'publicacion-no-activa'),
    }),
    entradaEnEstado('GET /v1/entradas/{id} original (200, VENDIDA)', 'token', 'entradaReventaId', 'VENDIDA',
      'La entrada del vendedor quedó VENDIDA: ya no sirve para entrar.'),
    entradaEnEstado('GET /v1/entradas/{id} del comprador (200, VALIDA)', 'tokenOtroComprador', 'entradaCompradaId',
      'VALIDA', 'La entrada nueva es del comprador: la ve con su token.'),
    publicar('POST /v1/reventas de la segunda entrada (201) guarda publicacionRetiroId', '{{entradaRetiroId}}', 29000, {
      tests: [
        estado(201),
        ...publicacionActiva('entradaRetiroId'),
        "pm.collectionVariables.set('publicacionRetiroId', p.id);",
      ],
    }),
    pedido('DELETE /v1/reventas/{id} ajena (404)', 'DELETE', url('baseReventas', ['{{publicacionRetiroId}}']), {
      descripcion: 'Una publicación de otra cuenta responde como una que no existe.',
      cabeceras: [bearer('tokenOtroComprador')],
      tests: problema(404, 'no-encontrado'),
    }),
    pedido('DELETE /v1/reventas/{id} (204)', 'DELETE', url('baseReventas', ['{{publicacionRetiroId}}']), {
      descripcion: 'La retira: queda RETIRADA y la entrada vuelve a VALIDA.',
      cabeceras: [bearer('token')],
      tests: [estado(204)],
    }),
    pedido('DELETE /v1/reventas/{id} repetido (409)', 'DELETE', url('baseReventas', ['{{publicacionRetiroId}}']), {
      cabeceras: [bearer('token')],
      tests: problema(409, 'publicacion-no-activa'),
    }),
    entradaEnEstado('GET /v1/entradas/{id} retirada (200, VALIDA)', 'token', 'entradaRetiroId', 'VALIDA',
      'Al retirar la publicación, la entrada vuelve a servir.'),
  ]);

// ---------------------------------------------------------------------------------------------
// 7 Facturación

const CAMPOS_FACTURA = "'ventaId', 'compradorId', 'eventoId', 'tipoComprobante', 'puntoDeVenta', 'numero', 'comprobante', 'cae', 'vencimientoCae', 'importe', 'fecha', 'emitida'";

const facturaCompleta = [
  'const f = pm.response.json();',
  "pm.test('la factura trae los campos del contrato', () => pm.expect(f).to.include.all.keys(" + CAMPOS_FACTURA + '));',
  "pm.test('es la factura B de la venta, por el total cobrado', () => {",
  "  pm.expect(f.ventaId).to.eql(pm.collectionVariables.get('ventaId'));",
  '  pm.expect(f.tipoComprobante).to.eql(6);',
  "  pm.expect(f.importe).to.eql(Number(pm.collectionVariables.get('montoTotal')));",
  '});',
  "pm.test('con número y CAE de 14 dígitos', () => {",
  '  pm.expect(f.comprobante).to.match(/^\\d{5}-\\d{8}$/);',
  '  pm.expect(f.numero).to.be.above(0);',
  '  pm.expect(f.cae).to.match(/^\\d{14}$/);',
  '  pm.expect(f.vencimientoCae).to.match(/^\\d{4}-\\d{2}-\\d{2}$/);',
  `  pm.expect(f.emitida).to.match(${INSTANTE_UTC});`,
  '});',
];

const facturacion = carpeta('7 Facturación (implementado)',
  'La factura de la compra que pagó la carpeta 3 ({{ventaId}}). No se pide por HTTP: Facturación consume el '
  + 'tópico de compras confirmadas y le pide el CAE a ARCA (simulado) por SOAP. Por eso el primer request '
  + 'se repite hasta 10 veces, con 1 segundo entre una y otra, hasta que la factura aparece. Corre entera sin '
  + 'intervención, después de la carpeta 3.',
  [
    login('Login comprador (200) guarda token', TITULAR, 'user', 'token', 'El dueño de la compra.'),
    pedido('GET /v1/facturas/{ventaId} (200) guarda el comprobante', 'GET', url('baseFacturas', ['{{ventaId}}']), {
      descripcion: 'La factura aparece unos segundos después del pago. Enviado a mano no se repite solo: si '
        + 'responde 404, se vuelve a enviar.',
      cabeceras: [bearer('token')],
      antes: esperaDeUnSegundo,
      tests: repetirHasta('intentosFactura', 'pm.response.code === 200', 10, [
        estado(200),
        esJson,
        ...facturaCompleta,
        "pm.collectionVariables.set('comprobante', f.comprobante);",
        "pm.collectionVariables.set('cae', f.cae);",
      ]),
    }),
    pedido('GET /v1/facturas/{ventaId} otra vez (200, la misma)', 'GET', url('baseFacturas', ['{{ventaId}}']), {
      descripcion: 'Una compra tiene una sola factura: consultarla de nuevo da el mismo número y el mismo CAE.',
      cabeceras: [bearer('token')],
      tests: [
        estado(200),
        'const f = pm.response.json();',
        "pm.test('mismo comprobante y mismo CAE', () => {",
        "  pm.expect(f.comprobante).to.eql(pm.collectionVariables.get('comprobante'));",
        "  pm.expect(f.cae).to.eql(pm.collectionVariables.get('cae'));",
        '});',
      ],
    }),
    pedido('GET /v1/facturas/{ventaId} sin token (401)', 'GET', url('baseFacturas', ['{{ventaId}}']), {
      tests: problema(401, 'no-autenticado'),
    }),
    login('Login segunda cuenta compradora (200) guarda tokenOtroComprador', OTRO_COMPRADOR, 'user',
      'tokenOtroComprador', 'Otra cuenta compradora, para probar el aislamiento.'),
    pedido('GET /v1/facturas/{ventaId} de otro comprador (404)', 'GET', url('baseFacturas', ['{{ventaId}}']), {
      descripcion: 'La factura ajena responde igual que una que no existe.',
      cabeceras: [bearer('tokenOtroComprador')],
      tests: problema(404, 'no-encontrado'),
    }),
    pedido('GET /v1/facturas/{ventaId} inexistente (404)', 'GET', url('baseFacturas', ['V-00000000']), {
      cabeceras: [bearer('token')],
      tests: problema(404, 'no-encontrado'),
    }),
    login('Login staff (200) guarda tokenStaff', STAFF, 'staff', 'tokenStaff', 'Un rol que no ve facturas.'),
    pedido('GET /v1/facturas/{ventaId} con token de staff (403)', 'GET', url('baseFacturas', ['{{ventaId}}']), {
      cabeceras: [bearer('tokenStaff')],
      tests: problema(403, 'sin-permiso'),
    }),
    pedido('Token de servicio (200) guarda tokenServicio', 'POST', url('baseUsuarios', ['servicios', 'token']), {
      cabeceras: [JSON_CT],
      cuerpo: { secreto: '{{secretoServicios}}' },
      tests: [estado(200), "pm.collectionVariables.set('tokenServicio', pm.response.json().token);"],
    }),
    pedido('GET /v1/facturas/{ventaId} con rol servicio (200)', 'GET', url('baseFacturas', ['{{ventaId}}']), {
      descripcion: 'Otro servicio ve cualquier factura.',
      cabeceras: [bearer('tokenServicio')],
      tests: [
        estado(200),
        "pm.test('es la misma factura', () => pm.expect(pm.response.json().cae).to.eql(pm.collectionVariables.get('cae')));",
      ],
    }),
  ]);

// ---------------------------------------------------------------------------------------------

const variable = (key, value, description) => ({ key, value, type: 'string', description });

const coleccion = {
  info: {
    name: 'Palco API',
    description: 'API de Palco, la plataforma de venta de entradas con reventa oficial (Desarrollo de Aplicaciones II, '
      + 'UADE). Se corre entera, en orden, con el Runner o con Newman, contra el backend local: las carpetas 1 a 7 '
      + 'son automáticas y se pueden repetir sin reiniciar el servidor. La subcarpeta "Pasos manuales" queda fuera '
      + 'de la corrida. La guía está en docs/api/GUIA_POSTMAN.md.',
    schema: 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json',
  },
  variable: [
    variable('baseUsuarios', 'http://localhost:8080/api/usuarios', 'Raíz del servicio de Usuarios.'),
    variable('secretoServicios', 'palco-desarrollo', 'Secreto compartido de los servicios (PALCO_SECRETO_SERVICIOS). Si el servidor arrancó con otro, pasarlo con --env-var secretoServicios=... o cambiarlo en las variables.'),
    variable('baseEventos', 'http://localhost:8080/api/eventos', 'Raíz del servicio de Eventos.'),
    variable('baseApi', 'http://localhost:8080/api/v1', 'Raíz de la API versionada (Ventas).'),
    variable('baseValidacion', 'http://localhost:8080/api/v1/validacion', 'Raíz del servicio de Validación.'),
    variable('baseReventas', 'http://localhost:8080/api/v1/reventas', 'Raíz del servicio de Reventa.'),
    variable('baseFacturas', 'http://localhost:8080/api/v1/facturas', 'Raíz del servicio de Facturación.'),
    variable('eventoId', 'ev-1', 'Evento de prueba en el que se compra.'),
    variable('sectorId', 'sec-1-4', 'Sector de prueba en el que se compra: Platea B, 27.500.'),
    variable('codigoVerificacion', '', 'MANUAL: el código de 6 dígitos que sale en el log del servidor al registrarse.'),
    variable('token', '', 'Token del comprador (titular@palco.test). Lo guardan los login.'),
    variable('tokenOtroComprador', '', 'Token de la segunda cuenta compradora (otro.titular@palco.test). Lo guarda "Login segunda cuenta compradora".'),
    variable('tokenOrganizador', '', 'Token de organizador@palco.test. Lo guardan los "Login organizador".'),
    variable('tokenStaff', '', 'Token de puerta@palco.test. Lo guardan los "Login staff".'),
    variable('tokenServicio', '', 'Token de rol servicio; dura 5 minutos. Lo guarda "Token de servicio".'),
    variable('emailRegistro', '', 'Email de la cuenta que registra la corrida. Se genera solo.'),
    variable('dniRegistro', '', 'DNI de la cuenta que registra la corrida. Se genera solo.'),
    variable('slug', '', 'Slug del primer evento del catálogo. Lo guarda "Listar eventos".'),
    variable('eventoCreadoId', '', 'Id del evento que crea la corrida. Sale de la respuesta.'),
    variable('sectorCreadoId', '', 'Id del sector del evento que crea la corrida. Sale de la respuesta.'),
    variable('claveVenta', '', 'Idempotency-Key de la compra. Se genera en cada corrida.'),
    variable('clavePago', '', 'Idempotency-Key del pago. Se genera en cada corrida.'),
    variable('claveVentaCancelada', '', 'Idempotency-Key de la compra que se cancela. Se genera en cada corrida.'),
    variable('ventaId', '', 'Id de la venta que se paga. Sale de la respuesta.'),
    variable('montoTotal', '', 'Total de la venta que se paga. Sale de la respuesta.'),
    variable('precioUnitario', '', 'Precio del sector comprado. Sale de la respuesta.'),
    variable('pagoId', '', 'Id del pago. Sale de la respuesta.'),
    variable('entradaId', '', 'Id de la primera entrada de la venta. Sale de la respuesta del pago.'),
    variable('codigoQr', '', 'Código QR de esa entrada, una vez emitida. Lo escanea la carpeta 5.'),
    variable('intentosEmision', '0', 'Contador interno de la espera de la emisión.'),
    variable('ventasDelTitular', '', 'Ids de las ventas de titular@palco.test, en JSON. Lo guarda "GET /v1/ventas mis compras".'),
    variable('ventaCanceladaId', '', 'Id de la venta que se cancela. Sale de la respuesta.'),
    variable('escaneoId', '', 'Validación: id del escaneo VALIDA. Sale de la respuesta.'),
    variable('claveVentaReventa', '', 'Reventa: Idempotency-Key de la compra de las entradas que se publican.'),
    variable('ventaReventaId', '', 'Reventa: id de esa compra. Sale de la respuesta.'),
    variable('entradaReventaId', '', 'Reventa: la entrada que se publica y se vende.'),
    variable('entradaRetiroId', '', 'Reventa: la entrada que se publica y se retira.'),
    variable('publicacionId', '', 'Reventa: id de la publicación que se vende. Sale de la respuesta.'),
    variable('publicacionRetiroId', '', 'Reventa: id de la publicación que se retira. Sale de la respuesta.'),
    variable('claveCompraReventa', '', 'Reventa: Idempotency-Key de la compra en la reventa. Se genera en cada corrida.'),
    variable('entradaCompradaId', '', 'Reventa: la entrada nueva del comprador. Sale de la respuesta.'),
    variable('ventaCompradaId', '', 'Reventa: la venta del comprador en Ventas. Sale de la respuesta.'),
    variable('intentosFactura', '0', 'Contador interno de la espera de la factura.'),
    variable('comprobante', '', 'Facturación: punto de venta y número de la factura. Sale de la respuesta.'),
    variable('cae', '', 'Facturación: el CAE de la factura. Sale de la respuesta.'),
    variable('tokenA', '', 'Seguridad: token de la cuenta A (titular@palco.test).'),
    variable('tokenB', '', 'Seguridad: token de la cuenta B (otro.titular@palco.test).'),
    variable('ventaIdA', '', 'Seguridad: una compra emitida de A. Lo guarda "SEC GET /v1/ventas de A".'),
    variable('entradaIdA', '', 'Seguridad: una entrada de esa compra de A.'),
    variable('emailInexistente', '', 'Seguridad: email que no existe, nuevo en cada corrida (SEC-W03a).'),
    variable('detalleInexistente', '', 'Seguridad: el detail del login con un email que no existe (SEC-W03a).'),
    variable('tokenNone', '', 'Seguridad: un JWT con alg=none, sin firma. Se arma solo (SEC-W05b).'),
    variable('emailIntentos', '', 'Seguridad: email propio de SEC-W04, nuevo en cada corrida.'),
    variable('codigosPrevios', '', 'Seguridad: los códigos de los cinco intentos previos de SEC-W04, en JSON.'),
  ],
  item: [usuarios, eventos, ventas, seguridad, validacion, reventa, facturacion],
};

fs.writeFileSync(salida, JSON.stringify(coleccion, null, 2) + '\n', 'utf8');

const contar = (items) => items.reduce((n, i) => n + (i.item ? contar(i.item) : 1), 0);
console.log('Colección escrita en ' + salida + ': ' + contar(coleccion.item) + ' requests.');
