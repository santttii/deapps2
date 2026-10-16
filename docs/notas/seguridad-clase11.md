# Seguridad: cambios de la clase 11

Qué cambió en el backend a partir de la matriz de criterios de seguridad de la clase 11 (16/10), y por qué. Una
sección por criterio. Los veredictos de la matriz se actualizan solo con el test ejecutado.

## SEC-W04 · Límite de intentos de login

**Antes.** `ServicioDeUsuarios.iniciarSesion` no contaba los fallidos: se podían probar contraseñas sin límite.

**Ahora.** 5 inicios de sesión fallidos en 15 minutos para un mismo email bloquean ese email. Mientras dure el
bloqueo, `POST /api/usuarios/login` responde **429** Problem Details, `type` `/problemas/demasiados-intentos`, con
la cabecera `Retry-After` en segundos:

```http
HTTP/1.1 429 Too Many Requests
Content-Type: application/problem+json
Retry-After: 600

{"type":"/problemas/demasiados-intentos","title":"Demasiados intentos","status":429,
 "detail":"Hubo demasiados intentos fallidos. Esperá unos minutos y volvé a probar.","instance":"/api/usuarios/login"}
```

Decisiones:

- **Se bloquea aunque la contraseña sea la correcta.** El control va antes de verificar la contraseña. Si la
  correcta entrara, el bloqueo no frenaría a quien sigue probando: el 6.º intento acertado le abriría la cuenta.
- **No revela si el email existe.** Se cuenta por email exista o no la cuenta, y el texto del 401 y del 429 es el
  mismo en los dos casos. El 429 tampoco hace el cálculo PBKDF2, así que tarda lo mismo para los dos.
- **Ventana deslizante.** Cuentan los fallidos de los últimos 15 minutos. El bloqueo se levanta cuando el más viejo
  de los 5 sale de la ventana, y eso es lo que dice `Retry-After` (redondeado hacia arriba, nunca menos de 1: quien
  respeta la cabecera no recibe otro 429). Los intentos bloqueados no se anotan, así que el bloqueo no se estira
  con cada reintento.
- **Un login correcto borra los fallidos** de ese email: equivocarse cuatro veces y entrar no deja al usuario a un
  error del bloqueo.
- **Dónde se guardan: en la base**, tabla `intento_login` (migración `V4__intentos_de_login.sql` de Usuarios), y no
  en un mapa en memoria. Así el límite vale igual con varias instancias del servicio y no se reinicia con un
  redespliegue. La tabla guarda el SHA-256 del email normalizado y no el email, porque también anota emails sin
  cuenta (mal escritos, o los que prueba un atacante). Cada fallido nuevo borra los de ese email que ya salieron de
  la ventana, para que la tabla no crezca. El SQL no tiene `check` de lista de valores y corre en H2 y en PostgreSQL.
- **El intento queda guardado aunque la respuesta sea un error.** Los errores de negocio deshacen la transacción;
  el 401 del login usa `CredencialesIncorrectas`, con `@ApplicationException(rollback = false)`, el mismo recurso
  que ya usaba el código de verificación (`CodigoIncorrecto`).
- **Constantes.** `MAX_INTENTOS_LOGIN = 5` y `VENTANA_INTENTOS_LOGIN = 15 min` están en `ServicioDeUsuarios`, junto
  a `MAX_INTENTOS_CODIGO`: son de seguridad de Usuarios y no reglas del negocio de venta de entradas, y
  `ReglasDeNegocio` tiene una copia en el frontend que no las necesita.
- **Límite conocido.** El bloqueo es por email: alguien puede bloquear 15 minutos el login de una cuenta ajena si
  conoce su email. Se acepta para esta etapa; contar también por IP lo atenuaría.

Piezas: `ErroresDeNegocio.DemasiadosIntentos` (nueva, 429) y su manejador en `ManejadoresDeErrores`, que agrega
`Retry-After`; `IntentosDeLogin` (Session Bean stateless que consulta y escribe la tabla) y la entidad `IntentoLogin`.

**Cómo se prueba.**

- `LimiteDeIntentosDeLoginTest` (Usuarios), con un reloj inyectable y los intentos en una lista: 5 fallidos y el
  6.º con la clave buena da 429 con 600 segundos de espera; a los 15 minutos menos un segundo sigue el 429 con
  `Retry-After` 1 y al cumplirse los 15 el login entra; un email inexistente cuenta igual y recibe el mismo texto;
  el email se cuenta sin importar mayúsculas ni espacios; los fallidos de un email no bloquean a otro; 4 fallidos no
  bloquean y un login correcto los borra.
- `MigracionesEnH2Test` (Usuarios): las migraciones corren en H2 en modo PostgreSQL y la tabla responde a la
  consulta de la ventana.
- `ErroresDeNegocioTest` (common): el tipo `demasiados-intentos`, el mínimo de 1 segundo y el rollback.
- En vivo: el request "SEC-W04" de la carpeta de seguridad de Postman (cinco fallidos con un email nuevo y el sexto
  espera 429 con `Retry-After`).

## Id de error en los 500

**Antes.** `ManejadoresDeErrores.Inesperado` escribía la traza en el log y respondía un 500 sin nada que la
relacionara con ese pedido.

**Ahora.** Cada error inesperado lleva un id nuevo (un UUID al azar, que no dice nada del error). El id va en el log,
en el mismo renglón que la traza (`Error no manejado (idError <id>)`), y en la respuesta como miembro de extensión
`idError` del Problem:

```json
{"type":"/problemas/error-inesperado","title":"Error inesperado","status":500,
 "detail":"Ocurrió un error inesperado.","instance":"/api/v1/ventas","idError":"3b9f2c1e-7a4d-4e8b-9c2f-1d6a5e0b7c43"}
```

Quien reporta el error pasa el id y la traza se encuentra en el log sin mandarle al cliente ningún detalle interno.
El nombre sigue el estilo de la otra extensión propia (`errores`). Los demás errores no cambian: `idError` solo se
escribe cuando tiene valor.

**Cómo se prueba.** `ErrorInesperadoTest` (common): el id de la respuesta es un UUID, es el mismo que aparece en el
mensaje del log, el log lleva la excepción con su traza, dos errores tienen ids distintos y un 409 no trae `idError`.

## Iteraciones de PBKDF2

**Antes.** `Contrasenas` usaba 310.000 iteraciones de PBKDF2-HMAC-SHA256.

**Ahora.** 600.000, lo que recomienda hoy OWASP (Password Storage Cheat Sheet) para PBKDF2-HMAC-SHA256.

- **Los hashes viejos siguen verificando.** El formato guardado es
  `pbkdf2-sha256$<iteraciones>$<sal>$<hash>`, y `verificar` usa las iteraciones del hash guardado. Las cuentas de
  prueba de V2 y V3 (310.000) entran igual, y no hace falta migrar datos.
- **El hash falso cuesta lo mismo que uno nuevo.** El que se usa cuando el email no existe (para no revelarlo por el
  tiempo de respuesta) se arma con `Contrasenas.hashear`, así que toma las iteraciones nuevas solo.
- **Las cuentas de prueba pasan a 600.000** (`V5__cuentas_de_prueba_con_600000_iteraciones.sql`), con la misma
  contraseña. Si quedaban en 310.000, verificaban en la mitad de tiempo que el hash falso, y esa diferencia dejaba
  ver qué emails existen (SEC-W03 compara justamente esos tiempos). La migración solo cambia la fila que todavía
  tiene el hash original de V2 o V3.
- **Límite conocido.** Una cuenta registrada antes de este cambio conserva su hash de 310.000 hasta que cambie la
  contraseña, y verifica más rápido que un email inexistente. Rehashear con las iteraciones nuevas al iniciar sesión
  cerraría la diferencia; queda propuesto.

**Cómo se prueba.** `ContrasenasTest`: un hash nuevo empieza con `pbkdf2-sha256$600000$` y verifica, uno de 310.000
sigue verificando con la clave buena y no con otra. `LimiteDeIntentosDeLoginTest.elHashFalsoCuestaLoMismoQueElDeUnaCuentaNueva`:
el hash falso usa `Contrasenas.ITERACIONES`. `MigracionesEnH2Test` (Usuarios): después de las migraciones las cuatro
cuentas de prueba tienen hashes de 600.000 que verifican con `palco1234`. `CuentasDePruebaTest` sigue verificando
las filas de V2 y V3.

## Secreto de servicios fuera de desarrollo (propuesta, sin cambios de código)

**Hoy.** Si falta `PALCO_SECRETO_SERVICIOS`, `TokenDeServicio` usa `palco-desarrollo`, que está escrito en el repo,
y deja un aviso en el log. Quien lea el código puede pedir un token de rol `servicio` a un servidor que arrancó así.

**Propuesta.** Que el valor de desarrollo solo valga cuando el entorno lo dice de forma explícita:

1. Una variable `PALCO_ENTORNO`. `dev.sh` la define como `desarrollo`; en cualquier otro servidor no se define.
2. En `TokenDeServicio.leerSecreto`: si falta el secreto y `PALCO_ENTORNO` no es `desarrollo`, se lanza
   `IllegalStateException("Falta PALCO_SECRETO_SERVICIOS")` en vez de usar el valor de desarrollo. También se
   rechaza un secreto definido con el valor de desarrollo, o de menos de 32 caracteres.
3. Para que falle **al arrancar** y no en la primera llamada entre servicios: un `@Startup @Singleton` en Usuarios
   (que es quien emite los tokens con ese secreto) llama a `TokenDeServicio.secretoCompartido()` en su
   `@PostConstruct`. Si lanza, WildFly no despliega el WAR y el error queda en el log del arranque. Los demás
   servicios usan el secreto recién al pedir un token; pueden sumar la misma comprobación.

Así el olvido no se descubre en producción: el servidor no queda andando con un secreto público. El cambio toca
`backend/common` (`TokenDeServicio`) y `dev.sh`, y se acuerda con quien es dueño de `common`. Para la demo del 23/10
alcanza con definir el valor en `backend/.env`, que no se commitea.
