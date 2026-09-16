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

export function esMayorDeEdad(fechaNacimientoISO: string, edadMinima = 16): boolean {
  const nacimiento = new Date(fechaNacimientoISO);
  if (Number.isNaN(nacimiento.getTime())) return false;
  const hoy = new Date();
  let edad = hoy.getFullYear() - nacimiento.getFullYear();
  const m = hoy.getMonth() - nacimiento.getMonth();
  if (m < 0 || (m === 0 && hoy.getDate() < nacimiento.getDate())) edad--;
  return edad >= edadMinima;
}

export function esNumeroTarjetaValido(valor: string): boolean {
  return valor.replace(/\s/g, '').length === 16;
}

export function esVencimientoValido(valor: string): boolean {
  return /^\d{2}\/\d{2}$/.test(valor);
}

export function esCVVValido(valor: string): boolean {
  return /^\d{3}$/.test(valor);
}

export function formatearNumeroTarjeta(valor: string): string {
  const digits = valor.replace(/\D/g, '').slice(0, 16);
  return digits.replace(/(.{4})/g, '$1 ').trim();
}
