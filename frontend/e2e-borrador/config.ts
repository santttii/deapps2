import { defineConfig, devices } from '@playwright/test';

// Borrador temporal: axe contra el servidor de Vite con la API simulada. Se borra al terminar.
export default defineConfig({
  testDir: '.',
  timeout: 300_000,
  workers: 1,
  reporter: [['list']],
  use: { baseURL: 'http://localhost:5199', locale: 'es-AR', timezoneId: 'America/Argentina/Buenos_Aires' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npx vite --port 5199 --strictPort',
    url: 'http://localhost:5199',
    reuseExistingServer: false,
    timeout: 120_000,
    env: { VITE_API_URL: 'http://localhost:5199' },
  },
});
