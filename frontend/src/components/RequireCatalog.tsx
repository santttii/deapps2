import type { ReactNode } from 'react';
import { useApp } from '../store/AppContext';
import Skeleton from './Skeleton';
import StatusMessage from './StatusMessage';

// Envuelve las pantallas que buscan eventos por id en el catálogo completo (checkout, mis
// entradas, reventa…): espera a que el catálogo llegue del servidor antes de mostrarlas.
// Sin esto, al recargar la página esas pantallas redirigían por no encontrar su evento.
export default function RequireCatalog({ children }: { children: ReactNode }) {
  const { state, recargarCatalogo } = useApp();

  if (state.catalogo.estado === 'cargando') {
    return (
      <div className="mx-auto flex max-w-[1240px] flex-col gap-4 px-7 py-10" role="status" aria-label="Cargando">
        <Skeleton className="h-8 w-1/3" />
        <Skeleton className="h-64 w-full" />
      </div>
    );
  }

  if (state.catalogo.estado === 'error') {
    return (
      <div className="mx-auto max-w-[1240px] px-7 py-10">
        <StatusMessage
          titulo="No pudimos cargar los eventos"
          texto={state.catalogo.error}
          accion="Reintentar"
          onAccion={recargarCatalogo}
        />
      </div>
    );
  }

  return <>{children}</>;
}
