import { expect, menu, sufijo, test } from './apoyo';

// Catálogo y detalle de un evento, y cómo se ven los errores del servidor. El evento lo crea la
// prueba, así se lo puede buscar por un título que no tiene ningún otro.

test('catálogo: busca un evento, lo filtra por categoría y abre su detalle con los sectores', async ({ page, api }) => {
  const evento = await api.crearEvento('E2E catalogo', { categoria: 'Teatro' });

  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Próximos eventos' })).toBeVisible();

  await page.getByRole('searchbox', { name: 'Buscar eventos' }).fill(evento.titulo);
  await expect(page.getByRole('heading', { name: 'Resultados' })).toBeVisible();
  const tarjeta = page.getByRole('link').filter({ hasText: evento.titulo });
  await expect(tarjeta).toHaveCount(1);
  await expect(tarjeta).toContainText('Sala de pruebas, Rosario');
  await expect(tarjeta).toContainText('Desde $20.000');

  // La búsqueda se combina con la categoría: en Música no está y en Teatro sí.
  await page.getByRole('button', { name: 'Música', exact: true }).click();
  await expect(page.getByText(`Sin resultados para «${evento.titulo}»`)).toBeVisible();
  await page.getByRole('button', { name: 'Teatro', exact: true }).click();
  await expect(tarjeta).toBeVisible();

  await tarjeta.click();
  await expect(page).toHaveURL(new RegExp(`/evento/${evento.slug}$`));
  await expect(page.getByRole('heading', { level: 1, name: evento.titulo })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Mapa de sectores' })).toBeVisible();

  // Dos entradas de Campo: 2 × $20.000 más el 10 % de cargo de servicio.
  const selector = page.locator('aside').filter({ hasText: 'Elegí tus entradas' });
  await expect(selector.getByText('Campo', { exact: true })).toBeVisible();
  await expect(selector.getByText('Platea', { exact: true })).toBeVisible();
  const continuar = selector.getByRole('button', { name: 'Continuar al checkout' });
  await expect(continuar).toBeDisabled();
  await selector.getByRole('button', { name: 'Sumar entrada de Campo' }).click();
  await selector.getByRole('button', { name: 'Sumar entrada de Campo' }).click();
  await expect(selector.getByText('$40.000', { exact: true })).toBeVisible();
  await expect(selector.getByText('$4.000', { exact: true })).toBeVisible();
  await expect(selector.getByText('$44.000', { exact: true })).toBeVisible();
  await expect(continuar).toBeEnabled();
});

test('si el servidor no responde, el catálogo muestra el error con "Reintentar" y después se recupera', async ({ page }) => {
  // La caída se simula solo en este navegador: el servidor es compartido y no se lo toca.
  await page.route('**/api/eventos**', (ruta) =>
    ruta.fulfill({
      status: 503,
      contentType: 'application/problem+json',
      body: JSON.stringify({ type: '/problemas/no-disponible', title: 'No disponible', status: 503, detail: 'Eventos no responde.' }),
    }),
  );
  await page.goto('/');

  await expect(page.getByText('No pudimos cargar los eventos')).toBeVisible();
  await expect(page.getByText('Ocurrió un error inesperado. Probá de nuevo en un momento.')).toBeVisible();
  // No es una pantalla en blanco: el encabezado sigue ahí y se puede navegar.
  await expect(menu(page).getByRole('link', { name: 'Reventa' })).toBeVisible();

  await page.unroute('**/api/eventos**');
  await page.getByRole('button', { name: 'Reintentar' }).click();
  await expect(page.getByRole('heading', { name: 'Próximos eventos' })).toBeVisible();
  await expect(page.getByText('No pudimos cargar los eventos')).toHaveCount(0);
});

test('un evento que no existe: el 404 del servidor se muestra como aviso, no como pantalla en blanco', async ({ page }) => {
  await page.goto(`/evento/no-existe-${sufijo()}`);

  await expect(page.getByText('No encontramos ese evento')).toBeVisible();
  await page.getByRole('button', { name: 'Ver el catálogo' }).click();
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole('heading', { name: 'Próximos eventos' })).toBeVisible();
});
