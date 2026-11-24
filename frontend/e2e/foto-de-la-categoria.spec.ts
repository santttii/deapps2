import { expect, test } from './apoyo';

// CA-16.4: un evento sin foto propia se muestra con la foto de su categoría, en el catálogo y en su página.
// El evento se crea por la API sin imagenUrl (lo que hace el alta del panel si no se elige una foto) y el
// resultado se mira en el navegador.

test('CP-16.4.1 foto de la categoría: un evento de Música sin foto se muestra con la foto de Música', async ({
  page,
  api,
}) => {
  const evento = await api.crearEvento('E2E sin foto', { categoria: 'Música' });
  expect(evento.imagenUrl ?? null, 'el servidor lo guarda sin imagen propia').toBeNull();

  // La página del evento.
  await page.goto(`/evento/${evento.slug}`);
  await expect(page.getByRole('heading', { level: 1, name: evento.titulo })).toBeVisible();
  const fotoDeLaPagina = page.locator('main img').first();
  await expect(fotoDeLaPagina).toHaveAttribute('src', /\/eventos\/categoria-musica\.jpg$/);

  // Su tarjeta en el catálogo, buscándolo por el título.
  await page.goto('/');
  await page.getByRole('searchbox', { name: 'Buscar eventos' }).fill(evento.titulo);
  const tarjeta = page.getByRole('link', { name: new RegExp(evento.titulo) }).first();
  await expect(tarjeta).toBeVisible();
  await expect(tarjeta.locator('img')).toHaveAttribute('src', /\/eventos\/categoria-musica\.jpg$/);
});
