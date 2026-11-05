import { Link, useNavigate } from 'react-router-dom';
import { useApp } from '../store/AppContext';
import { useSesion } from '../store/SesionContext';
import { soltarReserva } from '../servicios/ventas';

const ENLACE = 'whitespace-nowrap text-graphite hover:text-ink';

export default function Header() {
  const { state, usuarioActual, dispatch } = useApp();
  const { salir } = useSesion();
  const navigate = useNavigate();

  function cerrarSesion() {
    // Si quedó una compra con el cupo reservado, se suelta. Va antes de salir() porque el pedido
    // necesita el token.
    if (state.carrito?.ordenId) soltarReserva(state.carrito.ordenId);
    salir();
    // El carrito no pasa a la cuenta que ingrese después en este navegador.
    dispatch({ type: 'SET_CARRITO', payload: null });
    // Desde una página privada, ProtectedRoute redirige antes a /ingresar?next=<esa página>.
    navigate('/');
  }

  return (
    <header className="sticky top-0 z-40">
      <div className="flex h-[34px] items-center justify-center gap-2 bg-ink px-4 text-onDark">
        <span className="h-1.5 w-1.5 rounded-dot bg-accent" aria-hidden="true" />
        <p className="font-mono-label text-center text-[10px]">
          Toda entrada es nominativa · Reventa oficial con tope de +10%
        </p>
      </div>
      <div className="border-b border-line bg-paper/95 backdrop-blur-sm">
        {/* En el celular los espacios se achican para que todo entre en 360 px; más angosto, los
            enlaces bajan a otro renglón en lugar de empujar la página hacia el costado. */}
        <div className="mx-auto flex max-w-[1240px] items-center justify-between gap-3 px-7 py-5 sm:gap-6">
          <Link to="/" className="shrink-0 text-xl font-semibold tracking-tight text-ink">
            Palco<span className="text-accent">.</span>
          </Link>
          <nav
            aria-label="Principal"
            className="flex flex-wrap items-center justify-end gap-x-3 gap-y-2 text-sm sm:gap-x-4"
          >
            <Link to="/reventa" className={ENLACE}>
              Reventa
            </Link>
            {usuarioActual ? (
              <>
                {/* Cada rol ve solo lo que puede usar: comprar y tener entradas es del rol user. */}
                {usuarioActual.rol === 'user' && (
                  <Link to="/mis-entradas" className={ENLACE}>
                    Mis entradas
                  </Link>
                )}
                {usuarioActual.rol === 'organizer' && (
                  <Link to="/organizador" className={ENLACE}>
                    Organizador
                  </Link>
                )}
                {usuarioActual.rol === 'staff' && (
                  <Link to="/puerta" className={ENLACE}>
                    Puerta
                  </Link>
                )}
                <button
                  type="button"
                  onClick={cerrarSesion}
                  className="font-mono-label whitespace-nowrap border border-edge px-3 py-2 text-[11px]"
                >
                  {/* En el celular el nombre no entra: queda solo "salir". */}
                  <span className="hidden sm:inline">{usuarioActual.nombre} · </span>
                  salir
                </button>
              </>
            ) : (
              <Link to="/ingresar" className="font-mono-label whitespace-nowrap border border-edge px-3 py-2 text-[11px]">
                Ingresar
              </Link>
            )}
          </nav>
        </div>
      </div>
    </header>
  );
}
