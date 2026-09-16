import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useApp } from '../store/AppContext';
import type { Rol } from '../types';

interface ProtectedRouteProps {
  children: ReactNode;
  rolRequerido?: Rol;
}

export default function ProtectedRoute({ children, rolRequerido }: ProtectedRouteProps) {
  const { usuarioActual } = useApp();
  const location = useLocation();

  if (!usuarioActual) {
    return <Navigate to={`/ingresar?next=${encodeURIComponent(location.pathname)}`} replace />;
  }

  if (rolRequerido && usuarioActual.rol !== rolRequerido) {
    return <Navigate to="/" replace />;
  }

  return <>{children}</>;
}
