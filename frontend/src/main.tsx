import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { MotionConfig } from 'framer-motion';
import './index.css';
import App from './App.tsx';
import { AppProvider } from './store/AppContext';
import { SesionProvider } from './store/SesionContext';
import { ToastProvider } from './components/ToastProvider';

// El orden importa: SesionProvider avisa "sesión vencida" con un toast, y AppProvider toma el
// usuario de la sesión.
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <ToastProvider>
        <SesionProvider>
          <AppProvider>
            <MotionConfig reducedMotion="user">
              <App />
            </MotionConfig>
          </AppProvider>
        </SesionProvider>
      </ToastProvider>
    </BrowserRouter>
  </StrictMode>,
);
