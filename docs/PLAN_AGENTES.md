# Plan de trabajo con agentes — hacia el 23/10, el 06/11, el 13/11, el 20/11 y el 27/11

Hecho el 09/10/2026 a partir de la consigna del TP (`docs/consigna/TP_Integrador_...docx`), `TODO.md`, el código de
`backend/` y los apuntes de clase. **Actualizado el 13/10/2026** por dos motivos:

- **Las fechas eran de otra comisión.** El grupo cursa los viernes (08:45 a 11:45) y la consigna está escrita para la
  comisión del lunes. Este plan usa ahora el cronograma de los viernes, que publica la cátedra. La tabla de
  equivalencias está en `CLAUDE.md`, sección "Fechas de la comisión (viernes)".
- **El 09/10 casi nada estaba implementado y hoy la compra funciona de punta a punta.**

Nombres: lo que este plan llamaba "Entrega 3 (19/10)" es el **checkpoint del 23/10** (la Entrega Parcial N.º 2 de la
consigna), y lo que llamaba "Entrega 4 (09/11)" es la **2.ª entrega parcial del 13/11** (la Entrega Obligatoria N.º 2
de la consigna). **El 23/10 no está confirmado con la cátedra:** el cronograma de los viernes no nombra ese
checkpoint, y la fecha sale de sumarle a la de la consigna los cuatro días que separan a las demás fechas de las dos
comisiones.

De dónde sale el estado: de las notas de `docs/notas/`, que dicen qué se probó en el servidor el 13/10; del árbol de
archivos; y del historial de git. Para esta actualización **no se compiló ni se levantó nada**. Mientras se escribía
corría la verificación final de lo construido (armado desde cero y revisión adversaria): si encuentra algo, hay que
corregir la tabla de estado de abajo.

---

## 1. Dónde estamos vs. lo que pide la cátedra

### Estado real de los 8 servicios (al 13/10/2026)

| Servicio | Estado en el repo |
|---|---|
| Usuarios | Funciona: registro, verificación de email, login JWT, roles y token de rol `servicio` para las llamadas entre servicios |
| Eventos | Funciona: catálogo, detalle, crear, cupo atómico. `@Stateless`. **No tiene tests** |
| Ventas | Funciona: compra en dos llamadas con el cupo reservado 10 minutos (`@Stateful`), pago, emisión asincrónica de entradas por la cola y vencimiento de la reserva. API en `/api/v1` |
| Pagos | Funciona con una **pasarela simulada** (`PagosFake`). Ventas le cobra por REST con el token de servicio. Faltan Mercado Pago, el webhook y el reembolso |
| Notificaciones | Funciona: aviso de compra y auditoría durable, suscriptos al tópico. El aviso es una línea en el log, no un email |
| Facturación, Reventa, Validación | **Solo el esqueleto** (`*Application.java`, `pom.xml` y el `health`) |
| Mensajería | `PAGO_CONFIRMADO` se publica en `cola.emision-entradas` y en `topico.compra-confirmada` dentro de la transacción que confirma la venta. Hay 5 reintentos cada 2 segundos y DLQ |
| Frontend | Sesión, catálogo, alta de eventos y compra usan el backend. Reventa, puerta y panel del organizador siguen con datos locales |
| Transversal | Errores en Problem Details en los 8 servicios, colección de Postman, guion de demo (`scripts/demo-entrega3.sh`) y una nota para la defensa por tarea en `docs/notas/` |

### Brechas contra la consigna

**Checkpoint del 23/10** (Entrega Parcial N.º 2 de la consigna; formativo, sin defensa; fecha sin confirmar).

| Requisito | Estado |
|---|---|
| Diagrama de integración (SOA): componentes, sistemas externos y canal | Hecho: `docs/entrega3/Diagrama_Integracion_SOA.svg` y su fuente `.drawio` |
| Clasificación justificada síncrona/asincrónica de cada integración externa | Hecha: sección 3 de `docs/entrega3/Entrega_Parcial_2_Integracion.md` |
| **1 proceso de negocio con mensajería real de punta a punta, con broker configurado** | Hecho y con evidencia: `docs/entrega3/evidencia/` |
| Lo que falta lo hace una persona | El tag `entrega-parcial-2`; la evidencia tomada desde ese tag; la lectura de los cinco; el texto sobre el uso de IA; confirmar la fecha de la carátula, que dice 23/10/2026; el PDF definitivo; y subirlo. El detalle está en `docs/notas/docs-entrega3.md` |
| Documentación funcional, manual de usuario y prototipo Figma (Anexo) | No se hicieron: la consigna no los pide y la consulta a la cátedra no tuvo respuesta (pregunta 9) |

**Entrega del diseño del 06/11.** Es un hito nuevo: está en el cronograma de los viernes y la consigna no lo nombra.
**No se sabe qué se entrega.** Mientras no haya respuesta, se prepara para esa fecha el paquete de diseño: el borrador
del documento técnico con los diagramas de secuencia, el WSDL y `docs/api/openapi.yaml` al día.

**2.ª entrega parcial del 13/11** (Entrega Obligatoria N.º 2 de la consigna; obligatoria, con defensa oral de 20 a 25
minutos).

| Requisito de la consigna | Estado al 13/10 | Tarea |
|---|---|---|
| 2 procesos con mensajería de punta a punta: 1 por cola y 1 por tópico | Funcionan los dos, disparados por el mismo evento: la emisión de entradas (cola) y el aviso y la auditoría (tópico). Si la cátedra los cuenta como un solo proceso, C1 suma otro: el reintento de la factura, por una cola propia | C1 |
| 1 servicio SOAP **con su WSDL**, justificado como sistema legado | **No existe.** Facturación es solo el esqueleto | C1 |
| 1 servicio REST (partner moderno o API de consumo externo) | La API propia funciona y la consume el frontend: con eso se cubre. Mercado Pago está simulado (pregunta 11) | D1, que no es requisito de esta entrega |
| Transacciones declarativas en un flujo crítico de varios pasos | Existe: `confirmarCompra` (`REQUIRED`) y el publicador (`MANDATORY`). La falla a mitad del flujo se probó con un WAR temporal que no quedó en el repo | C3 |
| Documento técnico actualizado (8 a 12 páginas) con un diagrama de secuencia por integración | **No existe.** La base es `docs/entrega3/` y las notas | C5 |
| Demo que cruce componentes, mensajería y servicios web | `scripts/demo-entrega3.sh` cubre la compra, el pago y la mensajería. Falta el tramo de la factura SOAP | C1 |
| Declaración del uso de IA (obligatoria en esta entrega) | Está en el documento del checkpoint, sección 6.3. Hay que llevarla al documento nuevo | C5 |
| Cualquier integrante defiende cualquier parte | Hay una nota por tarea con las cinco preguntas más probables. Falta que los cinco las lean todas y un ensayo con preguntas cruzadas | Ola 4 |

**3.er checkpoint del 20/11.** También es nuevo y tampoco se sabe qué se entrega. El plan es mostrar Validación y
Reventa funcionando.

**Prueba del TP del 27/11.** Es el límite para tener el sistema completo. Checklist transversal de la consigna
(sección 6), que se evalúa en la Entrega Final:

| Requisito | Estado al 13/10 |
|---|---|
| ≥ 6 componentes de negocio identificados, con interfaz documentada | 8 identificados y 5 implementados: Usuarios, Eventos, Ventas, Pagos y Notificaciones. El sexto es Facturación (C1); después vienen Validación (B3) y Reventa (C2) |
| ≥ 1 componente **stateful** y 1 stateless, justificados | ✅ `ServicioDeVentas` es `@Stateful`, con su ciclo de vida en el log. El resto son stateless |
| Capas explícitas (presentación, negocio, datos) en cada componente implementado | ⚠️ Ventas y Pagos tienen las tres: `api/`, `negocio/` y `datos/`. En Usuarios, Eventos y Notificaciones el negocio usa `EntityManager` directo y la capa de datos no está separada (B4) |
| ≥ 3 patrones de diseño aplicados y justificados | En el código hay Repository (Ventas y Pagos), Adapter (`ClienteDePagos`), Factory (`FabricaDePasarela`, `FabricaDeCobros`) y Facade (`ComprasEnCurso`). Falta la justificación en el documento (C5). Eventos, Usuarios y Notificaciones siguen con `EntityManager` directo (B4) |
| SOAP + WSDL | ❌ C1 |
| REST / cola / tópico | ✅ |
| Transacciones declarativas | ✅, con lo que completa C3 |
| Seguridad declarativa en ≥ 2 operaciones | ✅ `@RolesAllowed` en todos los servicios implementados |
| Git incremental con **participación visible de todos** | ⚠️ Ver riesgo 1 abajo |
| Sistema completo, de punta a punta | Faltan Facturación, Validación y Reventa; Mercado Pago real con su webhook; y el frontend de reventa, puerta y panel |
| Documentación final consolidada (10 a 15 páginas) | No existe. Sale de ampliar el documento del 13/11 (D3) |

### Preguntas para la cátedra

Hay que mandarlas por Teams cuanto antes (la consigna pide consultar por ese canal antes de la clase) y repetirlas en
persona en la clase del 16/10. Con cada respuesta se corrige este plan.

1. **Fecha del checkpoint.** ¿La Entrega Parcial N.º 2 de la consigna (19/10 para la comisión del lunes) se entrega
   el viernes 23/10? El cronograma de los viernes tiene un 1.er check point (04/09) y un 3.º (20/11), pero ningún 2.º:
   ¿es el 23/10 o es la "Entrega del diseño" del 06/11?
2. **06/11, "Entrega del diseño del TP Obligatorio".** ¿Qué se entrega, en qué formato, y tiene defensa o nota?
3. **20/11, "3er Check point TPO".** ¿Qué se entrega o se muestra?
4. **27/11, "Prueba del TP Obligatorio".** ¿Es una demo del sistema completo, la Entrega Final con su defensa o un
   ensayo? ¿Qué hay que subir, y cuándo?
5. **Entrega Final.** ¿En qué fecha se entrega y se defiende en esta comisión: 27/11, 04/12 u 11/12? La consigna da
   tres fechas distintas (21/12 en la tabla, 30/11 en el detalle y 04/12 u 11/12 en el encabezado) y dos notas mínimas
   (4 en la tabla y 8 en el detalle).
6. **Hora límite.** La consigna dice "antes de las 13:30", que es de la comisión del lunes. ¿Para los viernes es antes
   de la clase de las 08:45?
7. **Defensa del 13/11.** ¿Se defiende ese mismo viernes, en el horario de clase?
8. **Consigna de la comisión.** El texto dice "Comisión Lunes TM" y el archivo se llama "Viernes". ¿Hay una versión
   para los viernes?
9. **Anexos.** ¿Hacen falta la documentación funcional, el manual de usuario y el prototipo en Figma? ¿Para qué fecha?
   (Es la consulta que figura sin respuesta en `docs/notas/docs-entrega3.md`.)
10. **SOAP.** ARCA exige un certificado para su ambiente de prueba. ¿Vale como servicio SOAP un sistema legado
    simulado por el grupo, publicado con su WSDL y declarado como simulado?
11. **REST.** ¿Alcanza con la API REST propia, que consume el frontend, o hace falta la integración real con un
    partner externo (Mercado Pago)? ¿Vale una pasarela simulada y declarada como tal?
12. **Dos procesos asincrónicos.** La emisión de entradas (cola) y el aviso y la auditoría (tópico) salen del mismo
    evento, `PAGO_CONFIRMADO`. ¿Cuentan como los dos procesos de negocio que pide la consigna?
13. **Componentes.** De los 6 componentes mínimos, ¿cuántos tienen que estar implementados en la Entrega Final?
14. **Documento "actualizado".** ¿El documento del 13/11 tiene que ser una actualización del que se presentó el 18/09,
    o puede ser uno nuevo que lo incluya?
15. **Grupo de cinco.** La consigna fija de 3 a 4 integrantes. Confirmar que el grupo de cinco está avalado.
16. **Repositorio.** Si el repo es privado, ¿a qué usuario de GitHub hay que darle acceso?

### Riesgos que no son de código

1. **Autoría en git.** El historial tiene 14 commits: 5 de `Improvement <Improvement@mesucan.com.ar>` (una identidad
   mal configurada, que hay que atribuir a su dueño real), 3 de Sebastian Deya, 2 de Julian Zuchowicki, 2 de
   `vfrisoli` y 2 de `santttii`. **No hay ninguno a nombre de Joaquin Molina.** Además, todo lo construido entre el
   09/10 y el 13/10 entró en dos commits de una sola persona, de 197 y de 77 archivos. La consigna pide un historial
   incremental, "no una única entrega final volcada de golpe", y "participación visible de todos los integrantes".
   Cada uno tiene que configurar `git config user.name` y `user.email` **antes** de volver a commitear. Los commits
   viejos se atribuyen con un `.mailmap`, que todavía no existe, sin reescribir la historia.
2. **Los agentes no deben commitear a nombre de una sola persona.** Cada tarea la corre su dueño o dueña, en su
   máquina y con su identidad, en commits chicos: uno por paso que se pueda verificar. Entre el 09/10 y el 13/10 no
   se hizo así; desde la Ola 3, sí. Para eso los cinco necesitan el entorno andando (JDK 25 y Maven).
3. **Defensa oral individual.** Que un agente escriba el código no alcanza: cada dueño o dueña tiene que poder
   explicar su parte, y la consigna pide que cada integrante pueda defender cualquiera. Por eso cada tarea deja una
   nota corta en `docs/notas/<tarea>.md` con el porqué de cada decisión, y antes de cada defensa hay un ensayo con
   preguntas cruzadas.
4. **La fecha del checkpoint no está confirmada.** El 23/10 es una deducción (pregunta 1). Antes de esperar la
   respuesta conviene mirar en el aula virtual de la comisión si la entrega ya está creada y qué fecha y hora de
   cierre tiene. Para no depender de nada de eso, el documento queda listo para subir el jueves 15/10: si la fecha
   resulta ser anterior al 23/10, ya está hecho.
5. **Dos hitos nuevos no tienen contenido definido:** la entrega del diseño del 06/11 y el 3.er checkpoint del 20/11
   (preguntas 2 y 3). Este plan supone qué llevar a cada uno. Si la cátedra pide otra cosa, por ejemplo el prototipo
   o la documentación funcional, es trabajo que hoy no está en el calendario: el plan de la documentación lo estimó
   en cuatro días de una persona.
6. **La hora de entrega tampoco está confirmada** (pregunta 6). Por eso cada entrega se sube el jueves anterior.
7. **La consigna se contradice sobre la Entrega Final** (pregunta 5). Se toma la fecha más temprana del cronograma,
   el 27/11, como límite para el sistema completo.
8. El tablero de Notion estaba atrasado el 09/10 (Eventos y Usuarios figuraban "Sin empezar"). El 13/10 no se revisó.

---

## 2. Cómo trabajar con agentes (reglas)

- **Un agente = una rama = una tarea del tablero = un/a dueño/a.** Rama `feat/<servicio>-<tema>`, de preferencia en su
  propio `git worktree` (`git worktree add ../palco-ventas feat/ventas-core`) para correr varios en paralelo sin pisarse.
  En las Olas 1 y 2 no se hizo así: se trabajó en un solo árbol y los commits los hizo una sola persona (riesgo 1).
  Desde la Ola 3 el commit de cada tarea lo hace su dueño o dueña, con su identidad y de a pasos chicos. El agente no
  hace commits.
- **Archivos disjuntos y un dueño por módulo.** Cada brief dice qué módulos puede tocar. Si una tarea necesita un
  cambio en el módulo de otra persona (por ejemplo, Reventa necesita una operación nueva en Ventas), se lo pide a su
  dueño y se acuerda en el plan, antes de escribir código.
- **Lo compartido se toca en un paso aparte.** `backend/common` viaja dentro de los 8 WAR, y `backend/servidor`
  (con `configurar.cli`) arma el servidor. Un cambio ahí tiene un solo dueño, se avisa al grupo y termina con los 8
  servicios reconstruidos y redesplegados y los 8 `health` comprobados.
- **Plan antes de código.** Pedirle a cada agente que primero escriba su plan (qué archivos, qué decisiones) y que
  vos lo apruebes; recién después implementa. Es lo que evita que decida solo cosas que después tenés que defender.
- **Verificar antes de dar por hecho.** Todo agente termina corriendo `mvn -q test` y el `curl` de su servicio, y
  reporta el resultado real. Al final de cada ola, un agente revisor distinto contrasta contra la consigna.
- **`CLAUDE.md` en la raíz** (incluido junto a este plan) le da a cada agente las convenciones del repo; sin eso cada
  uno inventa su estilo.
- **Orden de merge** por ola (abajo). Un agente no arranca si su dependencia no está mergeada.
- **Fechas de la comisión de los viernes.** Las de la consigna son de la comisión del lunes y no se usan: la tabla
  está en `CLAUDE.md`.
- **Cada entrega sale de un tag y se sube el jueves anterior a la clase.** Antes se congela, en la fecha que dice el
  calendario: desde ahí solo entran arreglos. Lo que no esté cerrado y revisado al congelar queda fuera de esa
  entrega, en su rama.

---

## 3. Olas de trabajo

Replanificadas el 13/10/2026 con el cronograma de los viernes. El orden de lo que falta sigue este criterio:

1. Primero, lo que la consigna exige para el 13/11 y hoy no existe: el servicio SOAP con su WSDL (C1) y el documento
   técnico con los diagramas de secuencia (C5). Con ellos va C3, porque la defensa del 13/11 pregunta qué pasa con la
   transacción ante una falla y hoy esa prueba no se puede repetir.
2. Después, lo que suma componentes implementados. Hoy son 5 y la consigna pide un mínimo de 6 componentes (cuántos
   tienen que estar implementados es la pregunta 13). Facturación (C1) es el sexto y Validación (B3), el séptimo. B3
   es chica y no depende de C1, así que cubre el mínimo aunque C1 se atrase.
3. Después, el resto: capas y tests (B4), contrato OpenAPI (C4), Reventa (C2), Mercado Pago real (D1), lo que queda
   del frontend (D2) y la documentación final (D3).

```
OLA 0 (09/10)           hecha, salvo la identidad de git de cada integrante y el .mailmap
OLA 1 (10/10 al 13/10)  hecha: A1 ventas-core · A2 mensajeria · A3 pagos · A4 docs-entrega3
OLA 2 (13/10)           hecha: B1 integracion · B2 frontend (sesión, catálogo y compra)
                        pasan a la Ola 3: B3 validacion-qr · B4 patrones-y-tests
                        ─── CHECKPOINT · vie 23/10 (sin confirmar) · se sube el jue 22/10 ───
OLA 3 (14/10 al 05/11)  primero: C1 facturacion-soap · C5 doc-tecnico · C3 transacciones · B3 validacion-qr
                        después: B4 patrones-y-tests · C4 openapi · pantalla de puerta (D2)
                        sin bloquear: plan y arranque de C2 reventa y de D1 mercadopago
                        ─── ENTREGA DEL DISEÑO · vie 06/11 · se sube el jue 05/11 ───
OLA 4 (06/11 al 12/11)  se congela el lun 09/11 · Revisor · armado desde cero · demo · documento · ensayo
                        ─── 2.ª ENTREGA PARCIAL, con defensa oral · vie 13/11 · se sube el jue 12/11 ───
OLA 5 (13/11 al 26/11)  C2 reventa · D1 mercadopago-webhook · D2 frontend-resto · D3 doc-final
                        ─── 3.er CHECKPOINT · vie 20/11 · se sube el jue 19/11 ───
                        ─── PRUEBA DEL TP · vie 27/11 · se congela el mar 24/11 · se sube el jue 26/11 ───
Después                 final adelantado (04/12) o final regular (11/12)
```

### Calendario

Las clases son los viernes a las 08:45. Cada entrega se sube el jueves anterior, y ese jueves queda de colchón: lo que
se entrega tiene que estar cerrado antes. Del 09/10 al 13/10 se hicieron las Olas 0, 1 y 2.

**Lo que queda de octubre, día por día**

| Día | Qué | Quién |
|---|---|---|
| Mar 13/10 | Termina la verificación final de lo construido. Salen por Teams las preguntas para la cátedra (sección 1). Se mira en el aula virtual qué fecha y hora de cierre tiene cada entrega | Santiago |
| Mié 14/10 | Commit de lo que quedó sin commit, tag `entrega-parcial-2`, servidor armado desde ese tag y evidencia final (`docs/entrega3/evidencia/reproducir.sh`). `.mailmap`. Cada integrante deja andando su identidad de git y su entorno (JDK 25, Maven y `./dev.sh`). Salen los briefs de C1, C3 y B3 | Santiago; los cinco |
| Jue 15/10 | Los cinco terminan de leer el documento del checkpoint y se completa el texto sobre el uso de IA. **El PDF queda listo para subir.** Se aprueban los planes de C1 y de C3 | Los cinco; Joaquin arma el PDF |
| **Vie 16/10** | Clase. Se consulta en persona lo que no tuvo respuesta por Teams y ese mismo día se corrige este plan. Arranca el código de C1 y de C3 | Los cinco; Santiago corrige el plan |
| Sáb 17 y dom 18/10 | C1: el sistema legado simulado, con su WSDL publicado. C3: prueba de la falla a mitad del flujo, que se pueda repetir. B3: plan | Santiago; Sebastian; Joaquin |
| Lun 19/10 | C1: el WSDL se descarga y una factura de prueba responde. B3: plan aprobado y arranca el código. C5: índice propuesto | Santiago; Joaquin |
| Mar 20/10 | C5: índice aprobado y lista de diagramas de secuencia, cada uno con su dueño. C2: arranca el plan (qué le pide a Ventas, a Pagos y a Validación) | Joaquin; Julian |
| Mié 21/10 | C1: al confirmarse una compra se le pide la factura al servicio SOAP, y `facturaEstado` deja de valer `NO_APLICA` | Santiago |
| Jue 22/10 | **Se sube el checkpoint**: enlace al repositorio y PDF. C1 la revisa alguien que no la escribió | Joaquin y Santiago; Julian |
| **Vie 23/10** | **Checkpoint, fecha sin confirmar.** Clase: se le muestra el WSDL a la cátedra. Punto de control de C1 (ver "Líneas de corte") | Los cinco |
| Sáb 24 y dom 25/10 | C1: timeout, Fault y cola de reintento. C3: conciliación de las ventas con un cobro sin resultado. B3: cierre, con su nota y el request 7 de Postman | Santiago y Julian; Sebastian; Joaquin |
| Lun 26/10 | Integración: se reconstruyen y redespliegan los 8 servicios, se comprueban los 8 `health`, Postman en verde y el guion de demo con el tramo de la factura | Santiago; Julian |
| Mar 27/10 | C5: diagramas de secuencia de las integraciones que ya funcionan (compra, cobro, emisión, aviso y auditoría). D2: arranca la pantalla de puerta | Cada dueño dibuja el suyo; Valentina |
| Mié 28/10 | C1 y C3 cerradas, con su nota en `docs/notas/` y su evidencia. Arranca B4 | Santiago; Sebastian |
| Jue 29/10 | C5: diagrama de la factura SOAP y sección de transacciones. C4: arranca la puesta al día de `openapi.yaml`. Revisor: primera pasada contra lo que pide el 13/11 | Joaquin; Santiago |
| **Vie 30/10** | Clase: se muestra el avance. Se aprueba el plan de C2. D1: cuenta de desarrollador y credenciales de prueba de Mercado Pago | Los cinco; Julian; Valentina |
| Sáb 31/10 | C5: primer borrador completo, para que lo lean los cinco | Joaquin |

**Desde noviembre, por semana**

| Semana | Qué | Hito del viernes |
|---|---|---|
| Dom 01/11 al jue 05/11 | Lun 02 y mar 03: los cinco leen el borrador de C5 y cada uno corrige su parte; se cierra la pantalla de puerta. **Mié 04/11: se congela el paquete de diseño:** documento técnico con los diagramas de secuencia, WSDL, `openapi.yaml` y diagrama de integración actualizado. **Jue 05/11: se sube**, y es la fecha de corte de B4. C2 y D1 avanzan sin bloquear | **06/11** Entrega del diseño |
| Sáb 07/11 al jue 12/11 | Sáb 07 y dom 08: última oportunidad de cerrar algo que entre en la entrega. **Lun 09/11: se congela.** Mar 10/11: armado desde cero, Revisor contra la consigna, demo completa y arreglos. Mié 11/11: documento definitivo en PDF, tag `entrega-obligatoria-2` y ensayo de la defensa con preguntas cruzadas (cada uno explica una parte que no hizo). **Jue 12/11: se sube**, y el resto del día es colchón | **13/11** 2.ª entrega parcial, con defensa oral |
| Sáb 14/11 al jue 19/11 | Se cierra C2. D1: cobro en el ambiente de prueba de Mercado Pago, y webhook. D2: pantalla de reventa. Arreglos que haya pedido la cátedra el 13/11. **Mié 18/11: se congela lo que se va a mostrar. Jue 19/11: se sube** lo que pida la cátedra | **20/11** 3.er checkpoint |
| Sáb 21/11 al jue 26/11 | Panel del organizador y lo que haya marcado el checkpoint. D3: documentación final. **Mar 24/11: se congela.** Mié 25/11: armado desde cero, Revisor contra el checklist de la sección 6 de la consigna, demo de punta a punta y documentación final en PDF. **Jue 26/11: ensayo general y se sube** | **27/11** Prueba del TP Obligatorio |
| Desde el 28/11 | Lo que pida la cátedra después de la prueba, la preparación individual de la defensa y la reflexión del equipo que pide la consigna: qué decisión de arquitectura cambiarían y por qué | 04/12 final adelantado u 11/12 final regular |

**Líneas de corte.** Qué se recorta si algo se atrasa, decidido de antemano para no discutirlo sobre la fecha:

- **C1, el viernes 23/10.** Si ese día el WSDL no se descarga y una factura de prueba no responde, C1 se reduce al
  mínimo que pide la consigna (servicio SOAP, WSDL, cliente y una factura por compra) y la cola de reintento pasa a
  la Ola 5. Julian se suma a C1 y C2 espera.
- **B4, el jueves 05/11.** Si no está cerrada y con Postman en verde, no entra en la entrega del 13/11 y pasa a la
  Ola 5. No se refactorizan Usuarios ni Eventos con la entrega encima.
- **C2 y D1, el domingo 08/11.** Entran en la entrega del 13/11 solo si ese día están cerradas y revisadas. La
  consigna no las pide para esa fecha.
- **D1, el jueves 19/11.** Si el cobro contra el ambiente de prueba de Mercado Pago no funciona de punta a punta,
  queda como "diseñado, pendiente" y el requisito REST se cubre con la API propia.
- **Panel del organizador, el martes 24/11.** Es lo último. Si no llega, sigue con datos locales y el documento lo
  dice.

### Reparto sugerido

Se mantienen los dueños que ya figuraban. Las tareas que no tenían dueño (C1 a C5 y las nuevas D1 a D3) se repartieron
para que cada integrante tenga al menos un componente del backend propio que defender.

| Persona | Hecho (Olas 0 a 2) | Ola 3 (14/10 al 05/11) | Ola 5 (13/11 al 26/11) | Parte propia que defiende |
|---|---|---|---|---|
| Sebastian Deya | A1 ventas-core; T1 errores y CORS | C3 transacciones; B4 patrones-y-tests | Lo que Reventa y Mercado Pago necesiten de Ventas | Ventas: el stateful, la transacción y la emisión |
| Julian Zuchowicki | A2 mensajería; B1 integración; T2 token de servicio | Cola de reintento de C1 y revisión de C1; guion de demo; plan y arranque de C2 | C2 reventa | Mensajería y Notificaciones; el cobro de Ventas a Pagos; Reventa |
| Valentina Frisoli | A3 pagos; B2 frontend (sesión, catálogo y compra) | D2: pantalla de puerta. D1: credenciales y adaptador de Mercado Pago | D1: webhook. D2: reventa y panel | Pagos; el frontend |
| Joaquin Molina | A4 docs-entrega3 (con Santiago) | B3 validación-qr; C5 doc-técnico | D3 doc-final | Validación; el documento técnico |
| Santiago Loto | Ola 0; T0 y T3 a T6; coordinación y revisión | C1 facturación-soap; C4 openapi; Revisor | Revisor; armado desde cero y tag de cada entrega | Facturación y el servicio SOAP |

- **Ola 4 (06/11 al 12/11):** nadie tiene tarea nueva. Los cinco cierran, revisan y ensayan.
- **Revisor.** Lo sigue llevando Santiago, salvo para C1, que es suya: la revisa Julian.
- **Usuarios y Eventos** se construyeron antes de este plan y acá no tienen dueño. Hay que asignarles uno para la
  defensa. Propuesta: quien los escribió. El commit de Usuarios es de Julian; el de Eventos figura a nombre de
  `Improvement`, la identidad que hay que atribuir (riesgo 1).
- **Joaquin tiene dos tareas en la Ola 3 a propósito:** todavía no tiene commits a su nombre y B3 es la primera
  tarea de código que le toca. En C5 integra: cada diagrama de secuencia lo dibuja y lo revisa quien defiende esa
  integración.
- El prototipo en Figma, que figuraba a cargo de Valentina, no se hizo y sigue sin fecha: depende de la pregunta 9.

---

## 4. Ola 0 — humanos (09/10)

Estado al 13/10. Lo que sigue sin hacer tiene fecha nueva: **miércoles 14/10**.

- [ ] Cada integrante: `git config user.name "Nombre Apellido"` y `user.email` (el de su cuenta de GitHub).
      **Pendiente.** En el historial siguen `vfrisoli`, `santttii` e `Improvement`, y no hay commits a nombre de Joaquin.
- [ ] Agregar `.mailmap` para atribuir los commits viejos de `Improvement`, `vfrisoli` y `santttii` a su nombre
      completo. **Pendiente:** el archivo no existe.
- [x] Copiar `CLAUDE.md` a la raíz y este plan a `docs/PLAN_AGENTES.md`; commitear.
- [x] `cd backend && cp .env.example .env && ./dev.sh` → confirmar que arrancan los 8 servicios y `curl localhost:8080/api/eventos/health`.
      Hecho en la máquina donde se probó todo el 13/10. **Falta que cada integrante lo repita en la suya**, porque
      desde la Ola 3 cada uno corre y commitea su tarea.
- [x] **Decidir el proceso del checkpoint.** Quedó `PAGO_CONFIRMADO` (contrato en
      `docs/superpowers/specs/mensajeria-pago-confirmado.md`), con los dos canales ya implementados: la cola de
      emisión y el tópico de aviso y auditoría.
- [x] **Decidir la base para la demo:** H2 en memoria, en modo PostgreSQL. Supabase sigue sin conectar y las
      migraciones nunca corrieron contra un PostgreSQL real.
- [x] Decisión que condicionaba A1: cómo se ata el bean `@Stateful` al usuario si la sesión es JWT y hay 8 WAR.
      Se resolvió con un índice por id de venta, validado contra el usuario del token (`docs/notas/ventas-core.md`,
      decisión 1). Falta el visto bueno formal del grupo: la sección 11 de `docs/planes/00-contratos-compartidos.md`
      se sigue titulando "Decisiones pendientes de aprobación".
- [ ] Actualizar Notion (Eventos, Usuarios y "Configurar proyecto base" → Listo, y lo hecho en las Olas 1 y 2).
      El 13/10 no se revisó.

---

## 5. Briefs de la Ola 1 (hechos)

Las cuatro tareas están cerradas. Los briefs quedan como se escribieron el 09/10, porque las notas los citan. Donde
lo construido se apartó del brief, vale la nota de `docs/notas/`. El encabezado que sigue vale también para los
briefs nuevos de la sección 7.

> Todos empiezan con: *"Leé `CLAUDE.md`, `TODO.md` y `docs/PLAN_AGENTES.md`. Antes de escribir código, mostrame tu
> plan (archivos y decisiones) y esperá mi OK."* y terminan con: *"Cuando termines, corré los tests y el curl, y
> reportá el resultado real. Escribí `docs/notas/<tarea>.md` (máx. 1 página) explicando el porqué de cada decisión
> para que pueda defenderla oralmente."*

### A1 · `ventas-core` — Sebastian · rama `feat/ventas-core`
> **Hecho.** Cerrada el 13/10/2026. Nota para la defensa: `docs/notas/ventas-core.md`. Cambió respecto del brief:
> la API cuelga de `/api/v1` y la compra son dos llamadas (reservar y pagar); la reserva la vence un barrido por
> minuto y no el `@PreDestroy`; las entradas nacen con el pago y el MDB las emite; y el cobro va detrás de la
> interfaz `Cobros`.

- **Toca:** solo `backend/servicios/ventas/`. **No toca** `common` ni otros servicios.
- **Objetivo:** `ServicioDeVentas` como componente **stateful**: `IVentas` + `@Stateful` que mantiene
  selección → titulares → pago a lo largo de varias llamadas; `@PostConstruct`/`@PreDestroy`, `@StatefulTimeout`
  igual a `MINUTOS_RESERVA`, `@Remove` al confirmar/cancelar (esto es la "evidencia de ciclo de vida" que pide la cátedra).
- Entidades `Venta`, `VentaItem`, `Entrada` (estado `PENDIENTE_EMISION` → `VALIDA`) + migración Flyway V1 en el
  esquema de ventas. Montos `long`. Validar `MAX_ENTRADAS_POR_ORDEN`, DNI válido y único por venta, subtotal + tasa.
- API `/api/ventas`: iniciar compra, agregar entradas, cargar titulares, confirmar, cancelar, `GET /{id}` (solo dueño).
- **Mensajería:** programar contra `PublicadorPagoConfirmado.publicar(PagoConfirmadoMensaje)` (lo crea A2 en
  `common`; hasta que esté mergeado, usar una interfaz stub local). Al confirmar el pago, publicar **dentro de la
  misma transacción** que guarda la venta. Agregar el **MDB de emisión** (`cola.emision-entradas`, consumidor único,
  idempotente): crea las entradas y pasa la venta a emitida.
- **Aceptación:** test del bean (Mockito) para las reglas; ciclo de vida demostrado (log de `@PreDestroy` al confirmar);
  el flujo `iniciar → confirmar` con pago fake deja la venta guardada y un mensaje en la cola.
- **Decisión a justificar en `docs/notas/`:** cómo se ata el bean stateful al usuario (ADR).

### A2 · `mensajeria` — Julian · rama `feat/mensajeria`
> **Hecho.** Cerrada y probada en el servidor el 13/10/2026. Nota para la defensa: `docs/notas/mensajeria.md`. Los
> pasos transversales que la acompañaron (errores en Problem Details, token de rol `servicio`, fechas de los eventos
> de prueba) están en `docs/notas/pasos-transversales.md`. El aviso sigue siendo una línea en el log, no un email.

- **Toca:** `backend/common/` (solo paquete nuevo `com.palco.common.mensajeria`) y `backend/servicios/notificaciones/`.
- **Objetivo:** `record PagoConfirmadoMensaje` según el contrato del `.md` (serializado con JSON-B),
  `PublicadorPagoConfirmado` (inyecta `JMSContext` con el connection factory transaccional `java:/JmsXA`; publica el
  **mismo** body en la cola y en el tópico, con la propiedad `tipoEvento=PAGO_CONFIRMADO`).
- En `notificaciones`: MDB suscriptor **no durable** del tópico (email → por ahora log; Mailtrap después) y MDB de
  **auditoría durable** (`subscriptionDurability=Durable`, `clientId`, `subscriptionName`) que guarda el evento en una
  tabla (migración Flyway). Ambos con `messageSelector = "tipoEvento = 'PAGO_CONFIRMADO'"`.
- DLQ / reintentos para mensajes inválidos (config en `configurar.cli`).
- **Aceptación:** test unitario del (de)serializador; un `POST` de prueba (endpoint interno solo para desarrollo, rol
  `servicio`) publica un mensaje y se ve en el log: el MDB de notificaciones lo recibe y la tabla de auditoría gana una fila.
  Con el MDB de auditoría detenido y vuelto a arrancar, recibe lo pendiente.
- **Es lo primero que se mergea:** todo lo demás depende de `common/mensajeria`.

### A3 · `pagos` — Valentina · rama `feat/pagos`
> **Hecho, con la pasarela simulada.** Cerrada el 13/10/2026. Nota para la defensa: `docs/notas/pagos.md`. Cambió
> respecto del brief: `IPagos` la implementa `ServicioDePagos` y las pasarelas implementan `PasarelaDePagos`; la API
> cuelga de `/api/v1/pagos` y pide el rol `servicio`. **No se hicieron** `MercadoPagoPagos`, el webhook ni el
> reembolso: pasan a D1 (sección 7). El prototipo en Figma tampoco se hizo (pregunta 9).

- **Toca:** solo `backend/servicios/pagos/`.
- **Objetivo:** `IPagos` (`procesarPago`, `confirmarPago`, `reembolsar`) con **dos implementaciones elegibles por
  configuración** (MicroProfile Config `palco.pagos.proveedor=fake|mercadopago`): `PagosFake` (default, aprueba salvo
  tarjetas de prueba rechazadas) y `MercadoPagoPagos` (cliente JAX-RS contra la API sandbox, credenciales por variable
  de entorno, **nunca** en el repo). Esto es el **Adapter/Strategy** que se defiende como patrón.
- Webhook `POST /api/pagos/webhook` **idempotente** (mismo `paymentId` dos veces no procesa dos veces) que responde
  200 rápido; rechazo/pendiente/vencido contemplados. Cliente JAX-RS con timeout corto.
- **Aceptación:** tests del fake y de la idempotencia del webhook; `curl` de un pago aprobado y uno rechazado.
- **Además (humano):** armar el **prototipo en Figma** con las pantallas del frontend (catálogo, evento, checkout,
  confirmación, mis entradas, reventa, panel organizador, puerta). Se puede partir de capturas del front actual.

### A4 · `docs-entrega3` — Joaquin (con Santiago) · rama `docs/entrega3`
> **Hecho.** El documento, el diagrama y la evidencia están en `docs/entrega3/`, escritos el 13/10/2026. Nota:
> `docs/notas/docs-entrega3.md`, que lista lo que falta y solo puede hacer una persona antes de subirlo. Se entrega
> el 23/10 (fecha sin confirmar). Cambió respecto del brief: es solo el núcleo que pide la consigna, escrito en
> Markdown y pasado a Word y PDF; la documentación funcional y el manual de usuario no se hicieron (pregunta 9).

- **Toca:** solo `docs/entrega3/`. No toca código.
- **Objetivo:** (1) diagrama de integración SOA en draw.io (partir de `docs/entrega1/Diagrama_Capas_EntradasEventos.drawio`):
  componentes internos, sistemas externos, canal y etiqueta SÍNCRONO/ASÍNCRONO por flecha; (2) documento de
  **clasificación justificada** de cada integración (consolidar `Taller_Arquitectura_Integracion.md`,
  `Taller_Mensajeria.md`, `Actividad_Integradora_Clase9.md`, los tres en `docs/clases/`); (3) **documentación funcional** (flujos de comprador,
  organizador, staff, reventa — ya redactados en la sección 6 de `docs/entrega1/Entrega_Parcial_1_Arquitectura.docx`);
  (4) **manual de usuario** con pasos y capturas del front.
- **Regla:** el documento debe describir **lo que el sistema hace hoy**, no lo planeado; si algo no está
  implementado, se marca como "diseñado, pendiente".
- Formato: Word (como la Entrega Parcial N.º 1). Pedir primero un índice y aprobarlo.

---

## 6. Briefs de la Ola 2 (B1 y B2 hechas; B3 y B4 pasan a la Ola 3)

| Agente | Objetivo | Depende de | Estado al 13/10 |
|---|---|---|---|
| **B1 integracion-e2e** (Julian) | Conectar Ventas → Pagos (REST entre servicios con token rol `servicio`) → publicar → MDBs. Script `scripts/demo-entrega3.sh` que corre el flujo completo con `curl`. Arreglar lo que aparezca. **Es el entregable central del checkpoint del 23/10.** | A1, A2, A3 | **Hecho** el 13/10. Nota: `docs/notas/integracion.md`. El guion está en `scripts/` y se explica en `scripts/LEEME.md` |
| **B2 frontend-checkout** (Valentina) | `frontend/src/lib/api.ts` + catálogo, login y checkout contra la API real (Fase 10 de `TODO.md`, solo el subconjunto necesario); estado "emitiendo entradas…" hasta que el MDB termine. | B1 parcial | **Hecho** el 13/10. Notas: `docs/notas/B2-frontend-sesion-catalogo.md` y `docs/notas/B2-frontend-checkout.md`. Reventa, puerta y panel siguen con datos locales: es la tarea D2 |
| **B3 validacion-qr** (Joaquin) | `ServicioDeValidación` **stateless**: QR firmado, `POST /api/validacion/escaneos` (rol `staff`), uso único atómico. | A1 (entidad `Entrada`) | **Pendiente.** Pasa a la Ola 3: del 17/10 al 25/10. Brief en la sección 7 |
| **B4 patrones-y-tests** (Sebastian) | Introducir **Repository/DAO** en Eventos, Usuarios y Ventas (hoy `EntityManager` directo), y documentar los 3 patrones: Repository, Adapter/Strategy (`IPagos`), Facade (recurso→servicio). Tests Mockito de los Session Beans. | A1–A3 mergeados | **Pendiente, y más chica:** Ventas y Pagos ya nacieron con Repository y con tests. Pasa a la Ola 3: del 28/10 al 05/11. Brief en la sección 7 |
| **Revisor** (Santiago) | Agente de solo lectura, **distinto** a los que escribieron el código: contrasta rama vs. consigna (la entrega que toque y el checklist) y devuelve lista de brechas con evidencia. Corre al final de cada ola. | cada ola | Hubo revisión el 13/10: las notas de Ventas, de la integración y del documento dicen qué se corrigió. Vuelve a correr el 29/10, el 10/11 y el 25/11 |

---

## 7. Olas 3 a 5 — briefs de lo que falta, hacia el 06/11, el 13/11, el 20/11 y el 27/11

Van en el orden de prioridad de la sección 3. Cada brief dice quién lo lleva, sus fechas y qué cambió por lo que ya
está construido. Todos usan el encabezado de la sección 5 y dejan su nota en `docs/notas/<tarea>.md`, con el mismo
formato que las de la Ola 1.

**Lo que ya existe y vale para todas las tareas** (el detalle está en `docs/planes/00-contratos-compartidos.md`):

- **Rutas.** Ventas cuelga de `/api/v1` y Pagos de `/api/v1/pagos`. Los servicios nuevos nacen versionados: Validación
  en `/api/v1/validacion` y Reventa en `/api/v1/reventas`. Usuarios, Eventos, Notificaciones y Facturación siguen en
  `/api/<servicio>`.
- **Llamadas entre servicios.** Van con el token de rol `servicio`, que entrega `TokenDeServicio` (en `common`). No
  se inventa otro mecanismo.
- **Errores.** Se lanzan excepciones de `ErroresDeNegocio` y salen como Problem Details (`application/problem+json`).
  Ya existen las de 402, 403, 422 y 504.
- **Pedidos repetidos.** Todo `POST` que crea o cobra lleva `Idempotency-Key`, como en Ventas y en Pagos.
- **Conexiones.** Los 8 servicios comparten 10 conexiones a la base: no se retiene una mientras se espera a otro
  servicio (`docs/notas/ventas-core.md`, decisión 10).
- **Migraciones.** Sin `check` de lista de valores, y una migración ya aplicada no se edita: se agrega otra.

### C1 · `facturacion-soap` — Santiago (la revisa Julian) · del 14/10 al 28/10
Es lo primero: la consigna pide para el 13/11 un servicio SOAP con su WSDL, y hoy no existe.

- **Toca:** `backend/servicios/facturacion/` y, si el grupo lo aprueba (decisión 1), un módulo nuevo para el sistema
  legado. El cambio en `backend/servidor` va en un paso aparte (sección 2). La cola nueva en `configurar.cli` se la
  pide a Julian, y lo que haga falta en Ventas, a Sebastian.
- **Objetivo mínimo** (lo que pide la consigna): un sistema legado simulado, que hace de ARCA, expuesto por SOAP con su
  WSDL publicado por WildFly; `ServicioDeFacturación`, con su interfaz `IFacturacion`, como cliente; y una factura por
  cada compra confirmada, con lo que `facturaEstado` deja de valer `NO_APLICA`.
- **Después del mínimo:** espera máxima de 5 segundos y, ante un timeout o un Fault, mensaje a
  `cola.reintento-facturacion` y la venta queda confirmada con la factura pendiente. Es el diseño de
  `docs/clases/clase-09-actividad-integradora/Actividad_Integradora_Clase9.md`, y suma un proceso asincrónico más,
  por cola.
- **Qué cambió por lo ya construido:**
  - La forma es la de Pagos: `IFacturacion` la implementa el servicio, y la salida al sistema externo va detrás de
    una interfaz propia, con una Factory que elige la implementación (`docs/notas/pagos.md`, decisiones 1 y 2).
  - `facturaEstado` ya existe en la venta (`EstadoFactura`), y los reintentos con DLQ ya valen para cualquier cola.
  - **El servidor se arma solo con las capas que usa** (`jaxrs-server`, `ejb`, `embedded-activemq` y
    `microprofile-jwt`, en `backend/servidor/pom.xml`) y ninguna es la de servicios web. Lo más probable es que haya
    que sumarla, y eso obliga a detener WildFly y armarlo de nuevo. Es lo primero que hay que comprobar, antes de
    escribir el servicio.
- **Decisiones que el plan propone y aprueba el grupo:**
  1. Dónde vive el sistema legado simulado: en un WAR aparte, que sería el noveno, o dentro de Facturación.
  2. Cómo se dispara la factura: (a) Ventas se la pide a Facturación dentro de la compra, que es lo que dice el
     documento del checkpoint (integración I3 y llamada S3), o (b) Facturación se suscribe al tópico
     `topico.compra-confirmada`, que no toca Ventas pero cambia lo ya documentado.
  3. Quién cambia `facturaEstado`, que es un dato de Ventas.
- **Aceptación:** el WSDL se descarga con `curl`; una compra de punta a punta termina con su factura; con el sistema
  legado caído o lento la compra se confirma igual y la factura se reintenta; tests; un tramo nuevo en el guion de
  demo y un request en Postman.
- **Hitos:** 19/10, el WSDL y una factura de prueba. 21/10, una factura por compra. 23/10, punto de control (ver
  "Líneas de corte"). 25/10, timeout y cola. 28/10, cierre y nota.

### C5 · `doc-tecnico` — Joaquin integra, cada dueño escribe su parte · del 19/10 al 11/11
Es lo segundo que hoy no existe y la consigna pide para el 13/11.

- **Toca:** solo `docs/entrega-obligatoria-2/`, carpeta nueva. No toca código ni `docs/entrega3/`, que queda como se
  entregó.
- **Objetivo:** el "documento técnico actualizado", de 8 a 12 páginas, con un diagrama de secuencia por integración.
- **Contenido mínimo:** arquitectura y componentes; patrones aplicados y por qué (los que estén en el código ese día);
  clasificación de las integraciones, con SOAP ya implementado; los diagramas de secuencia; la transacción del flujo
  crítico y qué pasa en cada falla; qué es real y qué es simulado; y la declaración del uso de IA, que en esta
  entrega es obligatoria.
- **Diagramas y quién los defiende:** la compra y su transacción, Sebastian; el cobro de Ventas a Pagos, Julian; la
  emisión de entradas por la cola, Sebastian; el aviso y la auditoría por el tópico, Julian; la factura SOAP y su
  reintento, Santiago; Mercado Pago, que queda como diseño, Valentina.
- **Qué cambió por lo ya construido:** no se parte de cero. La base es
  `docs/entrega3/Entrega_Parcial_2_Integracion.md`, que ya tiene unas 6.100 palabras: hay que resumir, no sumar. Las
  decisiones y las fallas de cada flujo están en `docs/notas/`. Hay un diagrama de secuencia de la clase 10 en
  `actividad_integradora_clase10/diagrama_secuencia.png`.
- **Regla**, la misma de A4: el documento describe lo que el sistema hace el día en que se congela. Lo que no esté
  implementado figura como "diseñado, pendiente".
- **Hitos:** 20/10, índice aprobado. 27/10 y 29/10, diagramas. 31/10, borrador completo. 04/11, paquete de diseño
  congelado (se sube el 05/11). 11/11, documento definitivo en PDF (se sube el 12/11).
- **A resolver por el grupo:** la consigna pide un documento "actualizado". En el repo, lo anterior es
  `docs/entrega1/Entrega_Parcial_1_Arquitectura.docx`. Si el 18/09 se presentó otro documento, hay que sumarlo a
  `docs/` (pregunta 14).

### C3 · `transacciones` — Sebastian · del 14/10 al 28/10
La defensa del 13/11 pide explicar qué pasa con la transacción ante una falla a mitad del flujo.

- **Toca:** solo `backend/servicios/ventas/`.
- **Qué cambió por lo ya construido:** la transacción declarativa ya existe. `confirmarCompra` es `REQUIRED`, el
  publicador es `MANDATORY` y el MDB de emisión consume dentro de su transacción. Ya no hay que anotar el flujo: hay
  que poder mostrarlo y cerrar lo que quedó abierto.
- **Objetivo:**
  1. Una prueba de la falla a mitad del flujo que quede en el repo y se pueda repetir en la demo. La del 13/10 se
     hizo con un WAR temporal. El resultado ya se conoce: el pago responde 500, la venta sigue `EN_CHECKOUT`, no hay
     entradas y no sale ningún mensaje.
  2. Conciliación: una venta que quedó con un intento de cobro sin resultado hoy no se puede pagar ni cancelar, y
     tampoco vence. Hay que preguntarle a Pagos por esa clave y cerrar la venta: confirmarla o liberar su cupo.
  3. El cupo que queda tomado cuando Eventos no responde al devolverlo.
- **No entra:** un commit en dos fases. La caída entre el commit de la base y el del broker se documenta como no
  probada, salvo que el plan encuentre cómo provocarla.
- **Aceptación:** un paso nuevo del guion de demo muestra la falla y el estado en que queda todo; tests; y la tabla
  "qué pasa si falla cada paso", actualizada, para C5.

### B3 · `validacion-qr` — Joaquin · del 17/10 al 25/10
Es el séptimo componente y la primera tarea de código de Joaquin.

- **Toca:** `backend/servicios/validacion/`.
- **Objetivo**, el mismo de la sección 6: `ServicioDeValidación` **stateless**, con `POST /api/v1/validacion/escaneos`
  para el rol `staff`, que responde si la entrada es válida, ya fue usada o no existe, devuelve el nombre y el DNI
  del titular y la marca como usada una sola vez aunque la escaneen dos puertas al mismo tiempo.
- **Qué cambió por lo ya construido:**
  - La ruta pasa a `/api/v1/validacion` (hoy el esqueleto está en `/api/validacion`).
  - La entrada y su código QR ya existen y son de Ventas: el MDB de emisión la deja `VALIDA`. Validación no puede
    leer las tablas de Ventas, así que **el plan tiene que proponer de dónde saca la entrada**: (a) una operación
    nueva en Ventas que la marca como usada con un `UPDATE` condicional, llamada con el token de servicio, o (b)
    una tabla propia que se llena desde el tópico `topico.compra-confirmada`. La (a) es la que anticipa el
    documento del checkpoint (llamada S3) y se acuerda con Sebastian.
  - Ya hay una cuenta de staff (`puerta@palco.test`) y un request en Postman, el 7, fuera de la corrida.
- **Aceptación:** el mismo código dos veces responde "válida" y después "usada"; dos escaneos simultáneos dejan pasar
  uno solo; con un rol que no es `staff` responde 403; tests.

### B4 · `patrones-y-tests` — Sebastian · del 28/10 al 05/11
- **Toca:** `backend/servicios/eventos/`, `backend/servicios/usuarios/` y `backend/servicios/notificaciones/`. Avisa
  a Julian antes de tocar los dos últimos.
- **Qué cambió por lo ya construido:** Ventas y Pagos ya nacieron con Repository y con tests, y los patrones ya están
  en el código (los justifica C5). Queda lo que la consigna llama "capas explícitas en cada componente": en Eventos,
  Usuarios y Notificaciones el negocio usa `EntityManager` directo. Además, Eventos no tiene ningún test.
- **Orden:** primero los tests de Eventos y después el cambio, para tener con qué comprobar que nada se rompió.
- **Aceptación:** `mvn test` y la colección de Postman en verde, y los 8 `health`.
- **Línea de corte:** si el 05/11 no está cerrada, no entra en la entrega del 13/11.

### C4 · `openapi` — Santiago · del 29/10 al 04/11
- **Toca:** solo `docs/api/`.
- **Qué cambió por lo ya construido:** los errores en Problem Details, `Idempotency-Key` y `/api/v1` ya existen. Lo
  que quedó atrás es el contrato: `openapi.yaml` no tiene lo que se implementó después (la lista está en la sección
  8.3 de contratos).
- **Objetivo:** `openapi.yaml` al día con lo implementado, escrito a mano, y con Facturación y Validación cuando
  cierren. Entra en el paquete de diseño del 06/11.
- **No entra, salvo que sobre tiempo:** pasar Usuarios y Eventos a `/api/v1` y el ETag. Cambia rutas que usan el
  frontend, Postman, los guiones y la evidencia, y la consigna no lo pide. Queda documentado como diferencia.
- **Resiliencia** (desafío opcional de la consigna): no se escribe código nuevo. Lo que ya hay se muestra en D3: si
  Pagos no responde, la venta no se confirma y el comprador recibe un 504; si Notificaciones está caído, la compra
  sigue.

### C2 · `reventa` — Julian · plan del 20/10 al 30/10, código desde el 31/10, cierre el 19/11
La consigna no la pide para el 13/11. Es el octavo componente y lo que distingue al producto, así que tiene que
estar para el 27/11.

- **Toca:** `backend/servicios/reventa/`.
- **Objetivo:** publicar una entrada, retirarla, listar las publicaciones y comprar una, con las reglas de
  `ReglasDeNegocio`: tope de +10 %, mínimo de 50 %, cierre 3 horas antes del evento y nadie compra la suya.
- **Qué cambió por lo ya construido:**
  - La ruta es `/api/v1/reventas`, que ya figura en `openapi.yaml` (hoy el esqueleto está en `/api/reventa`).
  - Cobra llamando a Pagos como lo hace Ventas: mirar `ClienteDePagos` y `docs/notas/integracion.md`.
  - Las entradas son de Ventas. El plan define qué le pide a Sebastian: como mínimo, listar las entradas de un
    usuario y pasar una entrada a otro titular.
  - El QR de la entrada vendida se invalida por Validación (B3), y el aviso al vendedor sale por Notificaciones.
  - Los eventos de prueba van del 26/12/2026 al 29/01/2027: la reventa está abierta en todas las fechas de entrega.
- **Línea de corte:** entra en la entrega del 13/11 solo si el 08/11 está cerrada y revisada.

### D1 · `mercadopago-webhook` — Valentina · credenciales el 30/10, adaptador hasta el 08/11, webhook hasta el 19/11
- **Toca:** `backend/servicios/pagos/`.
- **Qué cambió por lo ya construido:** está diseñado en `docs/planes/A3-pagos.md` (decisiones 5 y 6). La Factory ya
  elige la pasarela por configuración (`palco.pagos.proveedor`). Falta `MercadoPagoPagos`, que es el Adapter de
  `PasarelaDePagos`. La ruta `POST /api/v1/pagos/webhook` está reservada, y los estados `PENDIENTE`, `VENCIDO` y
  `REEMBOLSADO` ya están en el enumerado. Un pago pendiente necesita un estado nuevo en la venta: se acuerda con
  Sebastian.
- **Lo que solo puede hacer una persona:** la cuenta de desarrollador de Mercado Pago, las credenciales de prueba
  (nunca en el repo) y una URL pública para recibir el webhook.
- **Riesgo:** el 09/10 no se pudo verificar la firma del webhook contra la documentación oficial.
- **Línea de corte:** el 19/11. Si no funciona de punta a punta, queda como "diseñado, pendiente".

### D2 · `frontend-resto` — Valentina · puerta del 27/10 al 03/11, reventa del 14/11 al 19/11, panel del 21/11 al 24/11
- **Toca:** `frontend/`.
- **Objetivo:** conectar al backend las tres pantallas que siguen con datos locales, cada una cuando su servicio
  esté cerrado: la puerta (B3), la reventa y "Mis entradas" (C2), y el panel del organizador. El panel va último
  porque su backend no existe ni tiene dueño (Fase 9 de `TODO.md`).
- **Qué cambió por lo ya construido:** `api.ts`, la sesión y el manejo de errores ya están. Cada pantalla suma su
  archivo en `frontend/src/servicios/`, copiando la forma de `ventas.ts`. Lo que el frontend le pidió al backend
  está en `docs/notas/B2-frontend-checkout.md`.

### D3 · `doc-final` — Joaquin integra, con los cinco · del 14/11 al 25/11
- **Objetivo:** la documentación final de 10 a 15 páginas que pide la consigna: arquitectura completa, decisiones de
  diseño, patrones, estrategia de integración, aprendizajes del equipo y trabajo futuro. Sale de ampliar C5.
- **Puntos extra que cuestan poco:** dos ADR, que salen de decisiones ya escritas con sus alternativas (P1 y P3 de
  contratos), y la evidencia de resiliencia.

**Sin fecha, solo si sobra tiempo:** que el aviso de compra salga por email (Julian, en Notificaciones). El grupo
todavía no eligió proveedor.

## 8. Qué NO delegar a un agente

Las decisiones de arquitectura que van a preguntarte en la defensa (stateful vs. sesión, cola vs. tópico, qué
transacción abarca qué), el Figma final, el ensayo de la demo y la lectura del código de tu propia parte.
Los agentes aceleran la escritura; la defensa oral individual es de ustedes.

Tampoco se delegan: las preguntas a la cátedra; los commits, que cada uno hace con su identidad; el tag y la subida
de cada entrega; y la lectura completa de cada documento antes de entregarlo. La declaración del uso de IA la
aprueban los cinco y tiene que contar lo que se hizo.
