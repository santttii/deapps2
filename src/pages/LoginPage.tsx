import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useApp } from '../store/AppContext';
import { esEmailValido, esPasswordValida } from '../lib/validate';
import Input from '../components/Input';
import Button from '../components/Button';
import PageTransition from '../components/PageTransition';

export default function LoginPage() {
  const { state, dispatch } = useApp();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const next = params.get('next') ?? '/';

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [verPassword, setVerPassword] = useState(false);
  const [error, setError] = useState('');
  const [sacudir, setSacudir] = useState(false);

  function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!esEmailValido(email) || !esPasswordValida(password)) {
      setError('Revisá el email y la contraseña.');
      dispararSacudida();
      return;
    }
    const usuario = state.usuarios.find((u) => u.email === email && u.password === password);
    if (!usuario) {
      setError('Revisá el email y la contraseña.');
      dispararSacudida();
      return;
    }
    dispatch({ type: 'LOGIN', payload: usuario });
    navigate(next);
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
              />
              <button
                type="button"
                onClick={() => setVerPassword((v) => !v)}
                className="font-mono-label absolute right-3 top-9 text-[10px] text-muted"
              >
                {verPassword ? 'ocultar' : 'ver'}
              </button>
            </div>
            {error && (
              <p role="alert" aria-live="polite" className="border-l-2 border-accent pl-2 text-xs text-ink">
                {error}
              </p>
            )}
            <label className="flex items-center gap-2 text-sm text-muted">
              <input type="checkbox" /> mantenerme conectado
            </label>
            <button type="button" className="text-left text-sm text-accent underline">
              olvidé mi contraseña
            </button>
            <Button type="submit">Ingresar</Button>
            <div className="my-2 flex items-center gap-3 text-xs text-faint">
              <span className="h-px flex-1 bg-line" /> o <span className="h-px flex-1 bg-line" />
            </div>
            <Button type="button" variant="secondary">
              Continuar con Google
            </Button>
            <Button type="button" variant="secondary">
              Continuar con Apple
            </Button>
          </form>

          <div className="font-mono-label mt-10 max-w-sm border border-edge p-4 text-[11px] leading-relaxed text-muted">
            <p className="mb-2 text-ink">Usuarios de prueba</p>
            <p>titular@palco.test / palco1234 — user, verificado</p>
            <p>organizador@palco.test / palco1234 — organizer</p>
            <p>puerta@palco.test / palco1234 — staff</p>
          </div>
        </div>

        <div className="bg-[#FBFAF8] p-8 md:p-14">
          <h2 className="text-2xl font-semibold tracking-tight text-ink">Crear cuenta</h2>
          <p className="mt-3 max-w-sm text-sm text-graphite">
            Registrate para comprar entradas nominativas y publicar en el canal de
            reventa oficial.
          </p>
          <Link to="/registro" className="mt-6 inline-block">
            <Button variant="secondary">Crear cuenta</Button>
          </Link>
        </div>
      </div>
    </PageTransition>
  );
}
