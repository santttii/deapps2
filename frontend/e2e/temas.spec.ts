import AxeBuilder from '@axe-core/playwright';
import type { Page } from '@playwright/test';
import { expect, test } from './apoyo';

// Temas visuales: normal, claro y oscuro. El selector "Tema" del encabezado cambia el tema al instante,
// sin recargar, y la elección queda guardada en el navegador.
//
// El tema puesto es el atributo data-tema de <html>; cada tema le da otros valores a las variables de color
// de src/index.css. Por eso se comprueban cuatro cosas: el atributo, la variable --color-paper (el fondo), el
// color de fondo que el navegador calcula para la página a partir de esa variable y el color del logo del
// encabezado. El logo toma su color de una clase de Tailwind (text-ink): si cambia con el tema, las clases de
// los componentes leen las variables (tailwind.config.js), no solo el fondo de la página.
//
//   npx playwright test e2e/temas.spec.ts
//
// desde frontend/, con la app y el backend levantados (ver playwright.config.ts y apoyo.ts).

// Sin animaciones, como en la prueba de accesibilidad: el contraste no se mide en medio de una transición.
test.use({ reducedMotion: 'reduce' });

interface Puesto {
  atributo: string | undefined;
  paper: string;
  fondo: string;
  logo: string;
}

/**
 * Los colores de cada tema: la variable del fondo en canales R G B, el fondo que calcula el navegador y el
 * color del logo (la variable --color-ink).
 */
const COLORES = {
  normal: { paper: '250 249 247', fondo: 'rgb(250, 249, 247)', logo: 'rgb(26, 25, 23)' },
  claro: { paper: '255 255 255', fondo: 'rgb(255, 255, 255)', logo: 'rgb(15, 18, 24)' },
  oscuro: { paper: '26 25 23', fondo: 'rgb(26, 25, 23)', logo: 'rgb(242, 240, 234)' },
} as const;

type Tema = keyof typeof COLORES;

const TEMAS = Object.keys(COLORES) as Tema[];

/** Lo que el navegador está usando ahora mismo. */
function temaPuesto(page: Page): Promise<Puesto> {
  return page.evaluate(() => {
    const logo = document.querySelector('header a[href="/"]');
    return {
      atributo: document.documentElement.dataset.tema,
      paper: getComputedStyle(document.documentElement).getPropertyValue('--color-paper').trim(),
      fondo: getComputedStyle(document.body).backgroundColor,
      logo: logo ? getComputedStyle(logo).color : 'sin logo',
    };
  });
}

const selectorDeTema = (page: Page) => page.getByRole('combobox', { name: 'Tema' });

test('temas: el selector cambia el tema en el momento y se recuerda al recargar', async ({ page }) => {
  await page.goto('/');
  const selector = selectorDeTema(page);

  // Sin nada guardado arranca el normal, el aspecto de siempre de Palco.
  await expect(selector).toHaveValue('normal');
  expect(await temaPuesto(page)).toEqual({ atributo: 'normal', ...COLORES.normal });

  // Cada opción cambia el tema sin recargar: la misma página toma los colores nuevos.
  await selector.selectOption('oscuro');
  await expect(page.locator('html')).toHaveAttribute('data-tema', 'oscuro');
  expect(await temaPuesto(page)).toEqual({ atributo: 'oscuro', ...COLORES.oscuro });

  await selector.selectOption('claro');
  await expect(page.locator('html')).toHaveAttribute('data-tema', 'claro');
  expect(await temaPuesto(page)).toEqual({ atributo: 'claro', ...COLORES.claro });

  // Al recargar sigue el último elegido, y el selector lo muestra.
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('data-tema', 'claro');
  await expect(selector).toHaveValue('claro');
  expect(await temaPuesto(page)).toEqual({ atributo: 'claro', ...COLORES.claro });

  // También se recuerda al pasar a otra página.
  await page.goto('/ingresar');
  await expect(page.locator('html')).toHaveAttribute('data-tema', 'claro');

  // Con el teclado: la flecha abajo pasa al tema siguiente de la lista.
  await selector.focus();
  await page.keyboard.press('ArrowDown');
  await expect(page.locator('html')).toHaveAttribute('data-tema', 'oscuro');
  await expect(selector).toHaveValue('oscuro');

  // Y se puede volver al normal.
  await selector.selectOption('normal');
  await page.reload();
  expect(await temaPuesto(page)).toEqual({ atributo: 'normal', ...COLORES.normal });
});

test('temas: un valor guardado que no es un tema deja el normal', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('palco.tema', 'violeta'));
  await page.goto('/ingresar');
  await expect(selectorDeTema(page)).toHaveValue('normal');
  expect(await temaPuesto(page)).toEqual({ atributo: 'normal', ...COLORES.normal });
});

/** WCAG 2.0, 2.1 y 2.2 en los niveles A y AA, con las etiquetas de axe-core, y los impactos que hacen fallar. */
const ETIQUETAS_WCAG = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'];
const IMPACTOS_QUE_FALLAN = ['serious', 'critical'];

/** Espera a que terminen las transiciones de opacidad de framer-motion. */
async function quieta(page: Page): Promise<void> {
  await page.waitForFunction(
    () => [...document.querySelectorAll<HTMLElement>('[style*="opacity"]')].every((e) => getComputedStyle(e).opacity === '1'),
    undefined,
    { timeout: 15_000 },
  );
}

test('temas: el catálogo y el ingreso pasan axe en los tres temas', async ({ page }) => {
  const pantallas = [
    { ruta: '/', listo: page.getByRole('heading', { name: 'Próximos eventos' }) },
    { ruta: '/ingresar', listo: page.getByRole('heading', { name: 'Crear cuenta' }) },
  ];

  for (const tema of TEMAS) {
    for (const pantalla of pantallas) {
      await test.step(`${pantalla.ruta} en el tema ${tema}`, async () => {
        await page.goto(pantalla.ruta);
        await expect(pantalla.listo).toBeVisible();
        await selectorDeTema(page).selectOption(tema);
        await expect(page.locator('html')).toHaveAttribute('data-tema', tema);
        await quieta(page);

        const { violations } = await new AxeBuilder({ page }).withTags(ETIQUETAS_WCAG).analyze();
        const graves = violations
          .filter((v) => IMPACTOS_QUE_FALLAN.includes(v.impact ?? ''))
          .map((v) => `${v.id} (${v.impact}): ${v.help} → ${v.nodes.slice(0, 5).map((n) => n.target.join(' ')).join(' | ')}`);
        expect.soft(graves, `${pantalla.ruta} en el tema ${tema}: axe`).toEqual([]);
      });
    }
  }
});
