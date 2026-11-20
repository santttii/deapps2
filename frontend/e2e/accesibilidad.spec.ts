import AxeBuilder from '@axe-core/playwright';
import type { Page } from '@playwright/test';
import { CUENTAS, expect, ingresar, test } from './apoyo';

// Responsive y accesibilidad de las pantallas principales a 360, 768 y 1280 px de ancho: un teléfono, una tableta
// parada y una notebook. En cada pantalla y en cada ancho se comprueba:
//  - que no haya desborde horizontal: la página entra a lo ancho (scrollWidth <= innerWidth), sin scroll de costado;
//  - que axe-core no encuentre violaciones de impacto serious o critical de WCAG 2.0, 2.1 y 2.2, niveles A y AA;
// y se adjunta al reporte una captura de la página entera. Las comprobaciones son "soft": una pantalla con
// problemas no frena a las demás, y el reporte las lista todas. Las violaciones de impacto menor (minor y
// moderate) no hacen fallar la prueba, pero quedan en el adjunto axe.json de cada prueba.
//
// Las pantallas con sesión se recorren con la cuenta de su rol, que ingresa una sola vez por prueba. Cada prueba
// arma lo que su pantalla necesita para no verse vacía: un evento propio, una entrada emitida, una puerta asignada.

const ANCHOS = [
  { ancho: 360, alto: 800 },
  { ancho: 768, alto: 1024 },
  { ancho: 1280, alto: 800 },
];

/** WCAG 2.0, 2.1 y 2.2 en los niveles A y AA, con las etiquetas de axe-core. */
const ETIQUETAS_WCAG = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'];
const IMPACTOS_QUE_FALLAN = ['serious', 'critical'];

interface Pantalla {
  nombre: string;
  ruta: string;
  /** Espera a que la pantalla haya cargado sus datos y, si hace falta, la lleva al estado que se revisa. */
  preparar: (page: Page) => Promise<void>;
}

interface Hallazgo {
  pantalla: string;
  ancho: number;
  anchoDeLaPagina: number;
  violaciones: { id: string; impacto: string; ayuda: string; nodos: string[] }[];
}

// Sin animaciones, como pide quien tiene activado "reducir movimiento": las capturas salen quietas y el contraste
// no se mide en medio de una transición de opacidad.
test.use({ reducedMotion: 'reduce' });

/** Espera a que terminen las transiciones de opacidad de framer-motion, que siguen aun con movimiento reducido. */
async function quieta(page: Page): Promise<void> {
  await page.waitForFunction(() =>
    [...document.querySelectorAll<HTMLElement>('[style*="opacity"]')].every((e) => getComputedStyle(e).opacity === '1'),
  );
}

/** Recorre las pantallas en los tres anchos: desborde, axe y una captura de cada una. */
async function revisar(page: Page, pantallas: Pantalla[]): Promise<void> {
  const hallazgos: Hallazgo[] = [];

  for (const { ancho, alto } of ANCHOS) {
    await page.setViewportSize({ width: ancho, height: alto });
    for (const pantalla of pantallas) {
      const titulo = `${pantalla.nombre} a ${ancho} px`;
      await test.step(titulo, async () => {
        await page.goto(pantalla.ruta);
        await pantalla.preparar(page);
        await quieta(page);

        // Si la página es más ancha que la pantalla, el mensaje nombra los primeros elementos que se salen.
        const medida = await page.evaluate(() => {
          const visible = window.innerWidth;
          const seSalen = [...document.querySelectorAll('body *')]
            .filter((e) => e.getBoundingClientRect().right > visible + 1)
            .slice(0, 5)
            .map((e) => [e.tagName.toLowerCase(), ...[...e.classList].slice(0, 3)].join('.'));
          return { pagina: document.documentElement.scrollWidth, visible, seSalen };
        });
        expect
          .soft(medida.pagina, `${titulo}: la página es más ancha que la pantalla (${medida.seSalen.join(', ')})`)
          .toBeLessThanOrEqual(medida.visible);

        const { violations } = await new AxeBuilder({ page }).withTags(ETIQUETAS_WCAG).analyze();
        const violaciones = violations.map((v) => ({
          id: v.id,
          impacto: v.impact ?? 'sin dato',
          ayuda: v.help,
          nodos: v.nodes.slice(0, 5).map((n) => n.target.join(' ')),
        }));
        const graves = violaciones.filter((v) => IMPACTOS_QUE_FALLAN.includes(v.impacto));
        expect
          .soft(graves.map((v) => `${v.id} (${v.impacto}): ${v.ayuda} → ${v.nodos.join(' | ')}`), `${titulo}: axe`)
          .toEqual([]);
        hallazgos.push({ pantalla: pantalla.nombre, ancho, anchoDeLaPagina: medida.pagina, violaciones });

        await test.info().attach(`${pantalla.nombre} ${ancho}px.png`, {
          body: await page.screenshot({ fullPage: true }),
          contentType: 'image/png',
        });
      });
    }
  }

  await test.info().attach('axe.json', { body: JSON.stringify(hallazgos, null, 2), contentType: 'application/json' });
}

// Cada prueba recorre varias pantallas tres veces, con axe y una captura en cada una: lleva más que las otras.
test.describe.configure({ timeout: 240_000 });

test('responsive y accesibilidad sin sesión: catálogo, evento, ingresar, registro y reventa', async ({ page, api }) => {
  const evento = await api.crearEvento('E2E accesibilidad');

  await revisar(page, [
    {
      nombre: 'catálogo',
      ruta: '/',
      preparar: async (p) => {
        await expect(p.getByRole('heading', { name: 'Próximos eventos' })).toBeVisible();
      },
    },
    {
      nombre: 'evento',
      ruta: `/evento/${evento.slug}`,
      preparar: async (p) => {
        await expect(p.getByRole('heading', { level: 1, name: evento.titulo })).toBeVisible();
        await expect(p.getByRole('heading', { name: 'Mapa de sectores' })).toBeVisible();
      },
    },
    {
      nombre: 'ingresar',
      ruta: '/ingresar',
      preparar: async (p) => {
        await expect(p.getByRole('heading', { name: 'Ya tengo cuenta' })).toBeVisible();
      },
    },
    {
      nombre: 'registro',
      ruta: '/registro',
      preparar: async (p) => {
        await expect(p.getByRole('heading', { name: 'Crear cuenta' })).toBeVisible();
      },
    },
    {
      nombre: 'reventa',
      ruta: '/reventa',
      preparar: async (p) => {
        await expect(p.getByRole('heading', { name: 'Reventa oficial' })).toBeVisible();
        await expect(p.getByText(/^\d+ publicaci(ón|ones)$/)).toBeVisible();
      },
    },
  ]);
});

test('responsive y accesibilidad del comprador: mis entradas y asistente', async ({ page, api }) => {
  // Una entrada emitida, para que "Mis entradas" muestre el QR y el formulario de reventa.
  const evento = await api.crearEvento('E2E accesibilidad');
  const cuenta = await api.cuentaNueva();
  await api.comprar(cuenta, evento, evento.sectores[0].id);
  await ingresar(page, cuenta.email);

  await revisar(page, [
    {
      nombre: 'mis entradas',
      ruta: '/mis-entradas',
      preparar: async (p) => {
        await expect(p.getByRole('heading', { name: evento.titulo })).toBeVisible();
        await expect(p.getByRole('img', { name: /^Código QR de la entrada / })).toBeVisible();
        await expect(p.getByRole('heading', { name: 'Publicar en reventa oficial' })).toBeVisible();
      },
    },
    {
      nombre: 'asistente',
      ruta: '/asistente',
      preparar: async (p) => {
        await expect(p.getByRole('heading', { name: 'Asistente de Palco' })).toBeVisible();
      },
    },
  ]);
});

test('responsive y accesibilidad del organizador: panel de eventos y alta de un evento', async ({ page }) => {
  await ingresar(page, CUENTAS.organizador);
  const panelListo = async (p: Page) => {
    await expect(p.getByRole('heading', { level: 1, name: 'Eventos' })).toBeVisible();
    await expect(p.getByRole('region', { name: 'Tabla de mis eventos' })).toBeVisible();
  };

  await revisar(page, [
    { nombre: 'organizador', ruta: '/organizador', preparar: panelListo },
    {
      nombre: 'organizador, alta de evento',
      ruta: '/organizador',
      preparar: async (p) => {
        await panelListo(p);
        await p.getByRole('button', { name: '+ Crear evento' }).click();
        await expect(p.getByRole('dialog', { name: 'Crear evento' })).toBeVisible();
      },
    },
  ]);
});

test('responsive y accesibilidad de la puerta: validación de entradas', async ({ page, api }) => {
  const evento = await api.crearEvento('E2E accesibilidad', { puerta: true });
  await ingresar(page, CUENTAS.puerta);

  await revisar(page, [
    {
      nombre: 'puerta',
      ruta: '/puerta',
      preparar: async (p) => {
        await expect(p.getByRole('heading', { name: 'Validación en puerta' })).toBeVisible();
        await p.getByLabel('Evento de esta puerta').selectOption(evento.id);
        await expect(p.getByLabel('Código QR')).toBeVisible();
      },
    },
  ]);
});
