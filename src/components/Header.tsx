import { Link, useNavigate } from 'react-router-dom';
import { useApp } from '../store/AppContext';

export default function Header() {
  const { usuarioActual, dispatch } = useApp();
  const navigate = useNavigate();

  return (
    <header className="sticky top-0 z-40">
      <div className="flex h-[34px] items-center justify-center gap-2 bg-ink px-4 text-onDark">
        <span className="h-1.5 w-1.5 rounded-dot bg-accent" aria-hidden="true" />
        <p className="font-mono-label text-center text-[10px]">
          Toda entrada es nominativa · Reventa oficial con tope de +10%
        </p>
      </div>
      <div className="border-b border-line bg-paper/95 backdrop-blur-sm">
        <div className="mx-auto flex max-w-[1240px] items-center justify-between gap-6 px-7 py-5">
          <Link to="/" className="text-xl font-semibold tracking-tight text-ink">
            Palco<span className="text-accent">.</span>
          </Link>
          <nav className="flex items-center gap-4 text-sm">
            <Link to="/reventa" className="text-graphite hover:text-ink">
              Reventa
            </Link>
            {usuarioActual ? (
              <>
                <Link to="/mis-entradas" className="text-graphite hover:text-ink">
                  Mis entradas
                </Link>
                {usuarioActual.rol === 'organizer' && (
                  <Link to="/organizador" className="text-graphite hover:text-ink">
                    Organizador
                  </Link>
                )}
                {usuarioActual.rol === 'staff' && (
                  <Link to="/puerta" className="text-graphite hover:text-ink">
                    Puerta
                  </Link>
                )}
                <button
                  type="button"
                  onClick={() => {
                    dispatch({ type: 'LOGOUT' });
                    navigate('/');
                  }}
                  className="font-mono-label border border-edge px-3 py-2 text-[11px]"
                >
                  {usuarioActual.nombre} · salir
                </button>
              </>
            ) : (
              <Link to="/ingresar" className="font-mono-label border border-edge px-3 py-2 text-[11px]">
                Ingresar
              </Link>
            )}
          </nav>
        </div>
      </div>
    </header>
  );
}
