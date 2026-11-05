import { useEffect } from 'react';
import { Route, Routes, matchPath, useLocation } from 'react-router-dom';
import Header from './components/Header';
import Footer from './components/Footer';
import ProtectedRoute from './components/ProtectedRoute';
import RequireCatalog from './components/RequireCatalog';
import CatalogPage from './pages/CatalogPage';
import EventPage from './pages/EventPage';
import CheckoutPage from './pages/CheckoutPage';
import MyTicketsPage from './pages/MyTicketsPage';
import LoginPage from './pages/LoginPage';
import ConfirmationPage from './pages/ConfirmationPage';
import RegisterPage from './pages/RegisterPage';
import ResalePage from './pages/ResalePage';
import OrganizerPage from './pages/OrganizerPage';
import GatePage from './pages/GatePage';
import { useApp } from './store/AppContext';
import type { Evento } from './types';

const TITULOS: Record<string, string> = {
  '/checkout': 'Checkout',
  '/mis-entradas': 'Mis entradas',
  '/ingresar': 'Ingresar',
  '/registro': 'Crear cuenta',
  '/reventa': 'Reventa oficial',
  '/organizador': 'Organizador',
  '/puerta': 'Validación en puerta',
};

// El título de la pestaña cambia con la página: es lo primero que anuncia un lector de pantalla
// al navegar. El del evento sale del catálogo; hasta que llega, dice "Evento".
function tituloDeLaPagina(pathname: string, eventos: Evento[]): string {
  if (pathname === '/') return 'Palco · Entradas nominativas y reventa oficial';
  const slug = matchPath('/evento/:slug', pathname)?.params.slug;
  if (slug !== undefined) {
    const evento = eventos.find((e) => e.slug === slug);
    return `${evento?.titulo ?? 'Evento'} · Palco`;
  }
  if (matchPath('/confirmacion/:ordenId', pathname)) return 'Tu compra · Palco';
  const titulo = TITULOS[pathname];
  return titulo ? `${titulo} · Palco` : 'Palco';
}

// ProtectedRoute pide sesión (y rol); RequireCatalog espera el catálogo del servidor en las
// pantallas que buscan eventos por id. El catálogo y el detalle manejan su propia carga.
// Comprar y ver entradas es solo del rol user: el backend (Ventas y Reventa) rechaza a los demás.
export default function App() {
  const { pathname } = useLocation();
  const { state } = useApp();
  const titulo = tituloDeLaPagina(pathname, state.eventos);

  useEffect(() => {
    document.title = titulo;
  }, [titulo]);

  return (
    <div className="flex min-h-screen flex-col">
      {/* Primera parada del teclado: salta el encabezado y va directo al contenido. */}
      <a
        href="#contenido"
        className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-50 focus:bg-ink focus:px-4 focus:py-3 focus:text-sm focus:text-paper"
      >
        Saltar al contenido
      </a>
      <Header />
      {/* tabIndex -1 para que el salto deje el foco acá; scroll-mt deja lugar al encabezado fijo. */}
      <main id="contenido" tabIndex={-1} className="flex-1 scroll-mt-40 focus:outline-none">
        <Routes>
          <Route path="/" element={<CatalogPage />} />
          <Route path="/evento/:slug" element={<EventPage />} />
          <Route
            path="/checkout"
            element={
              <ProtectedRoute rolRequerido="user">
                <RequireCatalog>
                  <CheckoutPage />
                </RequireCatalog>
              </ProtectedRoute>
            }
          />
          <Route
            path="/confirmacion/:ordenId"
            element={
              <ProtectedRoute rolRequerido="user">
                <RequireCatalog>
                  <ConfirmationPage />
                </RequireCatalog>
              </ProtectedRoute>
            }
          />
          <Route
            path="/mis-entradas"
            element={
              <ProtectedRoute rolRequerido="user">
                <RequireCatalog>
                  <MyTicketsPage />
                </RequireCatalog>
              </ProtectedRoute>
            }
          />
          <Route path="/ingresar" element={<LoginPage />} />
          <Route path="/registro" element={<RegisterPage />} />
          <Route
            path="/reventa"
            element={
              <RequireCatalog>
                <ResalePage />
              </RequireCatalog>
            }
          />
          <Route
            path="/organizador"
            element={
              <ProtectedRoute rolRequerido="organizer">
                <RequireCatalog>
                  <OrganizerPage />
                </RequireCatalog>
              </ProtectedRoute>
            }
          />
          <Route
            path="/puerta"
            element={
              <ProtectedRoute rolRequerido="staff">
                <RequireCatalog>
                  <GatePage />
                </RequireCatalog>
              </ProtectedRoute>
            }
          />
        </Routes>
      </main>
      <Footer />
    </div>
  );
}
