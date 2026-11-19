import { PASSWORD, codigoDeVerificacion, dniAlAzar, expect, marcaDelLog, menu, sufijo, test } from './apoyo';

// Alta de una cuenta en dos pasos: los datos y el código de 6 dígitos que llega por email. Sin SMTP,
// el servidor deja ese email en su log, y de ahí lo lee la prueba, como lo haría la persona en su
// casilla. Con el código correcto la cuenta queda verificada y con la sesión abierta.

test('registro: crea una cuenta, la verifica con el código del email y queda con la sesión abierta', async ({ page }) => {
  const email = `e2e.registro.${sufijo()}@palco.test`;

  await page.goto('/ingresar');
  await page.getByRole('main').getByRole('link', { name: 'Crear cuenta' }).click();
  await expect(page).toHaveURL(/\/registro$/);
  await expect(page.getByRole('heading', { name: 'Crear cuenta' })).toBeVisible();

  await page.getByLabel('Nombre', { exact: true }).fill('Julieta');
  await page.getByLabel('Apellido', { exact: true }).fill('Registro');
  await page.getByLabel('DNI', { exact: true }).fill(dniAlAzar());
  await page.getByLabel('Fecha de nacimiento').fill('1998-03-21');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Contraseña', { exact: true }).fill(PASSWORD);
  await page.getByLabel('Repetir contraseña').fill(PASSWORD);
  const crear = page.getByRole('button', { name: 'Crear cuenta', exact: true });
  // Sin aceptar los términos no se puede crear.
  await expect(crear).toBeDisabled();
  await page.getByLabel('Acepto los términos y la política anti-reventa.').check();
  await page.getByLabel('Declaro que los datos ingresados son propios.').check();

  const marca = marcaDelLog();
  await crear.click();
  await expect(page.getByText(`Te enviamos un código de 6 dígitos a ${email}.`, { exact: false })).toBeVisible();

  const codigo = await codigoDeVerificacion(email, marca);
  for (const [i, digito] of [...codigo].entries()) {
    await page.getByLabel(`Dígito ${i + 1} del código`).fill(digito);
  }
  await page.getByRole('button', { name: 'Verificar email', exact: true }).click();

  // Verificada: la sesión queda abierta con el rol de comprador.
  await expect(page).toHaveURL(/\/$/);
  await expect(menu(page).getByRole('button', { name: /salir$/ })).toBeVisible();
  await expect(menu(page).getByRole('link', { name: 'Mis entradas' })).toBeVisible();
});
