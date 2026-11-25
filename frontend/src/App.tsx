import { Suspense, lazy, useEffect, type ComponentType } from 'react';
import { Route, Routes, matchPath, useLocation } from 'react-router-dom';
import Header from './components/Header';
import Footer from './components/Footer';
import ProtectedRoute from './components/ProtectedRoute';
import RequireCatalog from './components/RequireCatalog';
import Skeleton from './components/Skeleton';
import StatusMessage from './components/StatusMessage';
import CatalogPage from './pages/CatalogPage';
import { useApp } from './store/useApp';
import type { Evento } from './types';

// Cada pantalla, menos el catálogo (la portada), sale en su propio archivo de JavaScript y se baja
// recién la primera vez que se la abre: así la primera carga no trae el código de todas (el checkout,
// el panel del organizador, la puerta con la cámara...). Si el archivo no llega (un corte de red, o
// una versión nueva publicada con la pestaña abierta, que ya no tiene los archivos de la anterior),
// la pantalla avisa y ofrece recargar, en lugar de quedar en blanco.
function pantalla(cargar: () => Promise<{ default: ComponentType }>) {
  return lazy(() => cargar().catch(() => ({ default: PantallaQueNoCargo })));
}

function PantallaQueNoCargo() {
  return (
    <div className="mx-auto max-w-[1240px] px-7 py-10">
      <StatusMessage
        titulo="No pudimos abrir esta pantalla"
        texto="Revisá tu conexión y recargá la página."
        accion="Recargar la página"
        onAccion={() => window.location.reload()}
      />
    </div>
  );
}

// Lo que se ve mientras baja el archivo de la pantalla: el mismo esqueleto que RequireCatalog.
function CargandoPantalla() {
  return (
    <div className="mx-auto flex max-w-[1240px] flex-col gap-4 px-7 py-10" role="status" aria-label="Cargando">
      <Skeleton className="h-8 w-1/3" />
      <Skeleton className="h-64 w-full" />
    </div>
  );
}

const EventPage = pantalla(() => import('./pages/EventPage'));
const CheckoutPage = pantalla(() => import('./pages/CheckoutPage'));
const MyTicketsPage = pantalla(() => import('./pages/MyTicketsPage'));
const LoginPage = pantalla(() => import('./pages/LoginPage'));
const ConfirmationPage = pantalla(() => import('./pages/ConfirmationPage'));
const RegisterPage = pantalla(() => import('./pages/RegisterPage'));
const RecuperarPage = pantalla(() => import('./pages/RecuperarPage'));
const ResalePage = pantalla(() => import('./pages/ResalePage'));
const OrganizerPage = pantalla(() => import('./pages/OrganizerPage'));
const GatePage = pantalla(() => import('./pages/GatePage'));
const AssistantPage = pantalla(() => import('./pages/AssistantPage'));

const TITULOS: Record<string, string> = {
  '/checkout': 'Checkout',
  '/mis-entradas': 'Mis entradas',
  '/asistente': 'Asistente',
  '/ingresar': 'Ingresar',
  '/registro': 'Crear cuenta',
  '/recuperar': 'Recuperar la contraseña',
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
        <Suspense fallback={<CargandoPantalla />}>
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
            <Route
              path="/asistente"
              element={
                <ProtectedRoute>
                  <AssistantPage />
                </ProtectedRoute>
              }
            />
            <Route path="/ingresar" element={<LoginPage />} />
            <Route path="/registro" element={<RegisterPage />} />
            <Route path="/recuperar" element={<RecuperarPage />} />
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
        </Suspense>
      </main>
      <Footer />
    </div>
  );
}
