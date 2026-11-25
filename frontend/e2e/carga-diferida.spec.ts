import { expect, menu, test } from './apoyo';

// Cada pantalla, menos el catálogo, sale en su propio archivo de JavaScript y se baja la primera vez que se
// la abre (src/App.tsx). Si ese archivo no llega, la pantalla avisa y ofrece recargar, en lugar de quedar en
// blanco. El archivo se llama como la pantalla: /src/pages/LoginPage.tsx con el servidor de desarrollo y
// /assets/LoginPage-<hash>.js en el build que sirve nginx; el patrón vale para los dos.

const ARCHIVO_DE_INGRESAR = '**/LoginPage*';

test('carga diferida: si no llega el archivo de una pantalla, avisa y al recargar la abre', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Próximos eventos' })).toBeVisible();

  // Solo en este navegador: el pedido del archivo de la pantalla de ingreso no llega.
  await page.route(ARCHIVO_DE_INGRESAR, (ruta) => ruta.abort('connectionrefused'));
  await menu(page).getByRole('link', { name: 'Ingresar' }).click();
  await expect(page).toHaveURL(/\/ingresar$/);
  await expect(page.getByText('No pudimos abrir esta pantalla')).toBeVisible();
  await expect(page.getByText('Revisá tu conexión y recargá la página.')).toBeVisible();
  // El encabezado sigue ahí: se puede volver al catálogo, que ya estaba cargado.
  await expect(menu(page).getByRole('link', { name: 'Reventa' })).toBeVisible();

  await page.unroute(ARCHIVO_DE_INGRESAR);
  await page.getByRole('button', { name: 'Recargar la página' }).click();
  await expect(page.getByRole('heading', { name: 'Ya tengo cuenta' })).toBeVisible();
});
