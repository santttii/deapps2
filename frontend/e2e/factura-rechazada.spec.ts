import { expect, ingresar, test, type Api } from './apoyo';
import type { Page } from '@playwright/test';

// CA-06.3 en la pantalla (DEF-33): cuando la factura de una compra no está, Mis entradas dice por qué.
// Facturación responde 409 /problemas/comprobante-rechazado si ARCA rechazó el comprobante y 404 mientras no
// hay factura: en camino unos segundos después del pago, o demorada si ARCA no respondió y el mensaje quedó en
// la DLQ. ARCA simulado nunca rechaza una compra real, y llevar el mensaje a la DLQ pide cambiar la configuración
// del servidor, que es compartido: la respuesta de Facturación se simula solo en este navegador con page.route.
// La cuenta, el evento y la compra son de verdad.

const FACTURAS = '**/api/v1/facturas/*';

/** Una cuenta nueva con una entrada de Campo ya emitida, y su sesión abierta en Mis entradas. */
async function conUnaCompra(page: Page, api: Api, prefijo: string) {
  const evento = await api.crearEvento(prefijo);
  const campo = evento.sectores.find((s) => s.nombre === 'Campo')!;
  const cuenta = await api.cuentaNueva();
  const venta = await api.comprar(cuenta, evento, campo.id);
  await ingresar(page, cuenta.email);
  await page.goto('/mis-entradas');
  await expect(page.getByRole('heading', { name: evento.titulo })).toBeVisible();
  return venta;
}

/** Responde cada consulta de factura con ese problema y anota qué venta se pidió. */
async function facturacionResponde(page: Page, estado: number, tipo: string, titulo: string, detalle: string) {
  const pedidas: string[] = [];
  await page.route(FACTURAS, (ruta) => {
    const instancia = new URL(ruta.request().url()).pathname;
    pedidas.push(instancia);
    // Con npm run dev la API está en otro origen (localhost:8080) y la respuesta lleva el permiso de CORS que
    // daría el servidor; contra el compose es el mismo origen y el navegador no manda Origin.
    const origen = ruta.request().headers()['origin'];
    return ruta.fulfill({
      status: estado,
      contentType: 'application/problem+json',
      headers: origen ? { 'Access-Control-Allow-Origin': origen } : {},
      body: JSON.stringify({ type: `/problemas/${tipo}`, title: titulo, status: estado, detail: detalle, instance: instancia }),
    });
  });
  return pedidas;
}

test('factura rechazada: el 409 comprobante-rechazado muestra el aviso del rechazo, sin "Volver a consultar"', async ({
  page,
  api,
}) => {
  const pedidas = await facturacionResponde(
    page,
    409,
    'comprobante-rechazado',
    'Conflicto',
    'ARCA rechazó la factura de esta compra y la estamos revisando. Las entradas valen igual.',
  );
  const venta = await conUnaCompra(page, api, 'E2E factura rechazada');

  await page.getByRole('button', { name: 'Ver factura', exact: true }).click();
  const modal = page.getByRole('dialog', { name: 'Factura de la compra' });
  await expect(modal.getByRole('alert')).toHaveText(
    'ARCA rechazó la factura de esta compra y la estamos revisando: no tenés que hacer nada. Tus entradas valen igual.',
  );
  // Volver a consultar no la va a traer: el rechazo lo resuelve una persona.
  await expect(modal.getByRole('button', { name: 'Volver a consultar' })).toHaveCount(0);
  expect(pedidas, 'se pidió la factura de esa compra').toContain(`/api/v1/facturas/${venta.id}`);
});

test('factura demorada: el 404 recién comprada dice "en camino" y, de una compra de hace más de 5 minutos, que está demorada', async ({
  page,
  api,
}) => {
  const pedidas = await facturacionResponde(page, 404, 'no-encontrado', 'No encontrado', 'No hay una factura de esa venta.');
  // El reloj del navegador corre igual que siempre; después se lo adelanta para abrir la factura más tarde.
  await page.clock.install();
  const venta = await conUnaCompra(page, api, 'E2E factura demorada');

  await page.getByRole('button', { name: 'Ver factura', exact: true }).click();
  const modal = page.getByRole('dialog', { name: 'Factura de la compra' });
  const volverAConsultar = modal.getByRole('button', { name: 'Volver a consultar' });
  await expect(modal.getByRole('alert')).toHaveText(
    'La factura todavía se está generando con ARCA. Probá de nuevo en unos segundos.',
  );
  await expect(volverAConsultar).toBeVisible();

  // Diez minutos después la factura sigue sin estar: ya no es la espera normal, su mensaje quedó en la DLQ.
  await page.clock.setSystemTime(Date.now() + 10 * 60_000);
  await volverAConsultar.click();
  await expect(modal.getByRole('alert')).toHaveText(
    'La factura está demorada y la estamos revisando. Va a aparecer acá apenas ARCA la autorice; tus entradas valen igual.',
  );
  // Cuando se reprocese la DLQ, la factura aparece: se puede volver a consultar.
  await expect(volverAConsultar).toBeVisible();
  // Cuántas veces se pidió depende del modo (con npm run dev, StrictMode repite cada efecto): se mira qué se pidió.
  expect([...new Set(pedidas)], 'todas las consultas son de esa compra').toEqual([`/api/v1/facturas/${venta.id}`]);
});
