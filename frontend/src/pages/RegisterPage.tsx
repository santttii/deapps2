import { useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useSesion } from '../store/SesionContext';
import { useToast } from '../components/ToastProvider';
import { ErrorApi, mensajeDeError } from '../lib/api';
import { reenviarCodigo, registrar } from '../servicios/usuarios';
import {
  esDNIValido,
  esEmailValido,
  esMayorDeEdad,
  esNombreValido,
  esPasswordFuerte,
  fuerzaPassword,
  soloDigitosDNI,
} from '../lib/validate';
import Stepper from '../components/Stepper';
import Input from '../components/Input';
import Button from '../components/Button';
import PageTransition from '../components/PageTransition';

interface Datos {
  nombre: string;
  apellido: string;
  dni: string;
  fechaNacimiento: string;
  email: string;
  telefono: string;
  password: string;
  repetirPassword: string;
}

// Las claves coinciden con los nombres de campo del backend: sus errores se vuelcan directo acá.
type Errores = Partial<Record<keyof Datos, string>>;

// El backend acepta un reenvío cada 30 segundos.
const SEGUNDOS_ENTRE_REENVIOS = 30;

function codigoVacio(): string[] {
  return Array<string>(6).fill('');
}

export default function RegisterPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { verificarEmail } = useSesion();
  const { mostrarToast } = useToast();

  // El paso no se guarda en un useState: sale del state del router. Si trae un email sin
  // verificar es el paso del código. Así recargar la página no devuelve al formulario vacío, y
  // el enlace "Verificar mi email" del ingreso llega por el mismo camino.
  const estadoDeRuta = location.state as { emailSinVerificar?: unknown } | null;
  const emailSinVerificar =
    typeof estadoDeRuta?.emailSinVerificar === 'string' ? estadoDeRuta.emailSinVerificar : '';
  const paso = emailSinVerificar ? 1 : 0;

  const [datos, setDatos] = useState<Datos>({
    nombre: '',
    apellido: '',
    dni: '',
    fechaNacimiento: '',
    email: '',
    telefono: '',
    password: '',
    repetirPassword: '',
  });
  const [errores, setErrores] = useState<Errores>({});
  const [errorGeneral, setErrorGeneral] = useState('');
  const [aceptaTerminos, setAceptaTerminos] = useState(false);
  const [declaraDatos, setDeclaraDatos] = useState(false);
  const [registrando, setRegistrando] = useState(false);

  const [codigo, setCodigo] = useState<string[]>(codigoVacio);
  const inputsRef = useRef<(HTMLInputElement | null)[]>([]);
  const [errorCodigo, setErrorCodigo] = useState('');
  const [yaVerificada, setYaVerificada] = useState(false);
  const [verificando, setVerificando] = useState(false);
  // Arranca en 0: quien llega desde el ingreso o recarga puede pedir un código enseguida.
  const [reenviarSegundos, setReenviarSegundos] = useState(0);
  const [reenviando, setReenviando] = useState(false);

  function cambiar(campo: keyof Datos, valor: string) {
    setDatos((d) => ({ ...d, [campo]: valor }));
  }

  function validarDatos(): boolean {
    const e: Errores = {};
    if (!esNombreValido(datos.nombre)) e.nombre = 'Ingresá un nombre válido.';
    if (!esNombreValido(datos.apellido)) e.apellido = 'Ingresá un apellido válido.';
    if (!esDNIValido(datos.dni)) e.dni = 'El DNI debe tener 7 u 8 dígitos.';
    if (!datos.fechaNacimiento || !esMayorDeEdad(datos.fechaNacimiento)) {
      e.fechaNacimiento = 'Tenés que ser mayor de 16 años.';
    }
    if (!esEmailValido(datos.email)) e.email = 'Ingresá un email válido.';
    if (!esPasswordFuerte(datos.password)) e.password = 'Mínimo 8 caracteres, con letras y números.';
    if (datos.password !== datos.repetirPassword) e.repetirPassword = 'Las contraseñas no coinciden.';
    setErrores(e);
    return Object.keys(e).length === 0;
  }

  async function crearCuenta() {
    setErrorGeneral('');
    if (!validarDatos()) return;
    setRegistrando(true);
    let emailRegistrado: string;
    try {
      const respuesta = await registrar({
        nombre: datos.nombre.trim(),
        apellido: datos.apellido.trim(),
        dni: soloDigitosDNI(datos.dni),
        fechaNacimiento: datos.fechaNacimiento,
        email: datos.email,
        telefono: datos.telefono.trim(),
        password: datos.password,
      });
      emailRegistrado = respuesta.email;
    } catch (error) {
      // Los errores por campo (email o DNI repetidos, formato) van debajo de cada input.
      if (error instanceof ErrorApi && Object.keys(error.campos).length > 0) setErrores(error.campos);
      else setErrorGeneral(mensajeDeError(error));
      return;
    } finally {
      setRegistrando(false);
    }
    setCodigo(codigoVacio());
    setErrorCodigo('');
    setYaVerificada(false);
    setReenviarSegundos(SEGUNDOS_ENTRE_REENVIOS);
    navigate('/registro', { replace: true, state: { emailSinVerificar: emailRegistrado } });
  }

  function volverADatos() {
    navigate('/registro', { replace: true, state: null });
  }

  function onCambiarCodigo(index: number, valor: string) {
    if (/[^0-9]/.test(valor) && valor !== '') return;
    const nuevo = [...codigo];
    nuevo[index] = valor.slice(-1);
    setCodigo(nuevo);
    if (valor && index < 5) inputsRef.current[index + 1]?.focus();
  }

  function onKeyDownCodigo(index: number, e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Backspace' && !codigo[index] && index > 0) {
      inputsRef.current[index - 1]?.focus();
    }
  }

  function onPasteCodigo(e: React.ClipboardEvent<HTMLInputElement>) {
    const texto = e.clipboardData.getData('text').replace(/\D/g, '').slice(0, 6);
    if (texto.length === 6) {
      e.preventDefault();
      setCodigo(texto.split(''));
      inputsRef.current[5]?.focus();
    }
  }

  useEffect(() => {
    if (reenviarSegundos <= 0) return;
    const interval = setInterval(() => {
      setReenviarSegundos((s) => (s > 0 ? s - 1 : 0));
    }, 1000);
    return () => clearInterval(interval);
  }, [reenviarSegundos]);

  async function verificar() {
    setErrorCodigo('');
    setYaVerificada(false);
    setVerificando(true);
    try {
      // Con el código correcto el backend ya devuelve la sesión: la cuenta queda logueada.
      await verificarEmail(emailSinVerificar, codigo.join(''));
    } catch (error) {
      // Código incorrecto, vencido o con demasiados intentos: llega como error del campo "codigo".
      const errorDelCodigo = error instanceof ErrorApi ? error.campos.codigo : undefined;
      if (errorDelCodigo) {
        setCodigo(codigoVacio());
        inputsRef.current[0]?.focus();
      }
      setErrorCodigo(errorDelCodigo ?? mensajeDeError(error));
      // 409: ese email ya estaba verificado, solo falta ingresar.
      setYaVerificada(error instanceof ErrorApi && error.estado === 409);
      return;
    } finally {
      setVerificando(false);
    }
    navigate('/', { replace: true });
  }

  async function reenviar() {
    setYaVerificada(false);
    setReenviando(true);
    try {
      await reenviarCodigo(emailSinVerificar);
      setErrorCodigo('');
      setReenviarSegundos(SEGUNDOS_ENTRE_REENVIOS);
      mostrarToast('Te enviamos un código nuevo.');
    } catch (error) {
      setErrorCodigo(mensajeDeError(error));
      // 409: se pidió antes de tiempo y hay que esperar. Con otro error se puede reintentar enseguida.
      if (error instanceof ErrorApi && error.estado === 409) setReenviarSegundos(SEGUNDOS_ENTRE_REENVIOS);
    } finally {
      setReenviando(false);
    }
  }

  const fuerza = fuerzaPassword(datos.password);
  const codigoCompleto = codigo.every((c) => c !== '');

  return (
    <PageTransition>
      <div className="mx-auto max-w-[1240px] px-7 py-10">
        <Stepper pasos={['Datos', 'Verificar email']} activo={paso} onVolver={volverADatos} />

        <div className="mt-8 grid grid-cols-1 gap-10 md:grid-cols-[1fr_300px]">
          <div>
            {paso === 0 && (
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                <Input label="Nombre" value={datos.nombre} onChange={(e) => cambiar('nombre', e.target.value)} error={errores.nombre} maxLength={80} />
                <Input label="Apellido" value={datos.apellido} onChange={(e) => cambiar('apellido', e.target.value)} error={errores.apellido} maxLength={80} />
                <Input label="DNI" value={datos.dni} onChange={(e) => cambiar('dni', soloDigitosDNI(e.target.value))} error={errores.dni} />
                <Input label="Fecha de nacimiento" type="date" value={datos.fechaNacimiento} onChange={(e) => cambiar('fechaNacimiento', e.target.value)} error={errores.fechaNacimiento} />
                <Input label="Email" type="email" value={datos.email} onChange={(e) => cambiar('email', e.target.value)} error={errores.email} maxLength={200} />
                <Input label="Teléfono" value={datos.telefono} onChange={(e) => cambiar('telefono', e.target.value)} error={errores.telefono} maxLength={30} />
                <div>
                  <Input label="Contraseña" type="password" value={datos.password} onChange={(e) => cambiar('password', e.target.value)} error={errores.password} />
                  <div className="mt-2 flex gap-1">
                    {[0, 1, 2].map((i) => (
                      <span key={i} className={`h-1 flex-1 ${fuerza > i ? 'bg-accent' : 'bg-fill'}`} />
                    ))}
                  </div>
                </div>
                <Input label="Repetir contraseña" type="password" value={datos.repetirPassword} onChange={(e) => cambiar('repetirPassword', e.target.value)} error={errores.repetirPassword} />

                {/* Los términos se aceptan antes de crear la cuenta, que ocurre en este paso. */}
                <div className="flex flex-col gap-2 sm:col-span-2">
                  <label className="flex items-start gap-2 text-sm text-muted">
                    <input type="checkbox" checked={aceptaTerminos} onChange={(e) => setAceptaTerminos(e.target.checked)} className="mt-1" />
                    Acepto los términos y la política anti-reventa.
                  </label>
                  <label className="flex items-start gap-2 text-sm text-muted">
                    <input type="checkbox" checked={declaraDatos} onChange={(e) => setDeclaraDatos(e.target.checked)} className="mt-1" />
                    Declaro que los datos ingresados son propios.
                  </label>
                </div>

                {errorGeneral && (
                  <p role="alert" aria-live="polite" className="border-l-2 border-accent pl-2 text-xs text-ink sm:col-span-2">
                    {errorGeneral}
                  </p>
                )}

                <div className="sm:col-span-2">
                  <Button
                    disabled={!aceptaTerminos || !declaraDatos}
                    loading={registrando}
                    loadingText="Creando cuenta…"
                    onClick={crearCuenta}
                  >
                    Crear cuenta
                  </Button>
                </div>
              </div>
            )}

            {paso === 1 && (
              <div className="flex max-w-sm flex-col gap-5">
                <p className="text-sm text-graphite">
                  Te enviamos un código de 6 dígitos a {emailSinVerificar}. Si ya venció,
                  pedí uno nuevo.
                </p>
                {import.meta.env.DEV && (
                  <p className="font-mono-label text-[10px] text-muted">
                    Desarrollo: el email todavía no se envía, el código queda en el log del servidor.
                  </p>
                )}
                <div className="flex gap-2">
                  {codigo.map((c, i) => (
                    <input
                      key={i}
                      ref={(el) => {
                        inputsRef.current[i] = el;
                      }}
                      value={c}
                      onChange={(e) => onCambiarCodigo(i, e.target.value)}
                      onKeyDown={(e) => onKeyDownCodigo(i, e)}
                      onPaste={onPasteCodigo}
                      inputMode="numeric"
                      maxLength={1}
                      aria-label={`Dígito ${i + 1} del código`}
                      className="h-14 w-12 border border-edge text-center text-xl focus:border-accent"
                    />
                  ))}
                </div>
                {errorCodigo && (
                  <p role="alert" aria-live="polite" className="border-l-2 border-accent pl-2 text-xs text-ink">
                    {errorCodigo}{' '}
                    {yaVerificada && (
                      <Link to="/ingresar" className="text-accent underline">
                        Ingresar
                      </Link>
                    )}
                  </p>
                )}
                <button
                  type="button"
                  disabled={reenviarSegundos > 0 || reenviando}
                  onClick={reenviar}
                  className="font-mono-label self-start text-[11px] text-accent disabled:text-faint"
                >
                  {reenviarSegundos > 0 ? `reenviar en 0:${reenviarSegundos.toString().padStart(2, '0')}` : 'reenviar código'}
                </button>

                <Button disabled={!codigoCompleto} loading={verificando} loadingText="Verificando…" onClick={verificar}>
                  Verificar email
                </Button>
              </div>
            )}
          </div>

          <aside className="h-fit border border-wire p-6">
            <h2 className="text-sm font-semibold text-ink">Por qué pedimos el DNI</h2>
            <ol className="mt-4 flex flex-col gap-4 text-sm text-graphite">
              <li>
                <span className="font-mono-label mr-2 text-accent">01</span>
                Cada entrada queda nominada a una persona real.
              </li>
              <li>
                <span className="font-mono-label mr-2 text-accent">02</span>
                Se contrasta en puerta contra el documento.
              </li>
              <li>
                <span className="font-mono-label mr-2 text-accent">03</span>
                Habilita el canal de reventa oficial con tope de precio.
              </li>
            </ol>
          </aside>
        </div>
      </div>
    </PageTransition>
  );
}
