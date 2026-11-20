# Registro de decisiones de arquitectura (ADR)

Formato de Michael Nygard: estado, contexto, decisión, consecuencias y alternativas descartadas. Las decisiones de diseño sin formato ADR están en la sección 5 de `docs/documentacion/Palco_Documentacion_Completa.md`.

| ADR | Decisión | Patrón | Estado |
|---|---|---|---|
| [ADR-001](ADR-001-controlador-de-la-compra.md) | El Resource traduce HTTP y `ComprasEnCurso` coordina la compra | Controlador (GRASP) | Aceptada |
| [ADR-002](ADR-002-state-de-la-venta.md) | El ciclo de vida de la venta en una clase por estado (`EstadoDeVenta`) | State (GoF) | Aceptada |
| [ADR-003](ADR-003-adapter-de-arca.md) | `ArcaSoap` adapta el SOAP de ARCA a la interfaz `Arca` | Adapter + Factory (GoF) | Aceptada |
| [ADR-004](ADR-004-pubsub-pago-confirmado.md) | `PAGO_CONFIRMADO` por cola y tópico | Observer / pub-sub | Aceptada |
| [ADR-005](ADR-005-broker-embebido.md) | El broker es el Artemis embebido en WildFly, con el journal en un volumen del compose | Infraestructura | Aceptada |
