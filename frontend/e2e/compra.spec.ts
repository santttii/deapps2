import { dniAlAzar, expect, formatearDni, ingresar, test, type Evento } from './apoyo';
import type { Page } from '@playwright/test';

// La compra de punta a punta en el navegador: del detalle del evento al checkout (titulares, reserva
// y pago con la pasarela simulada) y a la entrada con su QR. Cada prueba compra con una cuenta
// recién creada en un evento propio: no la frenan el tope de entradas por persona ni una compra en
// curso de otra prueba.

/** Elige entradas de Campo en el detalle del evento y pasa al checkout. */
async function elegirEntradas(page: Page, evento: Evento, cantidad: number) {
  await page.goto(`/evento/${evento.slug}`);
  const selector = page.locator('aside').filter({ hasText: 'Elegí tus entradas' });
  for (let i = 0; i < cantidad; i++) {
    await selector.getByRole('button', { name: 'Sumar entrada de Campo' }).click();
  }
  await selector.getByRole('button', { name: 'Continuar al checkout' }).click();
}

/** Completa la tarjeta en el paso de pago. El número decide qué hace la pasarela simulada. */
async function pagarConTarjeta(page: Page, numero: string) {
  await page.getByRole('group', { name: 'Medio de pago' }).getByRole('button', { name: 'Tarjeta' }).click();
  await page.getByLabel('Número de tarjeta').fill(numero);
  await page.getByLabel('Titular', { exact: true }).fill('Lucía Navegador');
  await page.getByLabel('Vencimiento (MM/AA)').fill('1230');
  await page.getByLabel('CVV').fill('123');
  await page.getByRole('button', { name: 'Pagar y confirmar' }).click();
}

test('compra completa: del evento al checkout, pago aprobado y la entrada con su QR en Mis entradas', async ({ page, api }) => {
  const evento = await api.crearEvento('E2E compra');
  const cuenta = await api.cuentaNueva();
  const dniAcompanante = dniAlAzar();

  // Sin sesión se puede elegir; el checkout pide ingresar y después vuelve a él.
  await elegirEntradas(page, evento, 2);
  await expect(page).toHaveURL(/\/ingresar\?next=%2Fcheckout$/);
  await ingresar(page, cuenta.email);
  await expect(page).toHaveURL(/\/checkout$/);
  await expect(page.getByRole('heading', { name: 'Checkout' })).toBeVisible();

  // Paso 1, titulares: la primera entrada es de quien compra y no se cambia; la segunda se completa.
  const nombres = page.getByLabel('Nombre', { exact: true });
  const apellidos = page.getByLabel('Apellido', { exact: true });
  const dnis = page.getByLabel('DNI', { exact: true });
  await expect(nombres.first()).toHaveValue(cuenta.nombre);
  await expect(dnis.first()).toHaveValue(cuenta.dni);
  await expect(dnis.first()).toBeDisabled();
  await nombres.nth(1).fill('Martín');
  await apellidos.nth(1).fill('Acompañante');
  await dnis.nth(1).fill(dniAcompanante);
  await page.getByRole('button', { name: 'Continuar', exact: true }).click();

  // Paso 2, pago: el cupo quedó reservado y corre la cuenta regresiva.
  await expect(page.getByText(/^Reserva: \d+:\d\d$/)).toBeVisible();
  await expect(page.getByText('Tus lugares están reservados.', { exact: false })).toBeVisible();
  // Terminada en 1111: la pasarela simulada la aprueba (en 0002 la rechaza y en 0504 no responde).
  await pagarConTarjeta(page, '4111111111111111');

  // Confirmación: el pago se aprobó y, cuando termina la emisión asincrónica, aparece un QR por entrada.
  await expect(page).toHaveURL(/\/confirmacion\/V-[\w-]+$/);
  await expect(page.getByText(/^Pago aprobado · orden #V-/)).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Tus entradas' })).toBeVisible({ timeout: 40_000 });
  await expect(page.getByRole('img', { name: /^Código QR de la entrada / })).toHaveCount(2);
  await expect(page.getByText(formatearDni(cuenta.dni), { exact: true })).toBeVisible();
  await expect(page.getByText(formatearDni(dniAcompanante), { exact: true })).toBeVisible();
  await expect(page.getByText('$44.000', { exact: true })).toBeVisible();

  await page.getByRole('link', { name: 'Ver en mis entradas' }).click();
  await expect(page).toHaveURL(/\/mis-entradas$/);
  await expect(page.getByRole('heading', { level: 1, name: 'Mis entradas' })).toBeVisible();
  await expect(page.getByText('Entrada 1 de 2')).toBeVisible();
  await expect(page.getByRole('heading', { name: evento.titulo })).toBeVisible();
  await expect(page.getByRole('img', { name: /^Código QR de la entrada / })).toBeVisible();
  await expect(page.getByText('Código para la puerta')).toBeVisible();
  await page.getByRole('button', { name: 'Entrada siguiente' }).click();
  await expect(page.getByText('Entrada 2 de 2')).toBeVisible();
  await expect(page.getByRole('img', { name: /^Código QR de la entrada / })).toBeVisible();
});

test('tarjeta rechazada: el mensaje del servidor se muestra en el pago y la compra se puede cancelar', async ({ page, api }) => {
  const evento = await api.crearEvento('E2E rechazo');
  const cuenta = await api.cuentaNueva();

  await ingresar(page, cuenta.email);
  await elegirEntradas(page, evento, 1);
  await expect(page).toHaveURL(/\/checkout$/);
  await page.getByRole('button', { name: 'Continuar', exact: true }).click();
  await expect(page.getByText(/^Reserva: \d+:\d\d$/)).toBeVisible();

  // Terminada en 0002: la pasarela simulada la rechaza y Ventas responde 402 con el motivo.
  await pagarConTarjeta(page, '4000000000000002');
  await expect(page.getByRole('alert').filter({ hasText: 'El pago fue rechazado' })).toBeVisible();
  await expect(page).toHaveURL(/\/checkout$/);
  await expect(page.getByRole('button', { name: 'Pagar y confirmar' })).toBeEnabled();

  // Cancelar devuelve el cupo y vuelve al evento.
  await page.getByRole('button', { name: 'Cancelar compra' }).click();
  await expect(page).toHaveURL(new RegExp(`/evento/${evento.slug}$`));
  await expect(page.getByRole('heading', { level: 1, name: evento.titulo })).toBeVisible();
});
