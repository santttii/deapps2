# Taller grupal — Arquitectura de integración del proyecto (Palco)
**Desarrollo de Aplicaciones II · Unidad IV · Clase 6 (07/09/2026)**
*Insumo directo para el TP Segunda Parte.*

---

## 1. Sistemas a integrar

**Internos (capa de negocio, ya definidos en la Entrega Parcial N.º 1):**

| Componente | Rol en la integración |
|---|---|
| ServicioDeUsuarios | Identidad y roles — consumido transversalmente por casi todos los flujos |
| ServicioDeEventos | Catálogo de eventos y cupos |
| ServicioDeVentas (stateful) | Orquesta el checkout; coordina Eventos, Pagos, Facturación y Notificaciones |
| ServicioDePagos | Integración con Mercado Pago |
| ServicioDeValidación (stateless) | Valida entradas en puerta; también interviene en la reventa |
| ServicioDeReventa | Publicación y venta de reventa oficial |
| ServicioDeNotificaciones | Notificaciones multicanal, disparadas por procesos de otros servicios |
| ServicioDeFacturación | Integración con AFIP/ARCA |

**Externos:**

| Sistema | Protocolo | Naturaleza |
|---|---|---|
| AFIP / ARCA (facturación electrónica) | SOAP | Legado |
| Mercado Pago | REST | Moderno, de terceros |
| Broker de mensajería (ActiveMQ / RabbitMQ) | Async (colas y tópicos) | Infraestructura propia |

En total: **8 componentes internos + 3 sistemas externos = 11 nodos** a integrar entre sí y con el resto de la plataforma.

---

## 2. Modelo arquitectónico elegido: Bus de integración (ESB, conceptual)

**Justificación (criterios aplicados):**

- **Cantidad de sistemas:** con 11 nodos, punto a punto implicaría hasta N(N−1)/2 = **55 conexiones posibles** — imposible de sostener con un equipo de 5 personas y un cronograma de cuatrimestre; cualquier cambio en un componente (ej. cambiar el proveedor de pagos) arriesgaría romper varias integraciones a la vez.
- **Heterogeneidad de protocolos:** los 3 sistemas externos hablan protocolos distintos (SOAP legado, REST moderno, mensajería async). Un bus resuelve esto con **mediación de protocolos** en un solo lugar, en vez de que cada componente de negocio implemente su propio cliente SOAP/REST/JMS por separado.
- **Evolución esperada:** el TP crece por entregas (Parcial N.º 2, Obligatoria N.º 2, Final) y ya se anticipan más integraciones (ej. seguridad declarativa, nuevos canales). Hub-and-spoke ya sería una mejora sobre punto a punto (N conexiones en vez de N(N−1)/2), pero un bus da además **transformación y ruteo** desacoplados del negocio, que es justamente lo que necesitamos para sumar integraciones sin tocar los servicios existentes.
- **Acoplamiento:** el broker de mensajería (ActiveMQ/RabbitMQ) que ya estaba en el diseño de negocio cumple funcionalmente el rol de canal común para los procesos asincrónicos (emisión de entrada, notificaciones); conceptualmente ya veníamos construyendo hacia un modelo de bus sin nombrarlo así.

**Nota de alcance:** no implementamos un producto de ESB dedicado (Mule, Camel, etc.) — el criterio se aplica a nivel conceptual, apoyado en el broker de mensajería ya elegido para los procesos asincrónicos y en clientes SOAP/REST desacoplados detrás de interfaces propias (`IFacturacion`, `IPagos`), lo que en la práctica logra el mismo desacoplamiento que persigue un ESB sin agregar una pieza de infraestructura nueva al stack.

---

## 3. Servicio reutilizable (principio SOA)

**`ServicioDeUsuarios`** es el candidato más claro: expone identidad, autenticación y rol, y es consumido por **más de un flujo**:

- Checkout (`ServicioDeVentas`) — identificar al comprador y a cada titular nominativo.
- Reventa (`ServicioDeReventa`) — verificar que quien publica sea el titular verificado de la entrada.
- Panel del organizador — autenticar y autorizar según rol (organizador).
- Validación en puerta (`ServicioDeValidación`) — contrastar identidad contra la entrada al ingresar.

Es el mismo patrón que el ejemplo de clase ("consultar saldo" consumido por homebanking, app, cajero y call center): un único contrato, múltiples canales/flujos consumidores, sin que cada uno reimplemente su propia lógica de identidad.

**Candidato secundario:** `ServicioDeNotificaciones`, reutilizado por confirmación de compra, aviso de venta en reventa, recordatorios y alertas de cancelación — mismo contrato, distintos disparadores.
