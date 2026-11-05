import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useApp } from '../store/AppContext';
import type { Rol } from '../types';
import { ButtonLink } from './Button';

interface ProtectedRouteProps {
  children: ReactNode;
  rolRequerido?: Rol;
}

const PARA_QUIEN: Record<Rol, string> = {
  user: 'las cuentas que compran entradas',
  organizer: 'las cuentas de organizador',
  staff: 'el personal de puerta',
};

export default function ProtectedRoute({ children, rolRequerido }: ProtectedRouteProps) {
  const { usuarioActual } = useApp();
  const location = useLocation();

  if (!usuarioActual) {
    return <Navigate to={`/ingresar?next=${encodeURIComponent(location.pathname)}`} replace />;
  }

  // Con otro rol se explica por qué no se ve la pantalla, en lugar de mandar al inicio sin aviso.
  if (rolRequerido && usuarioActual.rol !== rolRequerido) {
    return (
      <div className="mx-auto max-w-[1240px] px-7 py-10">
        <div className="border border-dashed border-edge p-8 text-center sm:p-14">
          <h1 className="text-lg font-medium text-ink">Esta sección no es para tu cuenta</h1>
          <p className="mt-2 text-sm text-muted">Es para {PARA_QUIEN[rolRequerido]}.</p>
          <ButtonLink to="/" variant="secondary" className="mt-5">
            Ir al inicio
          </ButtonLink>
        </div>
      </div>
    );
  }

  return <>{children}</>;
}
