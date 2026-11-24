# Lecciones aprendidas: tres defectos que llegaron tarde

Tres defectos que se encontraron tarde, por qué las pruebas de ese momento no los detectaron y qué prueba los habría
atrapado. Los ID, las fechas y los commits son los de [`registro-de-defectos.md`](registro-de-defectos.md).

| Defecto | Severidad | Entró | Se corrigió | Prueba que faltaba |
|---|---|---|---|---|
| DEF-01: el `master` no desplegaba ningún servicio | Bloqueante | 30/10 | 05/11 (`39d358f`) | Humo de despliegue: levantar el servidor y exigir los `health` |
| DEF-16: el tope de la reventa sobre lo pagado | Crítica | 19/10 | 19/11 (`45e2355`) | Revender dos veces la misma entrada |
| DEF-27: el broker perdía mensajes al recrear el contenedor | Crítica | 30/10 | 19/11 (`030e2eb`) | Mensaje pendiente, `docker compose down` y `up` |

## 1. DEF-01: 761 tests en verde y el servidor sin desplegar

**Qué pasó.** El `master` del 30/10 (`f655139`) no desplegaba ningún servicio: los 8 WAR quedaban `.failed` por tres
problemas de inyección (faltaba la capa `mail` de Galleon, y había dos pares de beans ambiguos, en correos y en el
almacén de imágenes). Se corrigió el 05/11 (`39d358f`). Durante seis días el sistema no arrancó, ni con `./dev.sh` ni
con Docker.

**Por qué llegó tarde.** `mvn verify` daba 761 tests en verde. Todos eran unitarios o de base: los Session Beans se
prueban con Mockito, con los otros servicios como dobles, y ninguno arranca el contenedor. La resolución de beans de
CDI (qué implementación se inyecta, si hay dos candidatas) y las capas de Galleon solo se ejercitan al desplegar, y no
había ninguna prueba que desplegara. El job de integración del CI, que levanta el compose, se armó ese mismo 30/10
(`8ff9b63`): el defecto ya estaba en `master`.

**Qué prueba lo habría atrapado.** Una prueba de humo de despliegue antes de mergear: levantar el servidor y exigir
que los `health` de todos los servicios respondan 200.

**Qué lo cubre hoy.** El job "Integración" del CI: `docker compose up --build --wait`, el humo por HTTPS con los 9
`health`, los escenarios y el control de logs, en cada push y cada pull request. El plan lo pone como criterio de
entrada y de suspensión (sección 4) y como riesgo R-05.

**Lección.** Cantidad de tests no es cobertura del riesgo. Los unitarios prueban la lógica; el riesgo de "no arranca"
lo prueba solo algo que arranca.

## 2. DEF-16: el tope de la reventa sobre lo pagado y no sobre el precio de lista

**Qué pasó.** Desde que existe la reventa (19/10, `bcbc26a`), el rango de precio se calculaba sobre lo que pagó quien
vende. Una entrada comprada en la reventa nace con el precio de esa reventa como precio pagado, así que cada reventa
subía el tope otro 10 %: 21 % sobre la lista con dos, 33 % con tres. Se corrigió el 19/11 (`45e2355`), un mes
después.

**Por qué llegó tarde.** La regla se escribió desde el principio como "lo pagado más el 10 %": así se llama el test de
`ReglasDeNegocio` (`topeReventaEsLoPagadoMasDiezPorCientoRedondeadoHaciaAbajo`), y así lo decían la pantalla de
reventa, Mis entradas y el asistente. El criterio CA-08.1 parte de "una entrada que pagué $10.000", y en una entrada de
primera mano lo pagado y el precio de lista coinciden: todos los casos (los bordes del rango, el escenario 17 y la
suite de la interfaz) usaban entradas compradas en el catálogo y pasaban. Las clases de equivalencia separaban precios
válidos e inválidos, pero no el origen de la entrada (comprada en el catálogo o en la reventa), que es justo donde las
dos lecturas de la regla dan distinto.

**Qué prueba lo habría atrapado.** Una que revenda dos veces la misma entrada: comprar al precio de lista, revender al
tope, y que quien la compró la vuelva a publicar. El tope tiene que seguir en la lista más el 10 %. Es una prueba de
transición de estados sobre la entrada, no de valores límite sobre un precio.

**Qué lo cubre hoy.** `ServicioDeReventaTest#unaEntradaRevendidaConservaElTopeYElMinimoDelPrecioDeLista` recorre las
dos reventas, y el escenario 17 publica al tope exacto contra el sistema levantado. El rango sale del precio de lista
del sector, que no cambia con las reventas.

**Lección.** Cuando una regla depende de un dato que cambia con el ciclo de vida del objeto (lo pagado cambia con cada
reventa; el precio de lista no), los casos tienen que recorrer ese ciclo, no solo el primer estado.

## 3. DEF-27: el broker perdía los mensajes pendientes al recrear el contenedor

**Qué pasó.** En el compose, el journal del broker y el registro de transacciones (`standalone/data`) estaban en la
capa escribible del contenedor, y la base en un volumen. Si el contenedor se recreaba (`docker compose down` y `up`,
`--force-recreate` o una imagen nueva) con un mensaje pendiente, el mensaje se perdía y la venta quedaba `CONFIRMADA`
con las entradas sin emitir, para siempre. El compose existe desde el 30/10; se corrigió el 19/11 (`030e2eb`,
ADR-005).

**Por qué llegó tarde.** La prueba de resiliencia del broker (`broker-apagado.mjs`, del 18/11) deja un mensaje
pendiente y reinicia el servidor con `:shutdown(restart=true)`, dentro del mismo contenedor: la capa escribible
sobrevive y la prueba pasaba, 10 de 10. Probaba "se apaga el broker", pero no la operación que de verdad se hace con
el compose, que es bajarlo y volver a levantarlo. La base sí estaba en un volumen, y nada comparaba lo que dura la base
con lo que dura el broker.

**Qué prueba lo habría atrapado.** La misma prueba, pero con la operación real: mensaje pendiente, `docker compose
down` sin `-v`, `docker compose up` y comprobar que la venta pasa a `EMITIDA` con el contenedor nuevo.

**Qué lo cubre hoy.** `broker-apagado.mjs` en el job "Integración" del CI, y la prueba con el contenedor recreado,
corrida el 19/11 contra el compose: 16 de 16, con el id del contenedor cambiado
([`evidencia/checkpoint-3/broker-recreado.txt`](evidencia/checkpoint-3/broker-recreado.txt); los pasos están en
`scripts/LEEME.md`).

**Lección.** Una prueba de resiliencia tiene que reproducir la falla tal como pasa en la operación. Reiniciar un
proceso no es lo mismo que recrear su contenedor.

## Lo que tienen en común

En los tres, las pruebas pasaban y probaban lo que el código hacía, pero no el camino en que fallaba: el despliegue
(DEF-01), la segunda reventa de la misma entrada (DEF-16) y el contenedor recreado (DEF-27). En los tres, la prueba que
los habría atrapado es de un nivel más alto que la que había: de sistema en lugar de unitaria, o con la operación real
en lugar de una simulada.
