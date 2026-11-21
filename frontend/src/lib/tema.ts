// Temas visuales de Palco: el normal (el aspecto de siempre), uno claro y uno oscuro.
//
// Cada tema es un juego de valores para las variables de color de index.css. El tema puesto es
// el atributo data-tema de <html>, y las reglas de index.css lo leen de ahí: cambiar de tema es
// cambiar ese atributo, sin recargar la página ni volver a dibujar los componentes.
//
// La elección se guarda en localStorage. Es una preferencia de este navegador, no de la cuenta:
// vale también sin haber ingresado y no viaja al backend.

export type Tema = 'normal' | 'claro' | 'oscuro';

/** Los temas en el orden en que los ofrece el selector del encabezado. */
export const TEMAS: readonly { valor: Tema; nombre: string }[] = [
  { valor: 'normal', nombre: 'Normal' },
  { valor: 'claro', nombre: 'Claro' },
  { valor: 'oscuro', nombre: 'Oscuro' },
];

export const CLAVE_TEMA = 'palco.tema';

export function esTema(valor: unknown): valor is Tema {
  return TEMAS.some((t) => t.valor === valor);
}

/** El tema guardado; el normal si no hay ninguno, si lo guardado no es un tema o si no se puede leer. */
export function temaGuardado(): Tema {
  try {
    const guardado = localStorage.getItem(CLAVE_TEMA);
    return esTema(guardado) ? guardado : 'normal';
  } catch {
    // Con el almacenamiento bloqueado (por ejemplo, si el navegador no deja guardar datos de
    // sitios), leer tira una excepción.
    return 'normal';
  }
}

/** El tema que está puesto ahora en la página. */
export function temaActual(): Tema {
  const puesto = document.documentElement.dataset.tema;
  return esTema(puesto) ? puesto : 'normal';
}

/** Pone el tema en la página, sin guardarlo. */
export function aplicarTema(tema: Tema): void {
  document.documentElement.dataset.tema = tema;
}

/** Pone el tema y lo guarda para la próxima vez que se abra Palco en este navegador. */
export function elegirTema(tema: Tema): void {
  aplicarTema(tema);
  try {
    localStorage.setItem(CLAVE_TEMA, tema);
  } catch {
    // Sin almacenamiento el tema queda puesto igual, pero solo hasta recargar la página.
  }
}
