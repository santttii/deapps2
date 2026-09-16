import { useEffect, useRef, useState } from 'react';

export function useAnimatedNumber(valor: number, duracionMs = 300): number {
  const [mostrado, setMostrado] = useState(valor);
  const desde = useRef(valor);

  useEffect(() => {
    const inicioValor = desde.current;
    const delta = valor - inicioValor;
    if (delta === 0) return;
    let frame: number;
    let inicio: number | null = null;
    const paso = (t: number) => {
      if (inicio === null) inicio = t;
      const progreso = Math.min(1, (t - inicio) / duracionMs);
      setMostrado(Math.round(inicioValor + delta * progreso));
      if (progreso < 1) {
        frame = requestAnimationFrame(paso);
      } else {
        desde.current = valor;
      }
    };
    frame = requestAnimationFrame(paso);
    return () => cancelAnimationFrame(frame);
  }, [valor, duracionMs]);

  return mostrado;
}
