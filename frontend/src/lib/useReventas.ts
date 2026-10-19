import { useCallback, useEffect, useState } from 'react';
import { mensajeDeError } from './api';
import { listarReventas } from '../servicios/reventa';
import type { Publicacion } from '../types';

export interface Reventas {
  publicaciones: Publicacion[];
  cargando: boolean;
  error: string; // '' si no hubo error
  recargar: () => void;
}

// Publicaciones activas de uno o varios eventos (GET /api/v1/reventas?eventoId=, una vez por
// evento: Reventa lista por evento). Si falla el de un evento, se muestran las de los demás y se
// informa el error.
export function useReventas(eventoIds: string[]): Reventas {
  const [publicaciones, setPublicaciones] = useState<Publicacion[]>([]);
  const [cargando, setCargando] = useState(eventoIds.length > 0);
  const [error, setError] = useState('');
  const [vueltas, setVueltas] = useState(0);
  // La lista de ids como texto, para que una lista nueva con los mismos ids no repita los pedidos.
  const clave = eventoIds.join(',');

  useEffect(() => {
    const ids = clave ? clave.split(',') : [];
    let vigente = true;
    Promise.allSettled(ids.map((id) => listarReventas(id))).then((resultados) => {
      if (!vigente) return;
      setPublicaciones(resultados.flatMap((r) => (r.status === 'fulfilled' ? r.value : [])));
      const fallo = resultados.find((r) => r.status === 'rejected');
      setError(fallo ? mensajeDeError(fallo.reason) : '');
      setCargando(false);
    });
    return () => {
      vigente = false;
    };
  }, [clave, vueltas]);

  const recargar = useCallback(() => {
    setCargando(true);
    setVueltas((n) => n + 1);
  }, []);

  return { publicaciones, cargando, error, recargar };
}
