import AxeBuilder from '@axe-core/playwright';
import { test, expect, type Page, type Route } from '@playwright/test';

const dias = (n: number) => `${new Date(Date.now() + n * 864e5).toISOString().slice(0, 10)}T21:00:00`;
const sectores = (id: string) => [
  { id: `${id}-s1`, nombre: 'Campo', precio: 20000, cupo: 100, vendidas: 10 },
  { id: `${id}-s2`, nombre: 'Platea', precio: 35000, cupo: 50, vendidas: 50 },
];
const EVENTOS = [
  { id: 'ev-1', slug: 'gran-concierto', titulo: 'Gran concierto de prueba', categoria: 'Música', venue: 'Estadio de pruebas, Rosario', fechaISO: dias(30), descripcion: 'Un concierto.', sectores: sectores('ev-1'), destacado: true, imagenUrl: null, cancelado: false, cancelacionMotivo: null },
  { id: 'ev-2', slug: 'obra-de-teatro', titulo: 'Obra de teatro', categoria: 'Teatro', venue: 'Sala de pruebas', fechaISO: dias(40), descripcion: 'Una obra.', sectores: sectores('ev-2'), destacado: false, imagenUrl: null, cancelado: false, cancelacionMotivo: null },
  { id: 'ev-3', slug: 'partido', titulo: 'Partido de prueba', categoria: 'Deportes', venue: 'Cancha', fechaISO: dias(50), descripcion: 'Un partido.', sectores: sectores('ev-3'), destacado: false, imagenUrl: null, cancelado: false, cancelacionMotivo: null },
];
const USUARIOS = {
  user: { id: 'u1', nombre: 'Martina', apellido: 'Suárez', dni: '29888777', email: 'titular@palco.test', rol: 'user' },
  organizer: { id: 'u2', nombre: 'Cuenta', apellido: 'Organizadora', dni: '27444555', email: 'organizador@palco.test', rol: 'organizer' },
  staff: { id: 'u3', nombre: 'Cuenta', apellido: 'de Puerta', dni: '29666777', email: 'puerta@palco.test', rol: 'staff' },
};
const VENTA = {
  id: 'V-1', estado: 'EMITIDA', eventoId: 'ev-1', items: [{ sectorId: 'ev-1-s1', cantidad: 1, precioUnitario: 20000 }],
  subtotal: 20000, cargoServicio: 2000, montoTotal: 22000, creada: new Date().toISOString(), venceReserva: new Date().toISOString(),
  entradas: [{ id: 'E-1', sectorId: 'ev-1-s1', precioPagado: 20000, titular: { nombre: 'Martina Suárez', dni: '29888777' }, estado: 'VALIDA', codigoQr: 'abc123' }],
  _links: {},
};
const PUBLICACION = { id: 'P-1', entradaId: 'E-9', eventoId: 'ev-2', sectorId: 'ev-2-s1', precio: 21000, precioMaximo: 22000, publicada: new Date().toISOString() };

async function api(page: Page) {
  await page.route(/fonts\.(googleapis|gstatic)\.com/, (r) => r.abort());
  await page.route('**/api/**', async (route: Route) => {
    const url = new URL(route.request().url());
    const p = url.pathname;
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (p === '/api/eventos') return json(EVENTOS);
    if (p === '/api/eventos/mios') return json(EVENTOS.slice(0, 2));
    if (p.startsWith('/api/eventos/')) return json(EVENTOS.find((e) => e.slug === p.split('/')[3]) ?? EVENTOS[0]);
    if (p === '/api/v1/reventas') return json(url.searchParams.get('eventoId') === 'ev-2' ? [PUBLICACION] : []);
    if (p === '/api/v1/reventas/mias') return json([]);
    if (p === '/api/v1/ventas') return json([VENTA]);
    if (p === '/api/v1/resumenes') return json({ liquidacion: { ventas: 3, entradas: 5, recaudadoDeLosCompradores: 110000, cargoDePalco: 10000, aLiquidarAlOrganizador: 100000 }, porDia: [] });
    if (p === '/api/v1/validacion/puertas/mias') return json([{ eventoId: 'ev-1', staffEmail: 'puerta@palco.test', asignadaPor: 'u2', asignada: new Date().toISOString() }]);
    if (p === '/api/v1/validacion/puertas') return json([]);
    if (p === '/api/v1/validacion/escaneos') return json([]);
    if (p.startsWith('/api/v1/facturas/')) return json({ title: 'No existe', status: 404 }, 404);
    console.log('sin simular', route.request().method(), p);
    return json({ title: 'No simulado', status: 404 }, 404);
  });
}

async function conSesion(page: Page, rol: keyof typeof USUARIOS) {
  await page.addInitScript((usuario) => {
    localStorage.setItem('palco.sesion', JSON.stringify({ token: 't', expiraEn: Date.now() + 36e5, usuario }));
  }, USUARIOS[rol]);
  await page.route('**/api/usuarios/yo', (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(USUARIOS[rol]) }));
}

test.use({ reducedMotion: 'reduce' });

const ANCHOS = [{ ancho: 360, alto: 800 }, { ancho: 768, alto: 1024 }, { ancho: 1280, alto: 800 }];
const PANTALLAS: { rol?: keyof typeof USUARIOS; nombre: string; ruta: string; listo: (p: Page) => Promise<void> }[] = [
  { nombre: 'catálogo', ruta: '/', listo: async (p) => { await expect(p.getByRole('heading', { name: 'Próximos eventos' })).toBeVisible(); } },
  { nombre: 'evento', ruta: '/evento/gran-concierto', listo: async (p) => { await expect(p.getByRole('heading', { name: 'Mapa de sectores' })).toBeVisible(); } },
  { nombre: 'ingresar', ruta: '/ingresar', listo: async (p) => { await expect(p.getByRole('heading', { name: 'Ya tengo cuenta' })).toBeVisible(); } },
  { nombre: 'registro', ruta: '/registro', listo: async (p) => { await expect(p.getByRole('heading', { name: 'Crear cuenta' })).toBeVisible(); } },
  { nombre: 'reventa', ruta: '/reventa', listo: async (p) => { await expect(p.getByText(/^\d+ publicaci(ón|ones)$/)).toBeVisible(); } },
  { rol: 'user', nombre: 'mis entradas', ruta: '/mis-entradas', listo: async (p) => { await expect(p.getByRole('img', { name: /^Código QR de la entrada / })).toBeVisible(); } },
  { rol: 'user', nombre: 'asistente', ruta: '/asistente', listo: async (p) => { await expect(p.getByRole('heading', { name: 'Asistente de Palco' })).toBeVisible(); } },
  { rol: 'organizer', nombre: 'organizador', ruta: '/organizador', listo: async (p) => { await expect(p.getByRole('region', { name: 'Tabla de mis eventos' })).toBeVisible(); } },
  { rol: 'organizer', nombre: 'alta de evento', ruta: '/organizador', listo: async (p) => { await expect(p.getByRole('region', { name: 'Tabla de mis eventos' })).toBeVisible(); await p.getByRole('button', { name: '+ Crear evento' }).click(); await expect(p.getByRole('dialog', { name: 'Crear evento' })).toBeVisible(); } },
  { rol: 'staff', nombre: 'puerta', ruta: '/puerta', listo: async (p) => { await expect(p.getByLabel('Código QR')).toBeVisible(); } },
];

for (const pantalla of PANTALLAS) {
  test(pantalla.nombre, async ({ page }) => {
    await api(page);
    if (pantalla.rol) await conSesion(page, pantalla.rol);
    const salida: string[] = [];
    for (const { ancho, alto } of ANCHOS) {
      await page.setViewportSize({ width: ancho, height: alto });
      await page.goto(pantalla.ruta);
      await pantalla.listo(page);
      await page.waitForFunction(() => [...document.querySelectorAll<HTMLElement>('[style*="opacity"]')].every((e) => getComputedStyle(e).opacity === '1'));
      const m = await page.evaluate(() => {
        const v = window.innerWidth;
        return { pagina: document.documentElement.scrollWidth, v, salen: [...document.querySelectorAll('body *')].filter((e) => e.getBoundingClientRect().right > v + 1).slice(0, 6).map((e) => [e.tagName.toLowerCase(), ...[...e.classList].slice(0, 4)].join('.')) };
      });
      const { violations } = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
      salida.push(`${ancho}px ancho=${m.pagina}/${m.v}${m.pagina > m.v ? ` SALEN: ${m.salen.join(', ')}` : ''}`);
      for (const v of violations) salida.push(`   ${v.impact} ${v.id}: ${v.help} -> ${v.nodes.slice(0, 4).map((n) => `${n.target.join(' ')} [${n.failureSummary?.split('\n')[1]?.trim() ?? ''}]`).join(' | ')}`);
      await page.screenshot({ path: `e2e-borrador/capturas/${pantalla.nombre}-${ancho}.png`, fullPage: true });
    }
    console.log(`== ${pantalla.nombre}\n${salida.join('\n')}`);
  });
}
