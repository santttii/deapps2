import { useEffect, useState } from 'react';

// Devuelve el valor recién cuando dejó de cambiar durante demoraMs. Sirve para no pedirle
// al servidor una búsqueda por cada tecla.
export function useDebouncedValue<T>(valor: T, demoraMs: number): T {
  const [demorado, setDemorado] = useState(valor);

  useEffect(() => {
    const temporizador = setTimeout(() => setDemorado(valor), demoraMs);
    return () => clearTimeout(temporizador);
  }, [valor, demoraMs]);

  return demorado;
}
