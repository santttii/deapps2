// Lighthouse CI de Palco: accesibilidad y buenas prácticas de las pantallas públicas principales, contra el
// sistema levantado con docker compose (la app por nginx en https://localhost, con el certificado autofirmado
// que se genera al arrancar el contenedor). Lo corre .github/workflows/lighthouse.yml. A mano se corre igual, en
// Git Bash, desde la raíz del repo y con el compose levantado (un perfil por vez):
//
//   mkdir -p logs/lighthouse/mobile && cd logs/lighthouse/mobile
//   PALCO_LIGHTHOUSE_PERFIL=mobile npx --yes @lhci/cli@0.16.0 autorun --config=../../../.lighthouserc.cjs
//
// (y lo mismo con desktop). Se corre desde logs/lighthouse/<perfil> porque lhci deja sus archivos de trabajo
// (.lighthouseci/) en la carpeta desde la que se lo llama, y logs/ está ignorada por git.
//
// Variables:
//   PALCO_LIGHTHOUSE_PERFIL  mobile (por defecto) o desktop: qué dispositivo emula Lighthouse.
//   PALCO_LIGHTHOUSE_URL     dónde está la app; por defecto https://localhost.
//   CHROME_PATH              el navegador, si la máquina no tiene Chrome instalado: sirve el Chromium que
//                            instala Playwright para las E2E (npx playwright install chromium).
//
// Criterio de la cátedra (eje A): Lighthouse o axe >= 90 en accesibilidad y sin violaciones críticas
// (WCAG 2.2 AA). Lo aplican las aserciones de abajo: si no se cumple en alguna pantalla, autorun termina con
// error. Los reportes HTML y JSON de cada pantalla, con un manifest.json que resume los puntajes, quedan en
// logs/lighthouse/<perfil>/. El responsive a 360, 768 y 1280 px con axe-core lo prueba
// frontend/e2e/accesibilidad.spec.ts.
//
// Es .cjs y no .json porque el perfil y la dirección salen de variables de entorno, y porque así cada valor
// lleva su porqué.

'use strict';

const path = require('node:path');

// Los dos perfiles. Lighthouse emula por defecto un teléfono de 412 px y su preset desktop, una pantalla de
// 1350 px: los anchos se llevan a 360 y 1280 px, el más angosto y el más ancho de los que pide la cátedra.
const PERFILES = {
  // Teléfono: pantalla táctil, user agent de celular y red y CPU limitadas (lo de siempre de Lighthouse).
  mobile: {
    formFactor: 'mobile',
    screenEmulation: { mobile: true, width: 360, height: 800, deviceScaleFactor: 2, disabled: false },
  },
  // El preset desktop de Lighthouse: sin emulación táctil, user agent de escritorio, sin limitar la CPU.
  desktop: {
    preset: 'desktop',
    screenEmulation: { mobile: false, width: 1280, height: 800, deviceScaleFactor: 1, disabled: false },
  },
};

// Las pantallas públicas principales: se abren sin sesión, que es como llega quien entra por primera vez.
const PANTALLAS = [
  '/', // catálogo
  '/evento/atletico-norte-racing-sur', // detalle: el evento destacado de los datos iniciales (Eventos, migración V2)
  '/ingresar',
  '/registro',
  '/recuperar', // recuperar la contraseña
];

// Las auditorías de accesibilidad de Lighthouse que axe-core clasifica con impacto crítico (las de peso 10 en
// la categoría). El puntaje de la categoría es un promedio ponderado: podría dar 90 o más con una de estas
// falladas. Así, una violación crítica en cualquier pantalla hace fallar la corrida aunque el puntaje alcance.
// Una auditoría que no aplica a la pantalla (por ejemplo, video-caption sin videos) cuenta como aprobada.
const AUDITORIAS_CRITICAS = [
  'aria-allowed-attr',
  'aria-hidden-body',
  'aria-required-attr',
  'aria-required-children',
  'aria-required-parent',
  'aria-roles',
  'aria-valid-attr',
  'aria-valid-attr-value',
  'button-name',
  'duplicate-id-aria',
  'image-alt',
  'input-button-name',
  'input-image-alt',
  'label',
  'meta-refresh',
  'meta-viewport',
  'select-name',
  'video-caption',
];

const nombreDelPerfil = process.env.PALCO_LIGHTHOUSE_PERFIL || 'mobile';
if (!Object.hasOwn(PERFILES, nombreDelPerfil)) {
  throw new Error(`PALCO_LIGHTHOUSE_PERFIL tiene que ser mobile o desktop, no "${nombreDelPerfil}".`);
}
const urlDeLaApp = process.env.PALCO_LIGHTHOUSE_URL || 'https://localhost';

module.exports = {
  ci: {
    collect: {
      url: PANTALLAS.map((ruta) => new URL(ruta, urlDeLaApp).href),
      // Una corrida por pantalla: accesibilidad y buenas prácticas se calculan sobre la página ya cargada, no
      // con tiempos, que es lo que cambia de una corrida a otra.
      numberOfRuns: 1,
      settings: {
        ...PERFILES[nombreDelPerfil],
        // Solo las dos categorías que se evalúan. El rendimiento medido en un runner compartido no es estable;
        // el del backend lo mide la prueba de carga (scripts/carga).
        onlyCategories: ['accessibility', 'best-practices'],
        // --ignore-certificate-errors: el certificado de nginx es autofirmado, y sin esto Chrome no abre la página.
        // --no-sandbox: como lanza Playwright el Chromium de las E2E; el runner de Linux no siempre deja usar el
        // sandbox de Chrome, y este navegador solo abre el sistema de prueba.
        chromeFlags: '--ignore-certificate-errors --no-sandbox',
      },
    },
    assert: {
      assertions: {
        'categories:accessibility': ['error', { minScore: 0.9 }],
        // Advertencia: queda en el log y en el reporte, pero no frena la corrida.
        'categories:best-practices': ['warn', { minScore: 0.9 }],
        ...Object.fromEntries(AUDITORIAS_CRITICAS.map((id) => [id, ['error', { minScore: 1 }]])),
      },
    },
    upload: {
      // Los reportes quedan en disco (y en el artefacto del workflow): no se mandan a ningún servidor externo.
      // La ruta sale de este archivo y no de la carpeta desde la que se corre: siempre es logs/lighthouse/<perfil>.
      target: 'filesystem',
      outputDir: path.join(__dirname, 'logs', 'lighthouse', nombreDelPerfil),
    },
  },
};
