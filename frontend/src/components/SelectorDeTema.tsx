import { useId, useState } from 'react';
import { TEMAS, elegirTema, esTema, temaActual, type Tema } from '../lib/tema';

// El selector de tema del encabezado. Es una lista nativa (<select>) con su etiqueta visible: se
// maneja con el teclado y el lector de pantalla la anuncia con su nombre, "Tema", y el tema elegido,
// sin atributos ARIA extra. El cambio se ve al instante, porque solo cambia el atributo data-tema
// de <html> (ver src/lib/tema.ts).
export default function SelectorDeTema() {
  const id = useId();
  // El tema ya lo puso main.tsx antes de montar la app: el selector arranca mostrando ese.
  const [tema, setTema] = useState<Tema>(temaActual);

  function cambiar(valor: string) {
    if (!esTema(valor)) return;
    elegirTema(valor);
    setTema(valor);
  }

  return (
    <div className="flex items-center gap-2">
      <label htmlFor={id} className="font-mono-label text-[11px] text-muted">
        Tema
      </label>
      <select
        id={id}
        value={tema}
        onChange={(e) => cambiar(e.target.value)}
        className="font-mono-label min-h-[36px] border border-edge bg-paper px-2 text-[11px] text-ink"
      >
        {TEMAS.map((t) => (
          <option key={t.valor} value={t.valor}>
            {t.nombre}
          </option>
        ))}
      </select>
    </div>
  );
}
