import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useSesion } from '../store/SesionContext';
import { ErrorApi, mensajeDeError } from '../lib/api';
import { esEmailValido, esPasswordValida, esRutaInterna } from '../lib/validate';
import Input from '../components/Input';
import Button, { ButtonLink } from '../components/Button';
import PageTransition from '../components/PageTransition';

const CREDENCIALES_INCORRECTAS = 'Revisá el email y la contraseña.';

export default function LoginPage() {
  const { ingresar } = useSesion();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const next = params.get('next');
  const destino = esRutaInterna(next) ? next : '/';
  // El registro recibe el mismo next: quien crea la cuenta en medio de una compra vuelve a ella.
  const consultaNext = destino === '/' ? '' : `?next=${encodeURIComponent(destino)}`;

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [verPassword, setVerPassword] = useState(false);
  const [error, setError] = useState('');
  // Email de una cuenta que todavía no confirmó el código: habilita el enlace para verificarla.
  const [emailSinVerificar, setEmailSinVerificar] = useState('');
  const [ingresando, setIngresando] = useState(false);
  const [sacudir, setSacudir] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setEmailSinVerificar('');
    if (!esEmailValido(email) || !esPasswordValida(password)) {
      setError(CREDENCIALES_INCORRECTAS);
      dispararSacudida();
      return;
    }
    setIngresando(true);
    try {
      await ingresar(email.trim(), password);
    } catch (err) {
      mostrarError(err);
      return;
    } finally {
      setIngresando(false);
    }
    // Fuera del try: un problema al navegar no es un error de ingreso.
    navigate(destino);
  }

  function mostrarError(err: unknown) {
    const estado = err instanceof ErrorApi ? err.estado : null;
    if (estado === 400 || estado === 401) {
      setError(CREDENCIALES_INCORRECTAS);
      dispararSacudida();
      return;
    }
    // 429: demasiados intentos. Si el servidor dice cuánto falta, se dice en minutos; si no, queda
    // su mensaje, que no da un número.
    const espera = err instanceof ErrorApi ? err.segundosDeEspera : null;
    if (estado === 429 && espera) {
      const minutos = Math.ceil(espera / 60);
      setError(
        `Hubo demasiados intentos fallidos. Probá de nuevo en ${minutos} ${minutos === 1 ? 'minuto' : 'minutos'}.`,
      );
      return;
    }
    // 409: la cuenta existe pero falta confirmar el código que se mandó al email.
    if (estado === 409) setEmailSinVerificar(email.trim());
    setError(mensajeDeError(err));
  }

  function dispararSacudida() {
    setSacudir(true);
    setTimeout(() => setSacudir(false), 320);
  }

  return (
    <PageTransition>
      <div className="grid grid-cols-1 md:grid-cols-2 md:border-l md:border-r md:border-line">
        <div className="border-b border-line p-8 md:border-b-0 md:p-14">
          <h1 className="text-2xl font-semibold tracking-tight text-ink">Ya tengo cuenta</h1>
          <form
            onSubmit={onSubmit}
            className={`mt-6 flex max-w-sm flex-col gap-4 ${sacudir ? 'animate-shake' : ''}`}
          >
            <Input
              label="Email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              autoComplete="email"
            />
            <div className="relative">
              <Input
                label="Contraseña"
                type={verPassword ? 'text' : 'password'}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="current-password"
                className="pr-24"
              />
              {/* Va pegado al borde de abajo del campo, que es lo último del bloque. */}
              <button
                type="button"
                onClick={() => setVerPassword((v) => !v)}
                aria-label={verPassword ? 'Ocultar contraseña' : 'Mostrar contraseña'}
                className="font-mono-label absolute bottom-1 right-1 h-9 px-3 text-[10px] text-muted hover:text-ink"
              >
                {verPassword ? 'ocultar' : 'mostrar'}
              </button>
            </div>
            {error && (
              <p role="alert" aria-live="polite" className="border-l-2 border-accent pl-2 text-xs text-ink">
                {error}{' '}
                {emailSinVerificar && (
                  <Link
                    to={`/registro${consultaNext}`}
                    state={{ emailSinVerificar }}
                    className="text-accent underline"
                  >
                    Verificar mi email
                  </Link>
                )}
              </p>
            )}
            <Button type="submit" loading={ingresando} loadingText="Ingresando…">
              Ingresar
            </Button>
          </form>

          {import.meta.env.DEV && (
            <div className="mt-10 max-w-sm border border-edge p-4 text-[11px] leading-relaxed text-muted">
              <p className="font-mono-label mb-2 text-ink">Usuarios de prueba</p>
              {/* Sin mayúsculas: la contraseña se tipea así. */}
              <div className="font-mono">
                <p>titular@palco.test / palco1234 — user</p>
                <p>organizador@palco.test / palco1234 — organizer</p>
                <p>puerta@palco.test / palco1234 — staff</p>
              </div>
            </div>
          )}
        </div>

        <div className="bg-[#FBFAF8] p-8 md:p-14">
          <h2 className="text-2xl font-semibold tracking-tight text-ink">Crear cuenta</h2>
          <p className="mt-3 max-w-sm text-sm text-graphite">
            Registrate para comprar entradas nominativas y publicar en el canal de
            reventa oficial.
          </p>
          <ButtonLink to={`/registro${consultaNext}`} variant="secondary" className="mt-6">
            Crear cuenta
          </ButtonLink>
        </div>
      </div>
    </PageTransition>
  );
}
