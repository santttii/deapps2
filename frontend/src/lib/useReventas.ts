import { useCallback, useEffect, useState } from 'react';
import { mensajeDeError } from './api';
import { listarMisPublicaciones, listarReventas } from '../servicios/reventa';
import type { Publicacion } from '../types';

export interface Reventas {
  publicaciones: Publicacion[];
  // Ids de las publicaciones del usuario de la sesión. Vacío si no se pidieron.
  propias: ReadonlySet<string>;
  cargando: boolean;
  error: string; // '' si no hubo error
  recargar: () => void;
}

interface Respuesta {
  pedido: string; // el pedido al que responde (ver abajo)
  publicaciones: Publicacion[];
  propias: ReadonlySet<string>;
  error: string;
}

const SIN_PUBLICACIONES: Publicacion[] = [];
const SIN_PROPIAS: ReadonlySet<string> = new Set();

// Publicaciones activas de uno o varios eventos (GET /api/v1/reventas?eventoId=, una vez por
// evento: Reventa lista por evento). Si falla el de un evento, se muestran las de los demás y se
// informa el error.
//
// El listado no dice quién vende. Con conPropias (solo para una sesión con rol user, el único que
// puede pedir /mias) se piden también las del usuario, para no ofrecerle comprar las suyas.
export function useReventas(eventoIds: string[], conPropias = false): Reventas {
  const [respuesta, setRespuesta] = useState<Respuesta | null>(null);
  const [vueltas, setVueltas] = useState(0);
  // La lista de ids como texto, para que una lista nueva con los mismos ids no repita los pedidos.
  const clave = eventoIds.join(',');
  // Si cambian los eventos o la sesión, o se pide recargar, es otro pedido. Mientras la respuesta
  // que hay sea de un pedido anterior, se está cargando: también cuando los eventos llegan después
  // de montar la pantalla, y así no se muestra "no hay publicaciones" antes de preguntar.
  const pedido = `${clave}|${conPropias}|${vueltas}`;

  useEffect(() => {
    const ids = clave ? clave.split(',') : [];
    let vigente = true;
    // Si /mias falla no se informa: las publicaciones se ven igual y, si la persona intenta comprar
    // una suya, el backend la rechaza con un mensaje claro.
    const pedidoDePropias: Promise<Publicacion[]> = conPropias
      ? listarMisPublicaciones().catch(() => [])
      : Promise.resolve([]);
    Promise.all([Promise.allSettled(ids.map((id) => listarReventas(id))), pedidoDePropias]).then(
      ([resultados, mias]) => {
        if (!vigente) return;
        const fallo = resultados.find((r) => r.status === 'rejected');
        setRespuesta({
          pedido,
          publicaciones: resultados.flatMap((r) => (r.status === 'fulfilled' ? r.value : [])),
          propias: mias.length > 0 ? new Set(mias.map((p) => p.id)) : SIN_PROPIAS,
          error: fallo ? mensajeDeError(fallo.reason) : '',
        });
      },
    );
    return () => {
      vigente = false;
    };
  }, [clave, conPropias, pedido]);

  const recargar = useCallback(() => setVueltas((n) => n + 1), []);

  return {
    publicaciones: respuesta?.publicaciones ?? SIN_PUBLICACIONES,
    propias: respuesta?.propias ?? SIN_PROPIAS,
    cargando: clave !== '' && respuesta?.pedido !== pedido,
    error: respuesta?.error ?? '',
    recargar,
  };
}
