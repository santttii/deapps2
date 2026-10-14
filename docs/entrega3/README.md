# Entrega Parcial N.º 2 (23/10/2026, a confirmar) — carpeta de la entrega

Acá está el documento técnico de la entrega y todo lo que lo respalda. Describe el estado del servidor de desarrollo el 14/10/2026 entre las 17:56 y las 18:00, armado desde cero ese día desde el commit `580dd05`, el del tag `entrega-parcial-2`.

## Qué hay en la carpeta

| Archivo | Qué es |
|---|---|
| `Entrega_Parcial_2_Integracion.md` | El documento técnico, en Markdown. De acá salen el Word y el PDF que se suben al aula virtual |
| `Diagrama_Integracion_SOA.svg` | La figura 1 del documento. Se ve en cualquier navegador y en GitHub, y Word la inserta como imagen |
| `Diagrama_Integracion_SOA.drawio` | La fuente editable del diagrama, para app.diagrams.net. Tiene las mismas cajas y flechas que el SVG. Todavía nadie la abrió en esa herramienta |
| `evidencia/` | La evidencia del proceso asincrónico: comandos corridos contra el servidor, con su salida. `evidencia/LEEME.txt` explica cada archivo y dice cuáles traen solo un resumen |
| `evidencia/reproducir.sh` | El guion que repite el proceso completo, el control por rol y la reserva de cupo. Se corre desde la raíz del repo: `bash docs/entrega3/evidencia/reproducir.sh`. Su salida es `evidencia/reproduccion.txt` |
| `evidencia/tomar-evidencia.sh` y `evidencia/suscriptor-durable.sh` | Los guiones de la compra paso por paso y de la suscripción durable. Se corren igual que `reproducir.sh` |
| `evidencia/vencimiento-de-reserva.txt` | El resumen de la prueba del vencimiento de una compra sin pagar |

## Dónde responde el documento a cada punto de la consigna

| Pedido de la consigna | Sección del documento |
|---|---|
| Diagrama de arquitectura de integración (SOA): componentes internos, sistemas externos y canal | 2 (figura 1) |
| Clasificación justificada de cada integración externa: síncrona (SOAP o REST) o asincrónica (cola o tópico) | 3 |
| Al menos un proceso de negocio de punta a punta con mensajería asincrónica real y broker configurado | 4 |

Además, la sección 5 lista las diferencias con la Entrega Parcial N.º 1, los talleres de las clases 6, 8 y 9 y la actividad de la clase 10.

## Qué falta en esta carpeta

El detalle de cada punto está en `docs/notas/docs-entrega3.md`.

- `Diagrama_Integracion_SOA.drawio.png`: la exportación del diagrama desde app.diagrams.net.
- `evidencia/postman-runner.png`: la captura de la corrida de la colección de Postman.
- Confirmar con la cátedra la fecha de la entrega. El grupo cursa en la comisión de los viernes y la consigna, escrita para la del lunes, dice 19/10. El cronograma de los viernes no nombra este checkpoint: se toma el viernes 23/10/2026, cuatro días después como las otras fechas, y así figura en la carátula. También falta confirmar la hora límite: las 13:30 de la consigna son de la comisión del lunes.
- Lo que el documento marca como pendiente en un comentario junto a la carátula: la fecha de la entrega.
- Los anexos (documentación funcional, manual de usuario y prototipo): no se hicieron. La consigna no los pide para esta entrega y la consulta a la cátedra todavía no tiene respuesta.
