import { defineConfig, devices } from '@playwright/test';

// Pruebas E2E de Palco: recorren la aplicación en Chromium contra el backend de verdad.
//
//   npm run e2e                                    contra el servidor de desarrollo de Vite
//   PALCO_E2E_URL=https://localhost npm run e2e    contra el docker compose (así corre en el CI)
//
// Sin PALCO_E2E_URL se usa http://localhost:5173: si ya hay un `npm run dev` andando se reutiliza y,
// si no, se levanta uno. Tiene que ser ese origen exacto porque el backend solo acepta pedidos de
// los orígenes de PALCO_CORS_ORIGENES; por eso va --strictPort: si el 5173 está ocupado por otra cosa,
// Vite no se corre solo a otro puerto que el backend rechazaría.
//
// Las pruebas necesitan el backend levantado (backend/dev.sh o el compose) y leer su server.log, donde
// queda el código de verificación de cada cuenta nueva. Las variables están explicadas en e2e/apoyo.ts.
// Cada prueba crea sus propios datos (eventos, cuentas, compras) con nombres únicos, así se pueden
// correr contra un servidor que otras personas están usando.

const URL_DE_LA_APP = process.env.PALCO_E2E_URL?.replace(/\/$/, '');
const SERVIDOR_DE_DESARROLLO = 'http://localhost:5173';

export default defineConfig({
  testDir: './e2e',
  // Una compra completa pasa por Ventas, Pagos y la emisión asincrónica de las entradas: lleva varios
  // segundos, más en el CI, donde el backend recién arrancó.
  timeout: 90_000,
  expect: { timeout: 15_000 },
  // El backend es uno solo y compartido: más navegadores a la vez compiten por el mismo WildFly
  // (cada login cuesta un hash de contraseña) y no acortan la corrida.
  workers: 2,
  // En el CI un reintento separa una falla real de una demora del contenedor; el reporte marca
  // como "flaky" la prueba que pasó recién en el segundo intento.
  retries: process.env.CI ? 1 : 0,
  forbidOnly: !!process.env.CI,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: URL_DE_LA_APP ?? SERVIDOR_DE_DESARROLLO,
    // El compose sirve HTTPS con un certificado autofirmado que se genera al arrancar.
    ignoreHTTPSErrors: true,
    // Las fechas de los eventos son hora de Argentina sin zona: el navegador va en esa zona y en
    // español, como el de quien usa Palco, así los textos con fechas y montos no dependen de la máquina.
    locale: 'es-AR',
    timezoneId: 'America/Argentina/Buenos_Aires',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: URL_DE_LA_APP
    ? undefined
    : {
        command: 'npm run dev -- --strictPort',
        url: SERVIDOR_DE_DESARROLLO,
        reuseExistingServer: true,
        timeout: 120_000,
      },
});
