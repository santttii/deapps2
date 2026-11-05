import { useEffect, useMemo, useState } from 'react';
import { create } from 'qrcode';
import type { Entrada, EstadoEntrada } from '../types';

// La puerta solo deja pasar una entrada VALIDA. Del resto no se muestra el código: se dice por qué
// no sirve para entrar.
const SIN_QR: Record<Exclude<EstadoEntrada, 'valida'>, string> = {
  pendiente_emision: 'La entrada se está emitiendo: el código aparece en unos segundos.',
  publicada: 'Está publicada en la reventa oficial: mientras siga publicada no sirve para entrar.',
  vendida: 'La vendiste en la reventa oficial: ahora es de otra persona.',
  usada: 'Ya se usó para entrar al evento.',
  anulada: 'Está anulada: no sirve para entrar.',
};

// Margen blanco alrededor del código, en módulos. El estándar pide cuatro para que el lector
// encuentre dónde empieza.
const MARGEN = 4;

interface QRCodeProps {
  entrada: Entrada;
  size?: number;
}

export default function QRCode({ entrada, size = 200 }: QRCodeProps) {
  if (entrada.estado === 'valida' && entrada.codigoQr) {
    return <CodigoQR entradaId={entrada.id} valor={entrada.codigoQr} size={size} />;
  }
  // Una entrada VALIDA sin código todavía no terminó de emitirse.
  const motivo = entrada.estado === 'valida' ? SIN_QR.pendiente_emision : SIN_QR[entrada.estado];
  return (
    <p
      style={{ maxWidth: size }}
      className="border border-dashed border-edge bg-fill p-4 text-sm text-graphite"
    >
      {motivo}
    </p>
  );
}

interface CodigoQRProps {
  entradaId: string;
  valor: string;
  size: number;
}

// El QR lleva el codigoQr que genera Ventas al emitir, que es lo que busca la puerta. Debajo va el
// mismo código en texto, para tipearlo si no hay lector.
function CodigoQR({ entradaId, valor, size }: CodigoQRProps) {
  const [aviso, setAviso] = useState<'' | 'copiado' | 'sin-permiso'>('');

  // Cada módulo oscuro es un cuadrado de 1×1 en un único trazo.
  const { lado, trazo } = useMemo(() => {
    const { modules } = create(valor);
    let d = '';
    for (let fila = 0; fila < modules.size; fila++) {
      for (let col = 0; col < modules.size; col++) {
        if (modules.get(fila, col)) d += `M${col + MARGEN} ${fila + MARGEN}h1v1h-1z`;
      }
    }
    return { lado: modules.size + 2 * MARGEN, trazo: d };
  }, [valor]);

  // El aviso vuelve solo a "Copiar código" a los 2 segundos. El temporizador se descarta si el
  // componente se va antes, para no actualizar un estado que ya no existe.
  useEffect(() => {
    if (!aviso) return;
    const t = setTimeout(() => setAviso(''), 2000);
    return () => clearTimeout(t);
  }, [aviso]);

  async function copiar() {
    try {
      await navigator.clipboard.writeText(valor);
      setAviso('copiado');
    } catch {
      // Sin permiso para el portapapeles queda el texto, que se selecciona entero con un clic.
      setAviso('sin-permiso');
    }
  }

  return (
    <figure className="flex flex-col gap-2" style={{ maxWidth: size }}>
      <svg
        width={size}
        height={size}
        viewBox={`0 0 ${lado} ${lado}`}
        shapeRendering="crispEdges"
        role="img"
        aria-label={`Código QR de la entrada ${entradaId}`}
      >
        <rect width={lado} height={lado} className="fill-white" />
        <path d={trazo} className="fill-ink" />
      </svg>
      <figcaption>
        <span className="font-mono-label block text-[10px] text-muted">Código para la puerta</span>
        <span className="block select-all break-all font-mono text-xs text-ink">{valor}</span>
      </figcaption>
      <button
        type="button"
        onClick={copiar}
        className="min-h-[44px] border border-edge px-3 text-xs text-ink"
      >
        <span aria-live="polite">
          {aviso === 'copiado'
            ? 'Copiado'
            : aviso === 'sin-permiso'
              ? 'No se pudo copiar: seleccioná el código'
              : 'Copiar código'}
        </span>
      </button>
    </figure>
  );
}
