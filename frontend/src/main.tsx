import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { MotionConfig } from 'framer-motion';
import './index.css';
import App from './App.tsx';
import { AppProvider } from './store/AppContext';
import { ToastProvider } from './components/ToastProvider';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <AppProvider>
        <ToastProvider>
          <MotionConfig reducedMotion="user">
            <App />
          </MotionConfig>
        </ToastProvider>
      </AppProvider>
    </BrowserRouter>
  </StrictMode>,
);
