import { CUENTAS, PASSWORD, expect, ingresar, menu, salir, sufijo, test } from './apoyo';

// Ingreso de cada rol, el menú que ve cada uno y las rutas protegidas. Se usan las cuentas de prueba
// de las migraciones: solo se ingresa con la contraseña correcta, porque los intentos fallidos se
// cuentan por email y bloquearían la cuenta para el resto de quienes usan el servidor.

const ENLACES_DE_ROL = ['Mis entradas', 'Organizador', 'Puerta'];

const ROLES = [
  { rol: 'user', email: CUENTAS.comprador, enlace: 'Mis entradas', ruta: '/mis-entradas' },
  { rol: 'organizer', email: CUENTAS.organizador, enlace: 'Organizador', ruta: '/organizador' },
  { rol: 'staff', email: CUENTAS.puerta, enlace: 'Puerta', ruta: '/puerta' },
];

for (const { rol, email, enlace, ruta } of ROLES) {
  test(`ingreso con rol ${rol}: el menú muestra solo lo de su rol y su pantalla abre`, async ({ page }) => {
    await ingresar(page, email);

    await expect(menu(page).getByRole('link', { name: enlace })).toBeVisible();
    for (const otro of ENLACES_DE_ROL.filter((e) => e !== enlace)) {
      await expect(menu(page).getByRole('link', { name: otro })).toHaveCount(0);
    }
    // Lo común a toda cuenta con sesión.
    await expect(menu(page).getByRole('link', { name: 'Reventa' })).toBeVisible();
    await expect(menu(page).getByRole('link', { name: 'Asistente' })).toBeVisible();
    await expect(menu(page).getByRole('link', { name: 'Ingresar' })).toHaveCount(0);

    await menu(page).getByRole('link', { name: enlace }).click();
    await expect(page).toHaveURL(new RegExp(`${ruta}$`));
    // La pantalla del rol se muestra (y no el aviso de que es para otra cuenta).
    await expect(page.getByRole('heading', { name: 'Esta sección no es para tu cuenta' })).toHaveCount(0);
    const titulos: Record<string, RegExp> = {
      user: /^Mis entradas$|^Todavía no tenés entradas emitidas\.$/,
      organizer: /^Eventos$/,
      staff: /^Validación en puerta$/,
    };
    await expect(page.getByRole('main').getByText(titulos[rol]).first()).toBeVisible();

    await salir(page);
  });
}

test('una ruta protegida sin sesión lleva al ingreso y, al ingresar, vuelve a esa ruta', async ({ page }) => {
  await page.goto('/mis-entradas');

  await expect(page).toHaveURL(/\/ingresar\?next=%2Fmis-entradas$/);
  await expect(page.getByRole('heading', { name: 'Ya tengo cuenta' })).toBeVisible();

  await ingresar(page, CUENTAS.comprador);
  await expect(page).toHaveURL(/\/mis-entradas$/);
});

test('con una cuenta de otro rol, la pantalla protegida explica para quién es', async ({ page }) => {
  await ingresar(page, CUENTAS.comprador);
  await page.goto('/organizador');

  await expect(page.getByRole('heading', { name: 'Esta sección no es para tu cuenta' })).toBeVisible();
  await expect(page.getByText('Es para las cuentas de organizador.')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Eventos' })).toHaveCount(0);
});

test('credenciales incorrectas: el rechazo del servidor se muestra como mensaje', async ({ page }) => {
  // Un email que no existe, distinto en cada corrida: el intento fallido no bloquea ninguna cuenta real.
  await page.goto('/ingresar');
  await page.getByLabel('Email').fill(`nadie.${sufijo()}@palco.test`);
  await page.getByLabel('Contraseña', { exact: true }).fill(`${PASSWORD}-no`);
  await page.getByRole('button', { name: 'Ingresar', exact: true }).click();

  await expect(page.getByRole('alert')).toHaveText('Revisá el email y la contraseña.');
  await expect(page).toHaveURL(/\/ingresar$/);
  await expect(menu(page).getByRole('link', { name: 'Ingresar' })).toBeVisible();
});
