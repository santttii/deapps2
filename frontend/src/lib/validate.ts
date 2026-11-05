export function esNombreValido(valor: string): boolean {
  return /^[A-Za-zÀ-ÿ' -]{2,}$/.test(valor.trim());
}

export function esDNIValido(valor: string): boolean {
  const digits = valor.replace(/\D/g, '');
  return /^\d{7,8}$/.test(digits);
}

export function soloDigitosDNI(valor: string): string {
  return valor.replace(/\D/g, '').slice(0, 8);
}

export function esEmailValido(valor: string): boolean {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(valor.trim());
}

export function esPasswordValida(valor: string): boolean {
  return valor.length >= 8;
}

export function esPasswordFuerte(valor: string): boolean {
  return valor.length >= 8 && /[A-Za-z]/.test(valor) && /\d/.test(valor);
}

export function fuerzaPassword(valor: string): 0 | 1 | 2 | 3 {
  let score = 0;
  if (valor.length >= 8) score++;
  if (/[A-Za-z]/.test(valor) && /\d/.test(valor)) score++;
  if (valor.length >= 12 || /[^A-Za-z0-9]/.test(valor)) score++;
  return score as 0 | 1 | 2 | 3;
}

// La fecha se compara por año, mes y día, sin pasar por Date: new Date('AAAA-MM-DD') es la
// medianoche en UTC, que en Argentina todavía es el día anterior.
export function esMayorDeEdad(fechaNacimientoISO: string, edadMinima = 16): boolean {
  const partes = /^(\d{4})-(\d{2})-(\d{2})$/.exec(fechaNacimientoISO);
  if (!partes) return false;
  const [anio, mes, dia] = partes.slice(1).map(Number);
  const hoy = new Date();
  let edad = hoy.getFullYear() - anio;
  const m = hoy.getMonth() + 1 - mes;
  if (m < 0 || (m === 0 && hoy.getDate() < dia)) edad--;
  return edad >= edadMinima;
}

// El teléfono es opcional: vacío vale. Si viene, son dígitos, espacios y guiones, con un + adelante.
export function esTelefonoValido(valor: string): boolean {
  const telefono = valor.trim();
  return telefono === '' || /^\+?\d[\d -]*$/.test(telefono);
}

export function esNumeroTarjetaValido(valor: string): boolean {
  return valor.replace(/\s/g, '').length === 16;
}

// MM/AA con un mes de 01 a 12, y no anterior al mes actual: la tarjeta vence al terminar ese mes.
export function esVencimientoValido(valor: string): boolean {
  const partes = /^(\d{2})\/(\d{2})$/.exec(valor);
  if (!partes) return false;
  const mes = Number(partes[1]);
  const anio = 2000 + Number(partes[2]);
  if (mes < 1 || mes > 12) return false;
  const hoy = new Date();
  return anio > hoy.getFullYear() || (anio === hoy.getFullYear() && mes >= hoy.getMonth() + 1);
}

// Para el parámetro next: solo una ruta interna simple (/checkout, /confirmacion/A-12345). Así un
// enlace armado por otra persona no puede mandar a otro sitio después de ingresar o registrarse.
export function esRutaInterna(valor: string | null): valor is string {
  return valor !== null && /^(\/[A-Za-z0-9_-]+)+$/.test(valor);
}

export function esCVVValido(valor: string): boolean {
  return /^\d{3}$/.test(valor);
}

export function formatearNumeroTarjeta(valor: string): string {
  const digits = valor.replace(/\D/g, '').slice(0, 16);
  return digits.replace(/(.{4})/g, '$1 ').trim();
}
