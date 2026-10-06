// login.jsx — Flujo de autenticación para App Finanzas
// 5 pantallas: welcome · signin · signup · forgot · verify
// Carga DESPUÉS de shared.jsx (necesita Icon, tokens, etc.)

// ─────────────────────────────────────────────────────────
// Layout base (igual que screens.jsx pero sin tab bar)
// ─────────────────────────────────────────────────────────
const AUTH_SCREEN_BASE = {
  height: '100%',
  display: 'flex',
  flexDirection: 'column',
  background: 'var(--bg)',
  color: 'var(--ink)',
  fontFamily: 'var(--font-sans)',
  paddingTop: 56, // status bar
};
const AUTH_BODY = {
  flex: 1,
  display: 'flex',
  flexDirection: 'column',
  overflowY: 'auto',
  overflowX: 'hidden',
  padding: '0 24px 28px',
};

// ─────────────────────────────────────────────────────────
// Marca (logo + wordmark)
// ─────────────────────────────────────────────────────────
function BrandMark({ size = 56 }) {
  return (
    <div style={{
      width: size, height: size, borderRadius: size * 0.32,
      background: 'var(--brand)',
      color: 'var(--brand-ink)',
      display: 'grid', placeItems: 'center',
      boxShadow: 'var(--sh-2)',
    }}>
      <Icon name="wallet" size={Math.round(size * 0.5)} stroke={1.8} />
    </div>
  );
}

function Wordmark({ size = 28 }) {
  return (
    <div style={{
      display: 'inline-flex', alignItems: 'baseline', gap: 2,
      fontFamily: 'var(--font-display)', fontStyle: 'italic',
      fontSize: size, lineHeight: 1, color: 'var(--ink)',
      letterSpacing: '-0.02em',
    }}>
      Finanzas<span style={{ color: 'var(--brand)' }}>.</span>
    </div>
  );
}

// ─────────────────────────────────────────────────────────
// Inputs estilizados
// ─────────────────────────────────────────────────────────
function TextField({
  label, type = 'text', value, onChange, placeholder,
  icon, right, autoFocus = false, name,
}) {
  const [focused, setFocused] = React.useState(false);
  const filled = !!value;
  const lifted = focused || filled;
  return (
    <label style={{
      display: 'block',
      position: 'relative',
      background: 'var(--surface)',
      border: `1px solid ${focused ? 'var(--ink)' : 'var(--line)'}`,
      borderRadius: 14,
      padding: '14px 14px 10px',
      transition: 'border-color 160ms ease, background 160ms ease',
      cursor: 'text',
    }}>
      <div style={{
        display: 'flex', alignItems: 'center', gap: 10,
      }}>
        {icon && (
          <Icon name={icon} size={18} stroke={1.7}
            style={{ color: 'var(--muted)', flexShrink: 0, marginTop: lifted ? 8 : 0 }} />
        )}
        <div style={{ flex: 1, position: 'relative' }}>
          <span style={{
            position: 'absolute', left: 0,
            top: lifted ? -2 : 9,
            fontSize: lifted ? 11 : 15,
            color: lifted ? 'var(--muted)' : 'var(--muted-2)',
            fontWeight: lifted ? 600 : 400,
            letterSpacing: lifted ? '0.04em' : '-0.005em',
            textTransform: lifted ? 'uppercase' : 'none',
            pointerEvents: 'none',
            transition: 'all 160ms ease',
          }}>{label}</span>
          <input
            type={type}
            value={value}
            name={name}
            autoFocus={autoFocus}
            placeholder={focused ? placeholder : ''}
            onChange={(e) => onChange(e.target.value)}
            onFocus={() => setFocused(true)}
            onBlur={() => setFocused(false)}
            style={{
              width: '100%',
              border: 0, background: 'transparent',
              padding: 0,
              paddingTop: lifted ? 14 : 9,
              paddingBottom: 0,
              fontSize: 15, fontWeight: 500,
              fontFamily: 'inherit', color: 'var(--ink)',
              letterSpacing: '-0.005em',
              outline: 'none',
            }}
          />
        </div>
        {right}
      </div>
    </label>
  );
}

// Botón ojo para mostrar/ocultar contraseña
function PwdToggle({ shown, onClick }) {
  return (
    <button type="button" onClick={onClick} aria-label={shown ? 'Ocultar' : 'Mostrar'} style={{
      appearance: 'none', border: 0, background: 'transparent',
      color: 'var(--muted)', cursor: 'pointer',
      padding: 4, margin: -4, display: 'grid', placeItems: 'center',
    }}>
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none"
        stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
        {shown
          ? <><path d="M3 3l18 18" /><path d="M10.6 10.6a2 2 0 0 0 2.8 2.8" /><path d="M9.9 4.2A10 10 0 0 1 22 12c-.6 1.1-1.3 2.1-2.1 3M6.1 6.1A10 10 0 0 0 2 12a10 10 0 0 0 15 4" /></>
          : <><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z" /><circle cx="12" cy="12" r="3" /></>
        }
      </svg>
    </button>
  );
}

// ─────────────────────────────────────────────────────────
// Botones
// ─────────────────────────────────────────────────────────
function PrimaryBtn({ children, onClick, icon, disabled }) {
  return (
    <button type="button" onClick={onClick} disabled={disabled} style={{
      width: '100%', appearance: 'none', border: 0, cursor: disabled ? 'not-allowed' : 'pointer',
      background: 'var(--ink)', color: 'var(--bg)',
      borderRadius: 14, padding: '16px 18px',
      fontFamily: 'inherit', fontSize: 15, fontWeight: 600,
      letterSpacing: '-0.005em',
      display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
      boxShadow: '0 10px 24px -10px rgba(28,24,20,0.35)',
      opacity: disabled ? 0.4 : 1,
      transition: 'transform 120ms ease, opacity 160ms ease',
    }}
    onMouseDown={(e) => !disabled && (e.currentTarget.style.transform = 'scale(0.985)')}
    onMouseUp={(e) => e.currentTarget.style.transform = 'scale(1)'}
    onMouseLeave={(e) => e.currentTarget.style.transform = 'scale(1)'}
    >
      <span>{children}</span>
      {icon && <Icon name={icon} size={18} stroke={2.1} />}
    </button>
  );
}

function GhostBtn({ children, onClick, icon }) {
  return (
    <button type="button" onClick={onClick} style={{
      width: '100%', appearance: 'none', cursor: 'pointer',
      background: 'var(--surface)', color: 'var(--ink)',
      border: '1px solid var(--line)',
      borderRadius: 14, padding: '14px 18px',
      fontFamily: 'inherit', fontSize: 14, fontWeight: 600,
      display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 10,
      letterSpacing: '-0.005em',
    }}>
      {icon}
      <span>{children}</span>
    </button>
  );
}

function TextLink({ children, onClick }) {
  return (
    <button type="button" onClick={onClick} style={{
      appearance: 'none', border: 0, background: 'transparent',
      color: 'var(--ink-2)', cursor: 'pointer',
      fontFamily: 'inherit', fontSize: 13, fontWeight: 600,
      letterSpacing: '-0.005em',
      padding: 4, margin: -4,
      textDecoration: 'underline',
      textDecorationColor: 'var(--line)',
      textUnderlineOffset: 3,
    }}>{children}</button>
  );
}

// Cabecera con back + paso
function AuthHeader({ onBack, step, total }) {
  return (
    <div style={{
      display: 'flex', alignItems: 'center', justifyContent: 'space-between',
      padding: '8px 24px 18px',
    }}>
      <button onClick={onBack} aria-label="Atrás" style={{
        width: 38, height: 38, borderRadius: '50%',
        background: 'var(--surface)',
        border: '1px solid var(--line)',
        color: 'var(--ink-2)',
        display: 'grid', placeItems: 'center', cursor: 'pointer',
      }}>
        <Icon name="chevronL" size={18} stroke={2} />
      </button>
      {step && total && (
        <div style={{
          fontSize: 11, fontWeight: 600, letterSpacing: '0.1em',
          textTransform: 'uppercase', color: 'var(--muted)',
        }}>Paso {step} de {total}</div>
      )}
      <div style={{ width: 38 }} />
    </div>
  );
}

// Divisor con texto al medio
function Divider({ children }) {
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 12,
      color: 'var(--muted)', fontSize: 11, fontWeight: 600,
      letterSpacing: '0.1em', textTransform: 'uppercase',
      padding: '4px 0',
    }}>
      <div style={{ flex: 1, height: 1, background: 'var(--line)' }} />
      <span>{children}</span>
      <div style={{ flex: 1, height: 1, background: 'var(--line)' }} />
    </div>
  );
}

// Logo de Apple / Google (vectoriales minimalistas, sin licencia de marca)
function AppleGlyph() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M16.4 12.5c0-2 1.6-2.9 1.7-3-1-1.4-2.4-1.6-2.9-1.6-1.2-.1-2.4.7-3 .7-.6 0-1.6-.7-2.6-.7-1.4 0-2.6.8-3.3 2-1.4 2.5-.4 6.1.9 8.1.7 1 1.5 2.1 2.5 2 1 0 1.4-.6 2.6-.6s1.6.6 2.6.6c1.1 0 1.8-1 2.4-2 .8-1.1 1.1-2.2 1.1-2.3-.1 0-2.1-.8-2.1-3.2zm-2-5.9c.5-.7.9-1.6.8-2.5-.8 0-1.7.5-2.3 1.2-.5.6-.9 1.5-.8 2.4.8.1 1.7-.4 2.3-1.1z" />
    </svg>
  );
}
function GoogleGlyph() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true">
      <path d="M22 12.2c0-.8-.1-1.4-.2-2H12v3.8h5.7c-.1.9-.7 2.3-2 3.3l-.02.1 2.9 2.3.2 0c1.8-1.7 2.9-4.2 2.9-7.5z" fill="#4285F4"/>
      <path d="M12 22c2.6 0 4.8-.9 6.5-2.3l-3.1-2.4c-.8.6-2 1-3.4 1-2.6 0-4.8-1.7-5.6-4.1l-.1 0-3 2.3 0 .1C4.9 19.7 8.2 22 12 22z" fill="#34A853"/>
      <path d="M6.4 14.2c-.2-.6-.3-1.2-.3-1.9s.1-1.3.3-1.9V8.1l-3-.1c-.7 1.3-1 2.7-1 4.3s.4 3 1 4.3l3-2.4z" fill="#FBBC05"/>
      <path d="M12 6.2c1.8 0 3.1.8 3.8 1.5l2.7-2.7C16.8 3.5 14.6 2.5 12 2.5c-3.8 0-7.1 2.3-8.7 5.6l3 2.4C7.2 7.9 9.4 6.2 12 6.2z" fill="#EB4335"/>
    </svg>
  );
}

// Indicador de fuerza de contraseña
function PwdStrength({ value }) {
  const score = React.useMemo(() => {
    let s = 0;
    if (value.length >= 8) s++;
    if (/[A-Z]/.test(value)) s++;
    if (/[0-9]/.test(value)) s++;
    if (/[^A-Za-z0-9]/.test(value)) s++;
    return s;
  }, [value]);
  const labels = ['', 'Débil', 'Aceptable', 'Buena', 'Excelente'];
  const colors = ['var(--line)', 'var(--expense)', 'var(--alert)', 'var(--income)', 'var(--income)'];
  return (
    <div style={{ marginTop: 8 }}>
      <div style={{
        display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 4,
      }}>
        {[1,2,3,4].map(i => (
          <div key={i} style={{
            height: 4, borderRadius: 999,
            background: score >= i ? colors[score] : 'var(--line)',
            transition: 'background 160ms ease',
          }} />
        ))}
      </div>
      <div style={{
        fontSize: 11, color: 'var(--muted)', marginTop: 6,
        fontWeight: 500, letterSpacing: '0.01em',
        minHeight: 14,
      }}>
        {value.length > 0 ? `Fortaleza: ${labels[score]}` : 'Usa 8+ caracteres con números y mayúsculas.'}
      </div>
    </div>
  );
}

// ═════════════════════════════════════════════════════════
// 1. BIENVENIDA — splash de marca + CTAs
// ═════════════════════════════════════════════════════════
function WelcomeScreen({ onGo }) {
  return (
    <div style={AUTH_SCREEN_BASE}>
      <div style={{ ...AUTH_BODY, padding: '0 28px 36px', justifyContent: 'space-between' }}>
        {/* Hero */}
        <div style={{
          flex: 1, display: 'flex', flexDirection: 'column',
          alignItems: 'flex-start', justifyContent: 'center',
          paddingTop: 40,
        }}>
          <BrandMark size={64} />
          <h1 style={{
            margin: '32px 0 0',
            fontFamily: 'var(--font-display)', fontStyle: 'italic',
            fontSize: 54, lineHeight: 1.02, letterSpacing: '-0.025em',
            fontWeight: 400, color: 'var(--ink)',
            textWrap: 'pretty',
          }}>
            Tu dinero,<br />
            <span style={{ color: 'var(--brand)' }}>sin ruido.</span>
          </h1>
          <p style={{
            margin: '20px 0 0', maxWidth: 300,
            fontSize: 15, lineHeight: 1.45,
            color: 'var(--muted)', fontWeight: 500,
            letterSpacing: '-0.005em', textWrap: 'pretty',
          }}>
            Movimientos, presupuestos y patrimonio en un solo lugar. Privado, claro y diseñado para vivir tranquilo.
          </p>
        </div>

        {/* CTAs */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          <PrimaryBtn icon="arrowRight" onClick={() => onGo('signup')}>
            Empezar gratis
          </PrimaryBtn>
          <button type="button" onClick={() => onGo('signin')} style={{
            appearance: 'none', border: 0, background: 'transparent',
            cursor: 'pointer', padding: '14px 8px 4px',
            fontFamily: 'inherit', fontSize: 14,
            color: 'var(--ink-2)', fontWeight: 600,
            letterSpacing: '-0.005em',
          }}>
            Ya tengo cuenta&nbsp;·&nbsp;<span style={{ color: 'var(--muted)', fontWeight: 500 }}>Iniciar sesión</span>
          </button>
        </div>
      </div>
    </div>
  );
}

// ═════════════════════════════════════════════════════════
// 2. INICIAR SESIÓN
// ═════════════════════════════════════════════════════════
function SignInScreen({ onGo, onSubmit }) {
  const [email, setEmail] = React.useState('');
  const [pwd, setPwd] = React.useState('');
  const [showPwd, setShowPwd] = React.useState(false);
  const canSubmit = email.includes('@') && pwd.length >= 4;
  return (
    <div style={AUTH_SCREEN_BASE}>
      <AuthHeader onBack={() => onGo('welcome')} />
      <div style={AUTH_BODY}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 4 }}>
          <BrandMark size={36} />
          <Wordmark size={22} />
        </div>
        <h1 style={{
          margin: '28px 0 8px',
          fontFamily: 'var(--font-display)', fontStyle: 'italic',
          fontSize: 38, lineHeight: 1.05, letterSpacing: '-0.025em',
          fontWeight: 400, color: 'var(--ink)',
        }}>Hola de nuevo.</h1>
        <p style={{
          margin: 0, fontSize: 14, color: 'var(--muted)',
          fontWeight: 500, letterSpacing: '-0.005em',
        }}>
          Accede para continuar donde lo dejaste.
        </p>

        <form style={{ marginTop: 28, display: 'flex', flexDirection: 'column', gap: 10 }}
          onSubmit={(e) => { e.preventDefault(); if (canSubmit) onSubmit && onSubmit(); }}>
          <TextField label="Correo electrónico" type="email" name="email"
            value={email} onChange={setEmail}
            icon="globe" placeholder="tu@correo.com" autoFocus />
          <TextField label="Contraseña" type={showPwd ? 'text' : 'password'} name="password"
            value={pwd} onChange={setPwd}
            icon="cog" placeholder="••••••••"
            right={<PwdToggle shown={showPwd} onClick={() => setShowPwd(s => !s)} />} />

          <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 2 }}>
            <TextLink onClick={() => onGo('forgot')}>¿Olvidaste tu contraseña?</TextLink>
          </div>

          <div style={{ marginTop: 16 }}>
            <PrimaryBtn icon="arrowRight" disabled={!canSubmit} onClick={() => onSubmit && onSubmit()}>
              Iniciar sesión
            </PrimaryBtn>
          </div>
        </form>

        {/* Biometric quick login */}
        <button type="button" onClick={() => onSubmit && onSubmit()} style={{
          appearance: 'none', cursor: 'pointer',
          background: 'transparent', border: 0,
          marginTop: 18,
          display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 10,
          padding: '12px 8px',
          color: 'var(--ink-2)',
        }}>
          <div style={{
            width: 36, height: 36, borderRadius: '50%',
            border: '1.5px solid var(--ink-2)',
            display: 'grid', placeItems: 'center',
          }}>
            {/* Face ID glyph */}
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none"
              stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
              <path d="M4 8V6a2 2 0 0 1 2-2h2M20 8V6a2 2 0 0 0-2-2h-2M4 16v2a2 2 0 0 0 2 2h2M20 16v2a2 2 0 0 1-2 2h-2" />
              <path d="M9 10v2M15 10v2M12 9v4l-1.5 1M9 16c1 1 4.5 1 6 0" />
            </svg>
          </div>
          <span style={{
            fontFamily: 'inherit', fontSize: 13, fontWeight: 600,
            letterSpacing: '-0.005em',
          }}>Entrar con Face ID</span>
        </button>

        <div style={{ marginTop: 8 }}>
          <Divider>o continúa con</Divider>
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10, marginTop: 14 }}>
          <GhostBtn icon={<AppleGlyph />}>Apple</GhostBtn>
          <GhostBtn icon={<GoogleGlyph />}>Google</GhostBtn>
        </div>

        <div style={{
          marginTop: 28, textAlign: 'center', fontSize: 13,
          color: 'var(--muted)', fontWeight: 500,
        }}>
          ¿No tienes cuenta? <TextLink onClick={() => onGo('signup')}>Crear una</TextLink>
        </div>
      </div>
    </div>
  );
}

// ═════════════════════════════════════════════════════════
// 3. CREAR CUENTA
// ═════════════════════════════════════════════════════════
function SignUpScreen({ onGo }) {
  const [name, setName] = React.useState('');
  const [email, setEmail] = React.useState('');
  const [pwd, setPwd] = React.useState('');
  const [showPwd, setShowPwd] = React.useState(false);
  const [agreed, setAgreed] = React.useState(false);
  const canSubmit = name.length >= 2 && email.includes('@') && pwd.length >= 8 && agreed;

  return (
    <div style={AUTH_SCREEN_BASE}>
      <AuthHeader onBack={() => onGo('welcome')} step={1} total={2} />
      <div style={AUTH_BODY}>
        <h1 style={{
          margin: '4px 0 8px',
          fontFamily: 'var(--font-display)', fontStyle: 'italic',
          fontSize: 38, lineHeight: 1.05, letterSpacing: '-0.025em',
          fontWeight: 400, color: 'var(--ink)',
        }}>Crear cuenta.</h1>
        <p style={{
          margin: 0, fontSize: 14, color: 'var(--muted)',
          fontWeight: 500, letterSpacing: '-0.005em',
        }}>
          Te avisamos: solo recordamos tus números, nunca te juzgamos.
        </p>

        <div style={{ marginTop: 24, display: 'flex', flexDirection: 'column', gap: 10 }}>
          <TextField label="Nombre" name="name" value={name} onChange={setName}
            icon="user" placeholder="Marta" autoFocus />
          <TextField label="Correo electrónico" type="email" name="email"
            value={email} onChange={setEmail}
            icon="globe" placeholder="tu@correo.com" />
          <TextField label="Contraseña" type={showPwd ? 'text' : 'password'} name="password"
            value={pwd} onChange={setPwd}
            icon="cog" placeholder="Mínimo 8 caracteres"
            right={<PwdToggle shown={showPwd} onClick={() => setShowPwd(s => !s)} />} />
          <PwdStrength value={pwd} />
        </div>

        {/* Términos */}
        <label style={{
          display: 'flex', alignItems: 'flex-start', gap: 10,
          marginTop: 18, cursor: 'pointer',
          fontSize: 13, lineHeight: 1.5,
          color: 'var(--muted)', fontWeight: 500,
          letterSpacing: '-0.005em',
        }}>
          <span style={{
            width: 20, height: 20, borderRadius: 6,
            border: `1.5px solid ${agreed ? 'var(--ink)' : 'var(--line)'}`,
            background: agreed ? 'var(--ink)' : 'transparent',
            color: 'var(--bg)',
            display: 'grid', placeItems: 'center',
            flexShrink: 0, marginTop: 1,
            transition: 'all 140ms ease',
          }} onClick={() => setAgreed(a => !a)}>
            {agreed && <Icon name="check" size={14} stroke={2.4} />}
          </span>
          <span onClick={() => setAgreed(a => !a)}>
            Acepto los <u style={{ color: 'var(--ink-2)' }}>Términos</u> y la <u style={{ color: 'var(--ink-2)' }}>Política de privacidad</u>. Mis datos no se venden a terceros.
          </span>
        </label>

        <div style={{ marginTop: 22 }}>
          <PrimaryBtn icon="arrowRight" disabled={!canSubmit} onClick={() => onGo('verify')}>
            Crear cuenta
          </PrimaryBtn>
        </div>

        <div style={{ marginTop: 14 }}>
          <Divider>o regístrate con</Divider>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10, marginTop: 14 }}>
          <GhostBtn icon={<AppleGlyph />}>Apple</GhostBtn>
          <GhostBtn icon={<GoogleGlyph />}>Google</GhostBtn>
        </div>

        <div style={{
          marginTop: 22, textAlign: 'center', fontSize: 13,
          color: 'var(--muted)', fontWeight: 500,
        }}>
          ¿Ya tienes cuenta? <TextLink onClick={() => onGo('signin')}>Iniciar sesión</TextLink>
        </div>
      </div>
    </div>
  );
}

// ═════════════════════════════════════════════════════════
// 4. RECUPERAR CONTRASEÑA
// ═════════════════════════════════════════════════════════
function ForgotScreen({ onGo }) {
  const [email, setEmail] = React.useState('');
  const [sent, setSent] = React.useState(false);
  const canSubmit = email.includes('@');

  return (
    <div style={AUTH_SCREEN_BASE}>
      <AuthHeader onBack={() => onGo('signin')} />
      <div style={AUTH_BODY}>
        <div style={{
          width: 56, height: 56, borderRadius: 18,
          background: 'var(--brand-soft)', color: 'var(--brand)',
          display: 'grid', placeItems: 'center',
          marginBottom: 22,
        }}>
          <Icon name="bell" size={26} stroke={1.7} />
        </div>
        <h1 style={{
          margin: '0 0 8px',
          fontFamily: 'var(--font-display)', fontStyle: 'italic',
          fontSize: 38, lineHeight: 1.05, letterSpacing: '-0.025em',
          fontWeight: 400, color: 'var(--ink)',
        }}>Recuperar acceso.</h1>
        <p style={{
          margin: 0, fontSize: 14, color: 'var(--muted)',
          fontWeight: 500, letterSpacing: '-0.005em',
          lineHeight: 1.5, textWrap: 'pretty',
        }}>
          Escribe tu correo y te enviaremos un enlace para crear una nueva contraseña.
        </p>

        <div style={{ marginTop: 24 }}>
          <TextField label="Correo electrónico" type="email" name="email"
            value={email} onChange={setEmail}
            icon="globe" placeholder="tu@correo.com" autoFocus />
        </div>

        <div style={{ marginTop: 18 }}>
          <PrimaryBtn icon={sent ? 'check' : 'arrowRight'} disabled={!canSubmit} onClick={() => setSent(true)}>
            {sent ? 'Enlace enviado' : 'Enviar enlace de recuperación'}
          </PrimaryBtn>
        </div>

        {sent && (
          <div style={{
            marginTop: 16, padding: '14px 14px',
            background: 'var(--income-soft)',
            border: '1px solid transparent',
            borderRadius: 12,
            display: 'flex', alignItems: 'flex-start', gap: 10,
            color: 'var(--income)',
          }}>
            <Icon name="check" size={18} stroke={2.2} />
            <div style={{ fontSize: 13, fontWeight: 500, letterSpacing: '-0.005em', lineHeight: 1.45 }}>
              Si <b style={{ fontWeight: 700 }}>{email}</b> existe en App Finanzas, te llegará un correo en unos segundos. Revisa tu bandeja de spam por si acaso.
            </div>
          </div>
        )}

        <div style={{
          marginTop: 26, textAlign: 'center', fontSize: 13,
          color: 'var(--muted)', fontWeight: 500,
        }}>
          ¿Lo recordaste? <TextLink onClick={() => onGo('signin')}>Volver a iniciar sesión</TextLink>
        </div>
      </div>
    </div>
  );
}

// ═════════════════════════════════════════════════════════
// 5. VERIFICACIÓN OTP (tras crear cuenta)
// ═════════════════════════════════════════════════════════
function VerifyScreen({ onGo, email = 'marta@correo.com' }) {
  const [digits, setDigits] = React.useState(['', '', '', '', '', '']);
  const refs = React.useRef([]);
  const code = digits.join('');
  const filled = digits.every(d => d !== '');

  const setDigit = (i, v) => {
    const ch = v.replace(/\D/g, '').slice(-1);
    setDigits(prev => {
      const next = [...prev];
      next[i] = ch;
      return next;
    });
    if (ch && i < 5) refs.current[i+1]?.focus();
  };
  const onKeyDown = (i, e) => {
    if (e.key === 'Backspace' && !digits[i] && i > 0) refs.current[i-1]?.focus();
  };

  return (
    <div style={AUTH_SCREEN_BASE}>
      <AuthHeader onBack={() => onGo('signup')} step={2} total={2} />
      <div style={AUTH_BODY}>
        <div style={{
          width: 56, height: 56, borderRadius: 18,
          background: 'var(--brand-soft)', color: 'var(--brand)',
          display: 'grid', placeItems: 'center',
          marginBottom: 22,
        }}>
          <Icon name="receipt" size={26} stroke={1.7} />
        </div>
        <h1 style={{
          margin: '0 0 8px',
          fontFamily: 'var(--font-display)', fontStyle: 'italic',
          fontSize: 38, lineHeight: 1.05, letterSpacing: '-0.025em',
          fontWeight: 400, color: 'var(--ink)',
        }}>Revisa tu email.</h1>
        <p style={{
          margin: 0, fontSize: 14, color: 'var(--muted)',
          fontWeight: 500, letterSpacing: '-0.005em',
          lineHeight: 1.5, textWrap: 'pretty',
        }}>
          Hemos enviado un código de 6 dígitos a <b style={{ color: 'var(--ink-2)', fontWeight: 600 }}>{email}</b>. Introdúcelo abajo.
        </p>

        {/* OTP boxes */}
        <div style={{
          marginTop: 28,
          display: 'grid', gridTemplateColumns: 'repeat(6, 1fr)', gap: 8,
        }}>
          {digits.map((d, i) => (
            <input key={i}
              ref={el => refs.current[i] = el}
              value={d}
              inputMode="numeric"
              maxLength={1}
              onChange={(e) => setDigit(i, e.target.value)}
              onKeyDown={(e) => onKeyDown(i, e)}
              style={{
                aspectRatio: '1 / 1.15',
                width: '100%',
                textAlign: 'center',
                fontFamily: 'var(--font-display)', fontStyle: 'italic',
                fontSize: 30, fontWeight: 400,
                color: 'var(--ink)',
                background: 'var(--surface)',
                border: `1.5px solid ${d ? 'var(--ink)' : 'var(--line)'}`,
                borderRadius: 12,
                outline: 'none',
                transition: 'border-color 160ms ease',
                fontVariantNumeric: 'tabular-nums',
              }}
            />
          ))}
        </div>

        <div style={{
          marginTop: 14, textAlign: 'center', fontSize: 13,
          color: 'var(--muted)', fontWeight: 500,
        }}>
          ¿No te ha llegado? <TextLink onClick={() => {}}>Reenviar código</TextLink>
        </div>

        <div style={{ marginTop: 22 }}>
          <PrimaryBtn icon="check" disabled={!filled} onClick={() => onGo('done')}>
            Verificar y entrar
          </PrimaryBtn>
        </div>

        <div style={{
          marginTop: 18, textAlign: 'center', fontSize: 12,
          color: 'var(--muted)', fontWeight: 500,
          letterSpacing: '0.01em',
        }}>
          ¿Email incorrecto? <TextLink onClick={() => onGo('signup')}>Cambiar correo</TextLink>
        </div>
      </div>
    </div>
  );
}

// ═════════════════════════════════════════════════════════
// "Sesión iniciada" — confirmación intermedia antes de la app
// ═════════════════════════════════════════════════════════
function DoneScreen({ onGo }) {
  return (
    <div style={AUTH_SCREEN_BASE}>
      <div style={{ ...AUTH_BODY, alignItems: 'center', justifyContent: 'center', textAlign: 'center', padding: '0 28px 36px' }}>
        <div style={{
          width: 72, height: 72, borderRadius: '50%',
          background: 'var(--income-soft)', color: 'var(--income)',
          display: 'grid', placeItems: 'center',
          marginBottom: 22, marginTop: 80,
        }}>
          <Icon name="check" size={32} stroke={2.4} />
        </div>
        <h1 style={{
          margin: '0 0 10px',
          fontFamily: 'var(--font-display)', fontStyle: 'italic',
          fontSize: 40, lineHeight: 1.05, letterSpacing: '-0.025em',
          fontWeight: 400, color: 'var(--ink)',
        }}>Todo listo.</h1>
        <p style={{
          margin: 0, fontSize: 15, color: 'var(--muted)',
          fontWeight: 500, lineHeight: 1.5, maxWidth: 280,
        }}>
          Tu cuenta está activa. Vamos a conectar tu primer banco para empezar.
        </p>
        <div style={{ flex: 1 }} />
        <a href="index.html" style={{ width: '100%', textDecoration: 'none' }}>
          <PrimaryBtn icon="arrowRight">Entrar en la app</PrimaryBtn>
        </a>
        <button type="button" onClick={() => onGo('welcome')} style={{
          appearance: 'none', border: 0, background: 'transparent',
          cursor: 'pointer', padding: '14px 8px 4px',
          fontFamily: 'inherit', fontSize: 13,
          color: 'var(--muted)', fontWeight: 500,
        }}>Volver al inicio</button>
      </div>
    </div>
  );
}

// Exportar al global
Object.assign(window, {
  WelcomeScreen, SignInScreen, SignUpScreen, ForgotScreen, VerifyScreen, DoneScreen,
  BrandMark, Wordmark, TextField, PrimaryBtn, GhostBtn, TextLink,
});
