import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useApp } from '../store/AppContext';
import {
  esDNIValido,
  esEmailValido,
  esMayorDeEdad,
  esNombreValido,
  esPasswordFuerte,
  fuerzaPassword,
  soloDigitosDNI,
} from '../lib/validate';
import { sleep } from '../lib/format';
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

export default function RegisterPage() {
  const { state, dispatch } = useApp();
  const navigate = useNavigate();
  const [paso, setPaso] = useState(0);

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
  const [errores, setErrores] = useState<Partial<Record<keyof Datos, string>>>({});

  const [archivos, setArchivos] = useState<{ frente?: string; dorso?: string }>({});
  const [subiendo, setSubiendo] = useState<'frente' | 'dorso' | null>(null);

  const [codigo, setCodigo] = useState<string[]>(Array(6).fill(''));
  const inputsRef = useRef<(HTMLInputElement | null)[]>([]);
  const [reenviarSegundos, setReenviarSegundos] = useState(30);
  const [aceptaTerminos, setAceptaTerminos] = useState(false);
  const [declaraDatos, setDeclaraDatos] = useState(false);
  const [creando, setCreando] = useState(false);

  function validarDatos(): boolean {
    const e: Partial<Record<keyof Datos, string>> = {};
    if (!esNombreValido(datos.nombre)) e.nombre = 'Ingresá un nombre válido.';
    if (!esNombreValido(datos.apellido)) e.apellido = 'Ingresá un apellido válido.';
    if (!esDNIValido(datos.dni)) {
      e.dni = 'El DNI debe tener 7 u 8 dígitos.';
    } else if (state.usuarios.some((u) => u.dni === soloDigitosDNI(datos.dni))) {
      e.dni = 'Ese DNI ya está registrado.';
    }
    if (!datos.fechaNacimiento || !esMayorDeEdad(datos.fechaNacimiento)) {
      e.fechaNacimiento = 'Tenés que ser mayor de 16 años.';
    }
    if (!esEmailValido(datos.email)) e.email = 'Ingresá un email válido.';
    if (!esPasswordFuerte(datos.password)) e.password = 'Mínimo 8 caracteres, con letras y números.';
    if (datos.password !== datos.repetirPassword) e.repetirPassword = 'Las contraseñas no coinciden.';
    setErrores(e);
    return Object.keys(e).length === 0;
  }

  function onArchivo(lado: 'frente' | 'dorso', file: File) {
    setSubiendo(lado);
    setTimeout(() => {
      setArchivos((prev) => ({ ...prev, [lado]: file.name }));
      setSubiendo(null);
    }, 900);
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
    if (paso !== 2 || reenviarSegundos <= 0) return;
    const interval = setInterval(() => {
      setReenviarSegundos((s) => (s > 0 ? s - 1 : 0));
    }, 1000);
    return () => clearInterval(interval);
  }, [paso, reenviarSegundos]);

  async function crearCuenta() {
    if (!aceptaTerminos || !declaraDatos) return;
    setCreando(true);
    await sleep(700);
    dispatch({
      type: 'REGISTER',
      payload: {
        id: `user-${Date.now()}`,
        nombre: datos.nombre,
        apellido: datos.apellido,
        dni: soloDigitosDNI(datos.dni),
        email: datos.email,
        rol: 'user',
        identidadVerificada: true,
        password: datos.password,
      },
    });
    setCreando(false);
    navigate('/');
  }

  const fuerza = fuerzaPassword(datos.password);
  const codigoCompleto = codigo.every((c) => c !== '');

  return (
    <PageTransition>
      <div className="mx-auto max-w-[1240px] px-7 py-10">
        <Stepper pasos={['Datos', 'Identidad', 'Verificar email']} activo={paso} onVolver={setPaso} />

        <div className="mt-8 grid grid-cols-1 gap-10 md:grid-cols-[1fr_300px]">
          <div>
            {paso === 0 && (
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                <Input label="Nombre" value={datos.nombre} onChange={(e) => setDatos((d) => ({ ...d, nombre: e.target.value }))} error={errores.nombre} />
                <Input label="Apellido" value={datos.apellido} onChange={(e) => setDatos((d) => ({ ...d, apellido: e.target.value }))} error={errores.apellido} />
                <Input label="DNI" value={datos.dni} onChange={(e) => setDatos((d) => ({ ...d, dni: soloDigitosDNI(e.target.value) }))} error={errores.dni} />
                <Input label="Fecha de nacimiento" type="date" value={datos.fechaNacimiento} onChange={(e) => setDatos((d) => ({ ...d, fechaNacimiento: e.target.value }))} error={errores.fechaNacimiento} />
                <Input label="Email" type="email" value={datos.email} onChange={(e) => setDatos((d) => ({ ...d, email: e.target.value }))} error={errores.email} />
                <Input label="Teléfono" value={datos.telefono} onChange={(e) => setDatos((d) => ({ ...d, telefono: e.target.value }))} />
                <div>
                  <Input label="Contraseña" type="password" value={datos.password} onChange={(e) => setDatos((d) => ({ ...d, password: e.target.value }))} error={errores.password} />
                  <div className="mt-2 flex gap-1">
                    {[0, 1, 2].map((i) => (
                      <span key={i} className={`h-1 flex-1 ${fuerza > i ? 'bg-accent' : 'bg-fill'}`} />
                    ))}
                  </div>
                </div>
                <Input label="Repetir contraseña" type="password" value={datos.repetirPassword} onChange={(e) => setDatos((d) => ({ ...d, repetirPassword: e.target.value }))} error={errores.repetirPassword} />
                <div className="sm:col-span-2">
                  <Button onClick={() => validarDatos() && setPaso(1)}>Continuar</Button>
                </div>
              </div>
            )}

            {paso === 1 && (
              <div className="flex flex-col gap-6">
                <p className="max-w-xl text-sm text-muted">
                  La verificación de identidad es obligatoria para comprar y para
                  publicar en reventa oficial: cada entrada queda ligada a un DNI
                  verificado.
                </p>
                <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                  {(['frente', 'dorso'] as const).map((lado) => (
                    <label
                      key={lado}
                      onDrop={(e) => {
                        e.preventDefault();
                        const file = e.dataTransfer.files[0];
                        if (file) onArchivo(lado, file);
                      }}
                      onDragOver={(e) => e.preventDefault()}
                      className="flex h-40 cursor-pointer flex-col items-center justify-center border border-dashed border-edge text-center text-sm text-muted"
                    >
                      <input
                        type="file"
                        className="sr-only"
                        onChange={(e) => {
                          const file = e.target.files?.[0];
                          if (file) onArchivo(lado, file);
                        }}
                      />
                      {subiendo === lado ? (
                        <div className="w-2/3">
                          <div className="h-1 w-full overflow-hidden bg-fill">
                            <div className="h-full w-full origin-left animate-[shimmer_0.9s_linear] bg-accent" />
                          </div>
                          <p className="mt-2 text-xs">Subiendo…</p>
                        </div>
                      ) : archivos[lado] ? (
                        <p className="text-ink">✓ {archivos[lado]}</p>
                      ) : (
                        <p className="capitalize">{lado} del DNI · arrastrá o hacé click</p>
                      )}
                    </label>
                  ))}
                </div>
                <Button className="self-start" disabled={!archivos.frente || !archivos.dorso} onClick={() => setPaso(2)}>
                  Continuar
                </Button>
              </div>
            )}

            {paso === 2 && (
              <div className="flex max-w-sm flex-col gap-5">
                <p className="text-sm text-graphite">
                  Te enviamos un código de 6 dígitos a {datos.email || 'tu correo'}.
                  Ingresalo para verificar tu cuenta.
                </p>
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
                <button
                  type="button"
                  disabled={reenviarSegundos > 0}
                  onClick={() => setReenviarSegundos(30)}
                  className="font-mono-label self-start text-[11px] text-accent disabled:text-faint"
                >
                  {reenviarSegundos > 0 ? `reenviar en 0:${reenviarSegundos.toString().padStart(2, '0')}` : 'reenviar código'}
                </button>

                <label className="flex items-start gap-2 text-sm text-muted">
                  <input type="checkbox" checked={aceptaTerminos} onChange={(e) => setAceptaTerminos(e.target.checked)} className="mt-1" />
                  Acepto los términos y la política anti-reventa.
                </label>
                <label className="flex items-start gap-2 text-sm text-muted">
                  <input type="checkbox" checked={declaraDatos} onChange={(e) => setDeclaraDatos(e.target.checked)} className="mt-1" />
                  Declaro que los datos ingresados son propios.
                </label>

                <Button
                  disabled={!codigoCompleto || !aceptaTerminos || !declaraDatos}
                  loading={creando}
                  onClick={crearCuenta}
                >
                  Crear cuenta
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
