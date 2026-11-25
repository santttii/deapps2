// Pone el tema guardado antes de que el navegador pinte la página por primera vez.
//
// La app (src/main.tsx) también lo pone, pero el bundle es un módulo diferido: en una carga en frío
// el navegador alcanza a pintar el fondo del tema normal y después cambia. Este archivo se carga como
// script común y bloqueante en el <head> de index.html, así que corre antes del primer pintado. Va en
// un archivo aparte, y no escrito dentro de la página, porque la CSP de nginx (script-src 'self') no
// deja correr scripts en línea.
//
// La clave y los valores son los de src/lib/tema.ts (CLAVE_TEMA y TEMAS): si cambian allá, cambian acá.
(function () {
  try {
    var guardado = localStorage.getItem('palco.tema');
    if (guardado === 'normal' || guardado === 'claro' || guardado === 'oscuro') {
      document.documentElement.setAttribute('data-tema', guardado);
    }
  } catch {
    // Con el almacenamiento bloqueado queda el tema normal, como en src/lib/tema.ts.
  }
})();
