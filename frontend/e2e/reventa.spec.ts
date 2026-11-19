import { expect, ingresar, salir, test } from './apoyo';

// La reventa oficial: quien tiene una entrada la publica desde Mis entradas, con el tope de lo
// pagado + 10 %, y otra cuenta la compra desde la reventa. La entrada de partida se compra por la API:
// la compra en pantalla ya la cubre compra.spec.ts.

test('reventa: publica una entrada respetando el tope, aparece en la reventa y otra cuenta la compra', async ({ page, api }) => {
  const evento = await api.crearEvento('E2E reventa');
  const campo = evento.sectores.find((s) => s.nombre === 'Campo')!;
  const vendedor = await api.cuentaNueva();
  const compradora = await api.cuentaNueva();
  await api.comprar(vendedor, evento, campo.id);

  await ingresar(page, vendedor.email);
  await page.goto('/mis-entradas');
  await expect(page.getByRole('heading', { name: evento.titulo })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Publicar en reventa oficial' })).toBeVisible();
  // Pagó $20.000: puede pedir entre el 50 % ($10.000) y el 110 % ($22.000).
  await expect(page.getByText('$20.000', { exact: true })).toBeVisible();
  await expect(page.getByText('$10.000', { exact: true })).toBeVisible();
  await expect(page.getByText('$22.000', { exact: true })).toBeVisible();

  const precio = page.getByLabel('Precio de venta');
  const publicar = page.getByRole('button', { name: 'Publicar', exact: true });

  // Un peso por encima del tope no se publica.
  await precio.fill('22001');
  await publicar.click();
  await expect(page.getByText('El máximo permitido es $22.000.')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Publicar en reventa oficial' })).toBeVisible();

  // Justo en el tope, sí.
  await precio.fill('22000');
  await publicar.click();
  await expect(page.getByRole('heading', { name: 'Publicada en la reventa oficial' })).toBeVisible();
  await expect(page.getByText(/^Publicada a \$22\.000 desde el /)).toBeVisible();
  // Mientras está publicada no sirve para entrar: en lugar del QR se dice por qué.
  await expect(page.getByRole('img', { name: /^Código QR de la entrada / })).toHaveCount(0);
  await expect(page.getByText('Está publicada en la reventa oficial', { exact: false })).toBeVisible();

  // En la reventa del evento: con su tope, su precio y marcada como propia.
  await page.goto(`/reventa?evento=${evento.slug}`);
  await expect(page.getByRole('heading', { name: 'Reventa oficial' })).toBeVisible();
  const fila = page.getByRole('row').filter({ hasText: evento.titulo });
  await expect(fila).toHaveCount(1);
  await expect(fila).toContainText('Campo');
  await expect(fila.getByRole('cell', { name: '$22.000' })).toHaveCount(2);
  await expect(fila).toContainText('Es tu publicación');

  // Otra cuenta la compra. El titular se completa con sus datos.
  await salir(page);
  await ingresar(page, compradora.email);
  await page.goto(`/reventa?evento=${evento.slug}`);
  await fila.getByRole('button', { name: 'Comprar' }).click();
  const modal = page.getByRole('dialog', { name: 'Comprar en la reventa oficial' });
  await expect(modal.getByLabel('Nombre', { exact: true })).toHaveValue(compradora.nombre);
  await expect(modal.getByLabel('DNI', { exact: true })).toHaveValue(compradora.dni);
  await modal.getByRole('button', { name: 'Confirmar compra' }).click();

  await expect(page).toHaveURL(/\/mis-entradas$/);
  await expect(page.getByText('Compraste la entrada en la reventa oficial.')).toBeVisible();
  await expect(page.getByRole('heading', { name: evento.titulo })).toBeVisible();
  // La entrada se emite de nuevo, a su nombre y con un QR propio.
  await expect(page.getByRole('img', { name: /^Código QR de la entrada / })).toBeVisible({ timeout: 30_000 });

  // Y ya no está a la venta.
  await page.goto(`/reventa?evento=${evento.slug}`);
  await expect(page.getByText('No hay entradas publicadas en la reventa de este evento.')).toBeVisible();
});
