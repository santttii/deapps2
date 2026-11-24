import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import type { Page } from '@playwright/test';
import { PASSWORD, expect, ingresar, marcaDelLog, menu, sufijo, test } from './apoyo';

// Recuperar la contraseña (HU-03) en dos pasos: el email, al que llega un código de 6 dígitos, y la
// contraseña nueva con ese código. Como en el registro, sin SMTP el servidor deja el email en su log
// (con PALCO_CORREO_LOG_COMPLETO=true, que prende dev.sh; el compose, solo si se lo levanta con esa
// variable, como en el job E2E del CI) y de ahí lo lee la prueba, como
// lo haría la persona en su casilla. Cada prueba usa una cuenta nueva: cambiarle la contraseña a una
// cuenta de prueba de las migraciones rompería las demás pruebas.

const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

// El mismo server.log que lee apoyo.ts para el código de verificación.
const LOG = process.env.PALCO_LOG
  ? path.resolve(process.env.PALCO_LOG)
  : path.join(REPO, 'backend', 'servidor', 'target', 'server', 'standalone', 'log', 'server.log');

const PASSWORD_NUEVA = 'nueva12345';

/** Lo que el servidor escribió en el log desde la marca. Si el log rotó, el archivo nuevo se lee entero. */
function logDesde(marca: number): string {
  const tamano = fs.statSync(LOG).size;
  const desde = tamano < marca ? 0 : marca;
  const fd = fs.openSync(LOG, 'r');
  try {
    const buffer = Buffer.alloc(tamano - desde);
    fs.readSync(fd, buffer, 0, buffer.length, desde);
    return buffer.toString('utf8');
  } finally {
    fs.closeSync(fd);
  }
}

function renglonDelEmail(email: string): RegExp {
  return new RegExp(`Email para ${email.replace(/[.+]/g, '\\$&')} .*contraseña nueva es (\\d{6})\\.`);
}

/**
 * El código de reinicio que le mandó el servidor a ese email. En el log queda en un renglón
 * "Email para <email> (Volvé a poner tu contraseña de Palco): ... una contraseña nueva es 123456. ..."
 */
async function codigoDeReinicio(email: string, marca: number): Promise<string> {
  const patron = renglonDelEmail(email);
  const limite = Date.now() + 15_000;
  while (Date.now() < limite) {
    const encontrado = patron.exec(logDesde(marca));
    if (encontrado) return encontrado[1];
    await new Promise((listo) => setTimeout(listo, 300));
  }
  throw new Error(`El código de reinicio de ${email} no apareció en ${LOG}.`);
}

/** Lo que muestra el segundo paso, exista o no la cuenta. */
const respuestaDelPedido = (email: string) => `Si ${email} tiene una cuenta en Palco, te enviamos un código de 6 dígitos.`;

const enviarCodigo = (page: Page) =>
  page.getByRole('button', { name: 'Enviar código', exact: true }).click();

async function elegirPassword(page: Page, codigo: string): Promise<void> {
  await page.getByLabel('Código de 6 dígitos').fill(codigo);
  await page.getByLabel('Contraseña nueva', { exact: true }).fill(PASSWORD_NUEVA);
  await page.getByLabel('Repetir contraseña nueva').fill(PASSWORD_NUEVA);
  await page.getByRole('button', { name: 'Cambiar contraseña', exact: true }).click();
}

test('CP-03.1.1 recuperar: pide el código por email, elige una contraseña nueva e ingresa con ella', async ({ page, api }) => {
  const cuenta = await api.cuentaNueva();

  await page.goto('/ingresar');
  await page.getByRole('link', { name: '¿Olvidaste tu contraseña?' }).click();
  await expect(page).toHaveURL(/\/recuperar$/);
  await expect(page.getByRole('heading', { name: 'Recuperar la contraseña' })).toBeVisible();

  await page.getByLabel('Email').fill(cuenta.email);
  const marca = marcaDelLog();
  await enviarCodigo(page);
  await expect(page.getByText(respuestaDelPedido(cuenta.email))).toBeVisible();
  // El foco pasa solo al campo del código, que es lo que sigue.
  await expect(page.getByLabel('Código de 6 dígitos')).toBeFocused();

  const codigo = await codigoDeReinicio(cuenta.email, marca);
  await elegirPassword(page, codigo);

  // El reinicio no abre sesión: vuelve al ingreso con el aviso y el email ya escrito.
  await expect(page).toHaveURL(/\/ingresar$/);
  await expect(page.getByText('Listo: cambiaste la contraseña. Ingresá con la nueva.')).toBeVisible();
  await expect(page.getByLabel('Email')).toHaveValue(cuenta.email);
  await expect(page.getByLabel('Contraseña', { exact: true })).toBeFocused();

  await ingresar(page, cuenta.email, PASSWORD_NUEVA);
  await expect(menu(page).getByRole('link', { name: 'Mis entradas' })).toBeVisible();

  // La contraseña de antes ya no sirve.
  await expect(api.token(cuenta.email, PASSWORD)).rejects.toThrow(/respondió 401/);
});

test('CP-03.2.1 recuperar: un email sin cuenta recibe la misma respuesta y no se le manda nada', async ({ page }) => {
  const email = `nadie.${sufijo()}@palco.test`;

  await page.goto('/recuperar');
  await page.getByLabel('Email').fill(email);
  const marca = marcaDelLog();
  await enviarCodigo(page);

  // El mismo texto que con una cuenta existente: la pantalla no revela qué emails están registrados.
  await expect(page.getByText(respuestaDelPedido(email))).toBeVisible();
  await expect(page.getByLabel('Código de 6 dígitos')).toBeVisible();
  await expect(page.getByRole('alert')).toHaveCount(0);
  // El email se manda dentro del mismo pedido: con la respuesta ya en pantalla, estaría en el log.
  expect(logDesde(marca)).not.toContain(`Email para ${email}`);
});

test('CP-03.3.1 recuperar: con un código distinto del que llegó avisa en el campo y la contraseña no cambia', async ({ page, api }) => {
  const cuenta = await api.cuentaNueva();

  await page.goto('/recuperar');
  await page.getByLabel('Email').fill(cuenta.email);
  const marca = marcaDelLog();
  await enviarCodigo(page);
  await expect(page.getByText(respuestaDelPedido(cuenta.email))).toBeVisible();

  // Distinto del que llegó: el mismo código con el último dígito cambiado.
  const codigo = await codigoDeReinicio(cuenta.email, marca);
  const otroCodigo = codigo.slice(0, 5) + String((Number(codigo[5]) + 1) % 10);
  await elegirPassword(page, otroCodigo);

  // El 400 del backend (Problem Details con el error en "codigo") se muestra debajo del campo, que se
  // vacía y recibe el foco para escribir el código bien.
  const campo = page.getByLabel('Código de 6 dígitos');
  await expect(page.getByRole('alert')).toHaveText('El código no es correcto.');
  await expect(campo).toHaveValue('');
  await expect(campo).toBeFocused();
  await expect(page).toHaveURL(/\/recuperar$/);

  // La contraseña de antes sigue sirviendo.
  await expect(api.token(cuenta.email, PASSWORD)).resolves.toBeTruthy();
});

test('recuperar: los dos pasos entran a 360, 768 y 1280 px sin desborde horizontal', async ({ page }) => {
  const anchoDeLaPagina = () =>
    page.evaluate(() => ({ pagina: document.documentElement.scrollWidth, visible: window.innerWidth }));

  for (const ancho of [360, 768, 1280]) {
    await page.setViewportSize({ width: ancho, height: 800 });
    // Una dirección distinta en cada ancho: la pantalla guarda el paso en el historial (así recargar no obliga a
    // pedir otro código), y volver a la misma dirección el navegador lo toma como recargar y quedaría en el paso 2.
    await page.goto(`/recuperar?ancho=${ancho}`);
    await expect(page.getByRole('heading', { name: 'Recuperar la contraseña' })).toBeVisible();
    const primero = await anchoDeLaPagina();
    expect.soft(primero.pagina, `primer paso a ${ancho} px`).toBeLessThanOrEqual(primero.visible);

    await page.getByLabel('Email').fill(`nadie.${sufijo()}@palco.test`);
    await enviarCodigo(page);
    await expect(page.getByRole('button', { name: 'Cambiar contraseña', exact: true })).toBeVisible();
    const segundo = await anchoDeLaPagina();
    expect.soft(segundo.pagina, `segundo paso a ${ancho} px`).toBeLessThanOrEqual(segundo.visible);
  }
});
