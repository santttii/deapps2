import { useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { useToast } from '../components/useToast';
import { ErrorApi, mensajeDeError } from '../lib/api';
import { pedirCodigoDeReinicio, reiniciarPassword } from '../servicios/usuarios';
import { esEmailValido, esPasswordFuerte, esRutaInterna, fuerzaPassword } from '../lib/validate';
import Stepper from '../components/Stepper';
import Input from '../components/Input';
import Button from '../components/Button';
import PageTransition from '../components/PageTransition';

// Las claves coinciden con los nombres de campo del backend: sus errores se vuelcan directo acá.
type Errores = Partial<Record<'email' | 'codigo' | 'password' | 'repetirPassword', string>>;

// El backend manda un código nuevo recién 30 segundos después del anterior. Antes responde igual, sin
// mandarlo: por eso el botón espera ese tiempo y no deja creer que salió otro.
const SEGUNDOS_ENTRE_PEDIDOS = 30;

const REGLA_PASSWORD = 'Mínimo 8 caracteres, con letras y números.';

// 429: demasiados pedidos seguidos. Se trata como en el ingreso: si el servidor dice cuánto esperar
// (Retry-After), se dice en minutos; si no, queda su mensaje.
function textoDelError(error: unknown): string {
  const espera = error instanceof ErrorApi && error.estado === 429 ? error.segundosDeEspera : null;
  if (espera) {
    const minutos = Math.ceil(espera / 60);
    return `Hubo demasiados intentos. Probá de nuevo en ${minutos} ${minutos === 1 ? 'minuto' : 'minutos'}.`;
  }
  return mensajeDeError(error);
}

// Recuperar la contraseña (HU-03), en dos pasos: el email, al que llega un código de 6 dígitos, y la
// contraseña nueva con ese código. Termina en el ingreso, porque el backend no devuelve sesión.
export default function RecuperarPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { mostrarToast } = useToast();

  // Como en el registro, el paso sale del state del router: con el email ya pedido es el segundo. Así
  // recargar la página no obliga a pedir otro código.
  const estadoDeRuta = location.state as { emailDeReinicio?: unknown } | null;
  const emailPedido = typeof estadoDeRuta?.emailDeReinicio === 'string' ? estadoDeRuta.emailDeReinicio : '';
  const paso = emailPedido ? 1 : 0;

  // El mismo next que el ingreso: quien recupera la contraseña en medio de una compra vuelve a ella.
  const [params] = useSearchParams();
  const next = params.get('next');
  const destino = esRutaInterna(next) ? next : '/';
  const consultaNext = destino === '/' ? '' : `?next=${encodeURIComponent(destino)}`;

  const [email, setEmail] = useState('');
  const [codigo, setCodigo] = useState('');
  const [password, setPassword] = useState('');
  const [repetirPassword, setRepetirPassword] = useState('');
  const [errores, setErrores] = useState<Errores>({});
  const [errorGeneral, setErrorGeneral] = useState('');
  const [enviando, setEnviando] = useState(false);
  const [cambiando, setCambiando] = useState(false);
  // Arranca en 0: quien recarga el segundo paso puede pedir otro código enseguida.
  const [reenviarSegundos, setReenviarSegundos] = useState(0);
  const [reenviando, setReenviando] = useState(false);
  const formRef = useRef<HTMLFormElement>(null);

  // Después de un intento con errores, el foco va al primer campo marcado: con teclado o con lector de
  // pantalla se llega directo a lo que hay que corregir.
  useEffect(() => {
    formRef.current?.querySelector<HTMLInputElement>('[aria-invalid="true"]')?.focus();
  }, [errores]);

  useEffect(() => {
    if (reenviarSegundos <= 0) return;
    const interval = setInterval(() => {
      setReenviarSegundos((s) => (s > 0 ? s - 1 : 0));
    }, 1000);
    return () => clearInterval(interval);
  }, [reenviarSegundos]);

  async function pedirCodigo(e: React.FormEvent) {
    e.preventDefault();
    setErrorGeneral('');
    if (!esEmailValido(email)) {
      setErrores({ email: 'Ingresá un email válido.' });
      return;
    }
    setErrores({});
    setEnviando(true);
    try {
      await pedirCodigoDeReinicio(email);
    } catch (error) {
      if (error instanceof ErrorApi && error.campos.email) setErrores({ email: error.campos.email });
      else setErrorGeneral(textoDelError(error));
      return;
    } finally {
      setEnviando(false);
    }
    // La respuesta es la misma exista o no la cuenta, y la pantalla tampoco distingue: siempre pasa
    // al segundo paso con el mismo texto.
    setReenviarSegundos(SEGUNDOS_ENTRE_PEDIDOS);
    navigate(`/recuperar${consultaNext}`, { replace: true, state: { emailDeReinicio: email.trim() } });
  }

  // Vuelve al primer paso con el email que se había pedido ya escrito, para corregirlo.
  function usarOtroEmail() {
    setEmail(emailPedido);
    setErrores({});
    setErrorGeneral('');
    setCodigo('');
    setPassword('');
    setRepetirPassword('');
    navigate(`/recuperar${consultaNext}`, { replace: true, state: null });
  }

  async function cambiarPassword(e: React.FormEvent) {
    e.preventDefault();
    setErrorGeneral('');
    const nuevos: Errores = {};
    if (!/^\d{6}$/.test(codigo)) nuevos.codigo = 'Ingresá el código de 6 dígitos.';
    if (!esPasswordFuerte(password)) nuevos.password = REGLA_PASSWORD;
    if (password !== repetirPassword) nuevos.repetirPassword = 'Las contraseñas no coinciden.';
    setErrores(nuevos);
    if (Object.keys(nuevos).length > 0) return;

    setCambiando(true);
    try {
      await reiniciarPassword({ email: emailPedido, codigo, password });
    } catch (error) {
      // Problem Details con el error en un campo: código incorrecto, vencido o con demasiados intentos,
      // o una contraseña que no cumple la regla. Va debajo de ese campo, con el texto del backend.
      if (error instanceof ErrorApi && (error.campos.codigo || error.campos.password)) {
        if (error.campos.codigo) setCodigo('');
        setErrores({ codigo: error.campos.codigo, password: error.campos.password });
      } else {
        setErrorGeneral(textoDelError(error));
      }
      return;
    } finally {
      setCambiando(false);
    }
    // La sesión no se abre sola: se vuelve al ingreso con el email ya escrito y el aviso.
    navigate(`/ingresar${consultaNext}`, { replace: true, state: { passwordCambiada: emailPedido } });
  }

  async function reenviar() {
    setErrorGeneral('');
    setReenviando(true);
    try {
      await pedirCodigoDeReinicio(emailPedido);
      setReenviarSegundos(SEGUNDOS_ENTRE_PEDIDOS);
      setCodigo('');
      setErrores({});
      mostrarToast('Si el email tiene una cuenta, te enviamos un código nuevo.');
      // El botón queda deshabilitado durante la espera: el foco pasa al código, que es lo que sigue.
      formRef.current?.querySelector<HTMLInputElement>('input[name="codigo"]')?.focus();
    } catch (error) {
      setErrorGeneral(textoDelError(error));
    } finally {
      setReenviando(false);
    }
  }

  const fuerza = fuerzaPassword(password);

  return (
    <PageTransition>
      <div className="mx-auto max-w-[1240px] px-7 py-10">
        <h1 className="mb-6 text-2xl font-semibold tracking-tight text-ink">Recuperar la contraseña</h1>
        <Stepper pasos={['Email', 'Contraseña nueva']} activo={paso} onVolver={usarOtroEmail} />

        <div className="mt-8 grid grid-cols-1 gap-10 md:grid-cols-[1fr_300px]">
          <div>
            {paso === 0 && (
              <form ref={formRef} onSubmit={pedirCodigo} noValidate className="flex max-w-sm flex-col gap-4">
                <p className="text-sm text-graphite">
                  Escribí el email de tu cuenta y te mandamos un código de 6 dígitos para elegir una
                  contraseña nueva.
                </p>
                <Input
                  label="Email"
                  type="email"
                  name="email"
                  autoComplete="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  error={errores.email}
                  maxLength={200}
                />
                {errorGeneral && (
                  <p role="alert" aria-live="polite" className="border-l-2 border-accent pl-2 text-xs text-ink">
                    {errorGeneral}
                  </p>
                )}
                <Button type="submit" loading={enviando} loadingText="Enviando…">
                  Enviar código
                </Button>
              </form>
            )}

            {paso === 1 && (
              <form ref={formRef} onSubmit={cambiarPassword} noValidate className="flex max-w-sm flex-col gap-4">
                {/* break-all: un email largo no tiene espacios donde cortarse y a 360 px desbordaría. */}
                <p className="text-sm text-graphite">
                  Si <span className="break-all">{emailPedido}</span> tiene una cuenta en Palco, te enviamos
                  un código de 6 dígitos. Si ya venció, pedí uno nuevo.
                </p>
                {import.meta.env.DEV && (
                  <p className="font-mono-label text-[10px] text-muted">
                    Desarrollo: el email todavía no se envía, el código queda en el log del servidor.
                  </p>
                )}
                {/* Un solo campo con su etiqueta: el lector de pantalla lo anuncia una vez y el navegador
                    puede completar el código que llegó (one-time-code). El foco arranca acá. */}
                <Input
                  label="Código de 6 dígitos"
                  name="codigo"
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  autoFocus
                  maxLength={6}
                  value={codigo}
                  onChange={(e) => setCodigo(e.target.value.replace(/\D/g, '').slice(0, 6))}
                  error={errores.codigo}
                />
                <div>
                  <Input
                    label="Contraseña nueva"
                    type="password"
                    name="password"
                    autoComplete="new-password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    error={errores.password}
                    hint={REGLA_PASSWORD}
                  />
                  {/* La regla ya está escrita en el campo: las barras son solo visuales. */}
                  <div className="mt-2 flex gap-1" aria-hidden="true">
                    {[0, 1, 2].map((i) => (
                      <span key={i} className={`h-1 flex-1 ${fuerza > i ? 'bg-accent' : 'bg-fill'}`} />
                    ))}
                  </div>
                </div>
                <Input
                  label="Repetir contraseña nueva"
                  type="password"
                  name="repetirPassword"
                  autoComplete="new-password"
                  value={repetirPassword}
                  onChange={(e) => setRepetirPassword(e.target.value)}
                  error={errores.repetirPassword}
                />
                {errorGeneral && (
                  <p role="alert" aria-live="polite" className="border-l-2 border-accent pl-2 text-xs text-ink">
                    {errorGeneral}
                  </p>
                )}
                <button
                  type="button"
                  disabled={reenviarSegundos > 0 || reenviando}
                  onClick={reenviar}
                  className="font-mono-label self-start text-[11px] text-accent disabled:text-faint"
                >
                  {reenviarSegundos > 0
                    ? `reenviar en 0:${reenviarSegundos.toString().padStart(2, '0')}`
                    : 'reenviar código'}
                </button>
                <Button type="submit" loading={cambiando} loadingText="Cambiando…">
                  Cambiar contraseña
                </Button>
              </form>
            )}

            <Link to={`/ingresar${consultaNext}`} className="mt-6 inline-block text-sm text-accent underline">
              Volver a ingresar
            </Link>
          </div>

          <aside className="h-fit border border-wire p-6">
            <h2 className="text-sm font-semibold text-ink">Cómo funciona</h2>
            <ol className="mt-4 flex flex-col gap-4 text-sm text-graphite">
              <li>
                <span className="font-mono-label mr-2 text-accent">01</span>
                Pedís un código con el email de tu cuenta.
              </li>
              <li>
                <span className="font-mono-label mr-2 text-accent">02</span>
                Te llega por email. Si vence, pedís otro.
              </li>
              <li>
                <span className="font-mono-label mr-2 text-accent">03</span>
                Elegís la contraseña nueva e ingresás con ella.
              </li>
            </ol>
          </aside>
        </div>
      </div>
    </PageTransition>
  );
}
