import type { BrowserContext, Page } from '@playwright/test';
import { CUENTAS, expect, fechaDentroDe, ingresar, sufijo, test } from './apoyo';

// XSS almacenado: lo que un organizador escribe en su evento lo ve cualquiera en el catálogo y en el detalle.
// Las pruebas guardan HTML con JavaScript en cada texto del evento (título, lugar, descripción y nombre del
// sector) y, en cada pantalla donde aparece, comprueban dos cosas:
//  - que en la página no esté ninguno de los elementos que traen las cargas (ni un <img onerror>, ni un <svg
//    onload>, ni un <script> con ellas): React escribe los textos como texto, nunca como HTML;
//  - que no se haya abierto ningún alert(), que es lo que hace cada carga si llega a ejecutarse.
// La primera no depende de la Content-Security-Policy de nginx, que contra el compose bloquearía un script
// inyectado: si la página escribiera un texto como HTML, el elemento estaría ahí aunque no lo dejaran correr.
//
// Que la API guarde esos textos tal cual y los devuelva como JSON lo prueba scripts/pruebas/seguridad.mjs (bloque C).
//
// Las dos pruebas comprueban también que el navegador no guarda ninguna cookie, con sesión y sin ella: el token va
// en la cabecera Authorization, así que un pedido armado desde otro sitio (CSRF) no lleva la sesión. Guardado en
// localStorage, queda al alcance de un script que llegara a correr en la página: por eso importa que ningún texto
// se escriba como HTML.

/** Cargas de XSS: escritas como HTML, cada una llamaría a alert() con el nombre del campo donde va. */
const cargas = (marca: string) => ({
  titulo: `<img src=x onerror=alert('titulo')> <script>alert('titulo')</script> XSS ${marca}`,
  lugar: `<iframe src="javascript:alert('lugar')"></iframe> Sala de pruebas, Rosario`,
  descripcion: `<svg onload=alert('descripcion')></svg> <a href="javascript:alert('descripcion')">ver</a> Evento de una prueba E2E.`,
  sector: `<b onmouseover=alert('sector')>Campo</b>`,
});

/** Los elementos que dejarían las cargas en la página si se escribieran como HTML. */
const INYECTADOS = 'img[src="x"], [onerror], svg[onload], iframe[src^="javascript:"], a[href^="javascript:"], b[onmouseover]';

/** Anota cada diálogo que se abra (y lo cierra, para que la página siga): ninguno tendría que abrirse. */
function vigilarDialogos(page: Page): string[] {
  const dialogos: string[] = [];
  page.on('dialog', (dialogo) => {
    dialogos.push(`${dialogo.type()}: ${dialogo.message()}`);
    void dialogo.dismiss();
  });
  return dialogos;
}

/** En la pantalla que está abierta, las cargas quedaron como texto. */
async function soloTexto(page: Page, pantalla: string, dialogos: string[]): Promise<void> {
  await expect(page.locator(INYECTADOS), `${pantalla}: ningún elemento de las cargas`).toHaveCount(0);
  const scripts = await page.evaluate(
    () => [...document.querySelectorAll('script')].filter((s) => (s.textContent ?? '').includes('alert(')).length,
  );
  expect(scripts, `${pantalla}: ningún <script> con las cargas`).toBe(0);
  expect(dialogos, `${pantalla}: ningún alert() se ejecutó`).toEqual([]);
}

/**
 * Palco no usa cookies: el token viaja en la cabecera Authorization y el frontend lo guarda en localStorage
 * (src/lib/sesion.ts). En el navegador no tiene que haber ninguna cookie, ni de la app ni de la API. Si aparece
 * una, el mensaje muestra cuáles de Secure, HttpOnly y SameSite trae, que son los que tendría que llevar.
 * Que ninguna respuesta del servidor traiga Set-Cookie, por nginx y directo al backend, lo prueba
 * scripts/pruebas/seguridad.mjs (bloque E).
 */
async function sinCookies(context: BrowserContext, momento: string): Promise<void> {
  const cookies = (await context.cookies()).map(
    (c) => `${c.name} (${c.domain}${c.secure ? ', Secure' : ''}${c.httpOnly ? ', HttpOnly' : ''}, SameSite=${c.sameSite})`,
  );
  expect(cookies, `${momento}: ninguna cookie en el navegador`).toEqual([]);
}

test('XSS almacenado: el HTML que el organizador carga en su evento se ve como texto en el panel, el catálogo y el detalle', async ({ page, context, api }) => {
  const marca = sufijo();
  const carga = cargas(marca);
  const dialogos = vigilarDialogos(page);

  // El organizador carga el evento desde su panel, como cualquier otro.
  await ingresar(page, CUENTAS.organizador);
  // La sesión abierta está en localStorage, no en una cookie.
  const sesion = await page.evaluate(() => localStorage.getItem('palco.sesion'));
  expect(sesion, 'la sesión guardada en localStorage, con el token').toContain('"token"');
  await sinCookies(context, 'después de ingresar');
  await page.goto('/organizador');
  await page.getByRole('button', { name: '+ Crear evento' }).click();
  const alta = page.getByRole('dialog', { name: 'Crear evento' });
  await alta.getByLabel('Título').fill(carga.titulo);
  await alta.getByLabel('Categoría').selectOption('Teatro');
  await alta.getByLabel('Lugar').fill(carga.lugar);
  await alta.getByLabel('Fecha y hora').fill(fechaDentroDe(90).slice(0, 16));
  await alta.getByLabel('Descripción').fill(carga.descripcion);
  const sector = alta.getByRole('group', { name: 'Sector 1' });
  await sector.getByLabel('Nombre del sector').fill(carga.sector);
  await sector.getByLabel('Precio ($)').fill('20000');
  await sector.getByLabel('Cupo').fill('100');
  await alta.getByRole('button', { name: 'Crear evento' }).click();
  await expect(alta).toBeHidden();
  await expect(page.getByText('Evento creado. Ya está publicado en el catálogo.')).toBeVisible();

  // El servidor lo guardó con el texto tal cual. La prueba lo cancela al terminar.
  const [creado] = await api.buscarEventos(marca);
  expect(creado, 'el evento creado está en el catálogo').toBeTruthy();
  api.recordarEvento(creado.id);
  expect(creado.titulo).toBe(carga.titulo);

  // Panel del organizador: la tabla de sus eventos.
  const tabla = page.getByRole('region', { name: 'Tabla de mis eventos' });
  await expect(tabla.getByRole('row').filter({ hasText: carga.titulo })).toBeVisible();
  await soloTexto(page, 'panel del organizador', dialogos);

  // Catálogo: la tarjeta del evento, buscada por la marca de esta corrida.
  await page.goto('/');
  await page.getByRole('searchbox', { name: 'Buscar eventos' }).fill(marca);
  const tarjeta = page.getByRole('link').filter({ hasText: carga.titulo });
  await expect(tarjeta).toHaveCount(1);
  await expect(tarjeta).toContainText(carga.lugar);
  await soloTexto(page, 'catálogo', dialogos);

  // Detalle: título, lugar, descripción y sector, y el título de la pestaña, que sale del título del evento.
  await tarjeta.click();
  await expect(page).toHaveURL(new RegExp(`/evento/${creado.slug}$`));
  await expect(page.getByRole('heading', { level: 1, name: carga.titulo })).toBeVisible();
  await expect(page.getByText(carga.lugar).first()).toBeVisible();
  await expect(page.getByText(carga.descripcion, { exact: true }).first()).toBeVisible();
  await expect(page.getByText(carga.sector, { exact: true }).first()).toBeVisible();
  await expect(page).toHaveTitle(`${carga.titulo} · Palco`);
  await soloTexto(page, 'detalle del evento', dialogos);
  await sinCookies(context, 'después de crear el evento con la sesión abierta');
});

test('XSS sin pasar por el formulario: un título con HTML guardado por la API y una búsqueda con HTML se ven como texto, sin sesión', async ({ page, context, api }) => {
  const dialogos = vigilarDialogos(page);
  // crearEvento le agrega al título un sufijo único: es lo que se busca en el catálogo.
  const evento = await api.crearEvento(`<img src=x onerror=alert('api')> <script>alert('api')</script> XSS por la API`);
  const marca = evento.titulo.split(' ').at(-1)!;

  // Lo que se escribe en el buscador vuelve en pantalla, en el aviso de que no hay resultados.
  await page.goto('/');
  const buscador = page.getByRole('searchbox', { name: 'Buscar eventos' });
  const busqueda = `<img src=x onerror=alert('busqueda')> ${sufijo()}`;
  await buscador.fill(busqueda);
  await expect(page.getByText(`Sin resultados para «${busqueda}»`)).toBeVisible();
  await soloTexto(page, 'búsqueda sin resultados', dialogos);

  // El evento, para quien entra sin sesión: la tarjeta del catálogo y el detalle.
  await buscador.fill(marca);
  const tarjeta = page.getByRole('link').filter({ hasText: evento.titulo });
  await expect(tarjeta).toHaveCount(1);
  await soloTexto(page, 'catálogo sin sesión', dialogos);

  await page.goto(`/evento/${evento.slug}`);
  await expect(page.getByRole('heading', { level: 1, name: evento.titulo })).toBeVisible();
  await soloTexto(page, 'detalle sin sesión', dialogos);
  await sinCookies(context, 'sin sesión, después del catálogo y el detalle');
});
