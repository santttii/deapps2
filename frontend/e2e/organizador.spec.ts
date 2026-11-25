import type { Page } from '@playwright/test';
import { CUENTAS, FOTO_PNG, expect, fechaDentroDe, ingresar, sufijo, test } from './apoyo';

// El panel del organizador: crear un evento con foto, editarlo y cancelarlo, todo desde la pantalla, y
// ver las ventas por día y la liquidación de un evento. Lo que guardó el servidor se comprueba por la API,
// y el efecto en el catálogo, en el navegador.

/** Elige el evento en "Detalle por evento" y devuelve su sección de ventas. */
async function ventasDelEvento(page: Page, eventoId: string) {
  await page.getByRole('combobox', { name: 'Evento', exact: true }).selectOption(eventoId);
  return page.getByRole('region', { name: 'Ventas y liquidación' });
}

test('organizador: ve las ventas por día y la liquidación de su evento (HU-12)', async ({ page, api }) => {
  const evento = await api.crearEvento('E2E ventas por día');
  await ingresar(page, CUENTAS.organizador);
  await page.goto('/organizador');

  // Sin ventas pagadas: la tabla lo dice y la liquidación está en cero.
  let ventas = await ventasDelEvento(page, evento.id);
  const cifra = (etiqueta: string) => ventas.locator('dl > div').filter({ hasText: etiqueta }).locator('dd');
  const tabla = () => ventas.getByRole('region', { name: 'Ventas por día' });
  await expect(tabla().getByText('Todavía no hay ventas pagadas de este evento.')).toBeVisible();
  await expect(cifra('Recaudado de los compradores')).toHaveText('$0');
  await expect(cifra('A liquidar al organizador')).toHaveText('$0');

  // Una compra pagada de una entrada de Campo ($20.000 más el 10 % de cargo de servicio).
  const cuenta = await api.cuentaNueva();
  await api.comprar(cuenta, evento, evento.sectores[0].id);
  // Ventas agrupa por el día de la confirmación del pago en UTC, el que se muestra en la fila.
  const [anio, , diaDelMes] = new Date().toISOString().slice(0, 10).split('-');

  await page.reload();
  ventas = await ventasDelEvento(page, evento.id);
  await expect(cifra('Recaudado de los compradores')).toHaveText('$22.000');
  await expect(cifra('Cargo de Palco')).toHaveText('$2.000');
  await expect(cifra('A liquidar al organizador')).toHaveText('$20.000');
  await expect(ventas.getByText('1 compra pagada · 1 entrada')).toBeVisible();

  const filas = tabla().getByRole('row').filter({ has: page.getByRole('cell') });
  await expect(filas).toHaveCount(1);
  await expect(filas.first().getByRole('cell')).toHaveText([
    new RegExp(`^${diaDelMes}\\b.*${anio}$`),
    '1',
    '1',
    '$20.000',
    '$2.000',
    '$22.000',
  ]);
});

test('organizador: crea un evento con foto, lo edita y lo cancela', async ({ page, api }) => {
  const titulo = `E2E organizador ${sufijo()}`;
  const editado = `${titulo} (editado)`;

  await ingresar(page, CUENTAS.organizador);
  await page.goto('/organizador');
  await expect(page.getByRole('heading', { level: 1, name: 'Eventos' })).toBeVisible();

  // Crear, con una foto propia.
  await page.getByRole('button', { name: '+ Crear evento' }).click();
  const alta = page.getByRole('dialog', { name: 'Crear evento' });
  await alta.locator('input[type="file"]').setInputFiles({ name: 'foto-e2e.png', mimeType: 'image/png', buffer: FOTO_PNG });
  await expect(alta.getByText('foto-e2e.png · se sube al guardar.')).toBeVisible();
  await expect(alta.getByRole('img', { name: 'Foto del evento' })).toHaveAttribute('src', /^blob:/);
  await alta.getByLabel('Título').fill(titulo);
  await alta.getByLabel('Categoría').selectOption('Música');
  await alta.getByLabel('Lugar').fill('Sala de pruebas, Rosario');
  await alta.getByLabel('Fecha y hora').fill(fechaDentroDe(90).slice(0, 16));
  await alta.getByLabel('Descripción').fill('Evento creado desde el panel por una prueba E2E.');
  const primero = alta.getByRole('group', { name: 'Sector 1' });
  await primero.getByLabel('Nombre del sector').fill('Campo');
  await primero.getByLabel('Precio ($)').fill('15000');
  await primero.getByLabel('Cupo').fill('100');
  await alta.getByRole('button', { name: '+ Agregar sector' }).click();
  const segundo = alta.getByRole('group', { name: 'Sector 2' });
  await segundo.getByLabel('Nombre del sector').fill('Platea');
  await segundo.getByLabel('Precio ($)').fill('25000');
  await segundo.getByLabel('Cupo').fill('50');
  await alta.getByRole('button', { name: 'Crear evento' }).click();

  await expect(alta).toBeHidden();
  await expect(page.getByText('Evento creado. Ya está publicado en el catálogo.')).toBeVisible();
  const tabla = page.getByRole('region', { name: 'Tabla de mis eventos' });
  await expect(tabla.getByRole('row').filter({ hasText: titulo })).toBeVisible();

  // El servidor lo guardó con la foto subida, que se sirve tal cual.
  const [creado] = await api.buscarEventos(titulo);
  expect(creado, 'el evento creado está en el catálogo').toBeTruthy();
  api.recordarEvento(creado.id);
  expect(creado.imagenUrl, 'el evento tiene foto propia').toBeTruthy();
  const foto = await api.foto(creado.imagenUrl!);
  expect(foto.status()).toBe(200);
  expect(foto.headers()['content-type']).toContain('image/png');
  expect(Buffer.compare(await foto.body(), FOTO_PNG), 'la foto servida es la que se subió').toBe(0);

  // Editar: el formulario abre con los datos actuales; se cambian el título y el cupo de un sector.
  await page.getByRole('combobox', { name: 'Evento', exact: true }).selectOption(creado.id);
  await page.getByRole('button', { name: 'Editar evento' }).click();
  const edicion = page.getByRole('dialog', { name: 'Editar evento' });
  await expect(edicion.getByLabel('Título')).toHaveValue(titulo);
  await expect(edicion.getByRole('img', { name: 'Foto del evento' })).toBeVisible();
  await edicion.getByLabel('Título').fill(editado);
  await edicion.getByRole('group', { name: 'Sector 1' }).getByLabel('Cupo').fill('120');
  await edicion.getByRole('button', { name: 'Guardar cambios' }).click();

  await expect(edicion).toBeHidden();
  await expect(page.getByText('Cambios guardados. El catálogo ya muestra el evento actualizado.')).toBeVisible();
  await expect(tabla.getByRole('row').filter({ hasText: editado })).toBeVisible();
  const [guardado] = await api.buscarEventos(editado);
  expect(guardado?.id).toBe(creado.id);
  expect(guardado.sectores.find((s) => s.nombre === 'Campo')?.cupo).toBe(120);
  expect(guardado.imagenUrl, 'editar otra cosa no pierde la foto').toBe(creado.imagenUrl);

  // Cancelar: el modal avisa que no tiene vuelta atrás y que no hay ventas que anular.
  await page.getByRole('button', { name: 'Cancelar evento' }).click();
  const cancelacion = page.getByRole('dialog', { name: 'Cancelar evento' });
  await expect(cancelacion.getByText('Esto no se puede deshacer.')).toBeVisible();
  await expect(cancelacion.getByText(/^Todavía no hay entradas vendidas/)).toBeVisible();
  await cancelacion.getByLabel(/^Motivo/).fill('El lugar no fue habilitado.');
  await cancelacion.getByRole('button', { name: 'Sí, cancelar el evento' }).click();

  await expect(cancelacion).toBeHidden();
  await expect(page.getByText(/^Evento cancelado\./)).toBeVisible();
  await expect(page.getByText(/^Este evento está cancelado\..*Motivo: El lugar no fue habilitado\./)).toBeVisible();
  await expect(tabla.getByRole('row').filter({ hasText: editado })).toContainText('cancelado');
  await expect(page.getByRole('button', { name: 'Editar evento' })).toHaveCount(0);

  // Sale del catálogo, y su página avisa la cancelación con el motivo y no deja comprar.
  expect(await api.buscarEventos(editado)).toHaveLength(0);
  await page.goto(`/evento/${guardado.slug}`);
  await expect(
    page.getByText(/^Este evento se canceló: El lugar no fue habilitado\./).filter({ visible: true }).first(),
  ).toBeVisible();
  await expect(page.getByRole('button', { name: 'Sumar entrada de Campo' })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Continuar al checkout' })).toBeDisabled();
});
