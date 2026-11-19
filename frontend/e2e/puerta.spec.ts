import { CUENTAS, expect, formatearDni, ingresar, sufijo, test } from './apoyo';

// La validación en la puerta: la cuenta de puerta elige el evento y carga el código de la entrada.
// El evento es de la prueba y queda asignado a esa cuenta; la entrada se compra por la API, con una
// cuenta nueva, y su código es el codigoQr que da Ventas al emitirla.

test('puerta: valida una entrada, rechaza el mismo código la segunda vez y uno que no existe', async ({ page, api }) => {
  const evento = await api.crearEvento('E2E puerta', { puerta: true });
  const cuenta = await api.cuentaNueva();
  const venta = await api.comprar(cuenta, evento, evento.sectores[0].id);
  const codigo = venta.entradas[0].codigoQr!;

  await ingresar(page, CUENTAS.puerta);
  await page.goto('/puerta');
  await expect(page.getByRole('heading', { name: 'Validación en puerta' })).toBeVisible();
  await page.getByLabel('Evento de esta puerta').selectOption(evento.id);

  const campoCodigo = page.getByLabel('Código QR');
  const validar = page.getByRole('button', { name: 'Validar entrada' });

  // Primera lectura: válida, con los datos del titular para contrastar con el documento.
  await campoCodigo.fill(codigo);
  await validar.click();
  await expect(page.getByText('✓ Entrada válida')).toBeVisible();
  await expect(page.getByText(`${cuenta.nombre} ${cuenta.apellido}`, { exact: true })).toBeVisible();
  await expect(page.getByText(formatearDni(cuenta.dni), { exact: true })).toBeVisible();

  // Segunda lectura del mismo código: ya se usó, y dice cuándo y con qué cuenta.
  await campoCodigo.fill(codigo);
  await validar.click();
  await expect(page.getByText('Entrada ya utilizada')).toBeVisible();
  await expect(page.getByText(/^Escaneo anterior: .* · con esta cuenta$/)).toBeVisible();

  // Un código que Palco no emitió.
  await campoCodigo.fill(`no-es-de-palco-${sufijo()}`);
  await validar.click();
  await expect(page.getByText('Código no reconocido')).toBeVisible();
});
