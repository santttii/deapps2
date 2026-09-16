import { Route, Routes } from 'react-router-dom';
import Header from './components/Header';
import Footer from './components/Footer';
import ProtectedRoute from './components/ProtectedRoute';
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
                <CheckoutPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/confirmacion/:ordenId"
            element={
              <ProtectedRoute>
                <ConfirmationPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/mis-entradas"
            element={
              <ProtectedRoute>
                <MyTicketsPage />
              </ProtectedRoute>
            }
          />
          <Route path="/ingresar" element={<LoginPage />} />
          <Route path="/registro" element={<RegisterPage />} />
          <Route path="/reventa" element={<ResalePage />} />
          <Route
            path="/organizador"
            element={
              <ProtectedRoute rolRequerido="organizer">
                <OrganizerPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/puerta"
            element={
              <ProtectedRoute rolRequerido="staff">
                <GatePage />
              </ProtectedRoute>
            }
          />
        </Routes>
      </main>
      <Footer />
    </div>
  );
}
