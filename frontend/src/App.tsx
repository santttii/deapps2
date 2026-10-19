import { Route, Routes } from 'react-router-dom';
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

// ProtectedRoute pide sesión (y rol); RequireCatalog espera el catálogo del servidor en las
// pantallas que buscan eventos por id. El catálogo y el detalle manejan su propia carga.
export default function App() {
  return (
    <div className="flex min-h-screen flex-col">
      <Header />
      <main className="flex-1">
        <Routes>
          <Route path="/" element={<CatalogPage />} />
          <Route path="/evento/:slug" element={<EventPage />} />
          <Route
            path="/checkout"
            element={
              <ProtectedRoute>
                <RequireCatalog>
                  <CheckoutPage />
                </RequireCatalog>
              </ProtectedRoute>
            }
          />
          <Route
            path="/confirmacion/:ordenId"
            element={
              <ProtectedRoute>
                <RequireCatalog>
                  <ConfirmationPage />
                </RequireCatalog>
              </ProtectedRoute>
            }
          />
          <Route
            path="/mis-entradas"
            element={
              <ProtectedRoute>
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
