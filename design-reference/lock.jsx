// lock.jsx — Pantalla de bloqueo al abrir la app (PIN + Face ID / huella)
// Carga DESPUÉS de shared.jsx y perfil.jsx (usa ICONS.faceid / ICONS.finger, backupProvider).

const LOCK_PIN = '1234'; // PIN del prototipo

function LockScreen({ profile, platform = 'ios', onUnlock }) {
  const [digits, setDigits] = React.useState('');
  const [error, setError] = React.useState(false);
  const [failed, setFailed] = React.useState(false);   // ya falló una vez → mostrar pista
  const [scanning, setScanning] = React.useState(false);
  const [leaving, setLeaving] = React.useState(false);

  const prov = backupProvider(platform);
  const bioLabel = platform === 'android' ? 'huella' : 'Face ID';

  const unlock = () => {
    setLeaving(true);
    setTimeout(onUnlock, 260);
  };

  const press = (d) => {
    if (scanning || leaving || digits.length >= 4) return;
    const next = digits + d;
    setDigits(next);
    setError(false);
    if (next.length === 4) {
      if (next === LOCK_PIN) {
        setTimeout(unlock, 180);
      } else {
        setTimeout(() => {
          setError(true);
          setFailed(true);
          setDigits('');
        }, 220);
      }
    }
  };

  const backspace = () => {
    setDigits(prev => prev.slice(0, -1));
    setError(false);
  };

  const bioUnlock = () => {
    if (scanning || leaving) return;
    setScanning(true);
    setTimeout(unlock, 1000);
  };

  const keyStyle = {
    width: 72, height: 72, borderRadius: '50%',
    appearance: 'none', cursor: 'pointer',
    background: 'var(--surface)',
    border: '1px solid var(--line)',
    color: 'var(--ink)',
    fontFamily: 'var(--font-sans)',
    fontSize: 26, fontWeight: 500,
    display: 'grid', placeItems: 'center',
    letterSpacing: '-0.01em',
    transition: 'background 120ms ease, transform 120ms ease',
    justifySelf: 'center',
  };

  return (
    <div style={{
      position: 'absolute', inset: 0, zIndex: 60,
      background: 'var(--bg)', color: 'var(--ink)',
      fontFamily: 'var(--font-sans)',
      display: 'flex', flexDirection: 'column',
      paddingTop: 56,
      opacity: leaving ? 0 : 1,
      transform: leaving ? 'scale(1.04)' : 'scale(1)',
      transition: 'opacity 260ms ease, transform 260ms ease',
    }}>
      <style>{`
        @keyframes lock-shake {
          0%, 100% { transform: translateX(0); }
          20% { transform: translateX(-8px); }
          40% { transform: translateX(8px); }
          60% { transform: translateX(-5px); }
          80% { transform: translateX(5px); }
        }
        @keyframes lock-pulse {
          0%, 100% { transform: scale(1); opacity: 1; }
          50% { transform: scale(1.12); opacity: 0.6; }
        }
        .lock-key:active { background: var(--bg-2) !important; transform: scale(0.94); }
      `}</style>

      {/* Cabecera */}
      <div style={{
        flex: 1, display: 'flex', flexDirection: 'column',
        alignItems: 'center', justifyContent: 'center', gap: 0,
        paddingBottom: 8,
      }}>
        <div style={{
          width: 52, height: 52, borderRadius: 16,
          background: 'var(--brand)', color: 'var(--brand-ink, var(--surface-2))',
          display: 'grid', placeItems: 'center',
          boxShadow: 'var(--sh-2)',
          animation: scanning ? 'lock-pulse 900ms ease infinite' : 'none',
        }}>
          <Icon name={scanning ? prov.bioIcon : 'wallet'} size={26} stroke={1.8} />
        </div>
        <h1 style={{
          margin: '18px 0 4px',
          fontFamily: 'var(--font-display)', fontStyle: 'italic',
          fontSize: 32, lineHeight: 1.05, letterSpacing: '-0.02em',
          fontWeight: 400, color: 'var(--ink)',
        }}>Hola, {profile?.firstName || 'Marta'}.</h1>
        <p style={{
          margin: 0, fontSize: 13.5, fontWeight: 500,
          color: error ? 'var(--expense)' : 'var(--muted)',
          letterSpacing: '-0.005em',
          transition: 'color 160ms ease',
        }}>
          {scanning ? 'Reconociendo…'
            : error ? 'PIN incorrecto, prueba otra vez'
            : 'Introduce tu PIN para entrar'}
        </p>

        {/* Puntos del PIN */}
        <div style={{
          display: 'flex', gap: 14, marginTop: 22,
          animation: error ? 'lock-shake 400ms ease' : 'none',
        }}>
          {[0, 1, 2, 3].map(i => {
            const on = i < digits.length;
            return (
              <span key={i} style={{
                width: 13, height: 13, borderRadius: '50%',
                background: on ? 'var(--ink)' : 'transparent',
                border: `1.5px solid ${on ? 'var(--ink)' : 'var(--muted-2)'}`,
                transition: 'background 140ms ease, border-color 140ms ease',
              }} />
            );
          })}
        </div>

        {failed && !scanning && (
          <div style={{
            marginTop: 14, fontSize: 11.5, color: 'var(--muted-2)',
            letterSpacing: '0.02em',
          }}>
            PIN del prototipo: 1234
          </div>
        )}
      </div>

      {/* Teclado */}
      <div style={{
        display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)',
        gap: 14, padding: '0 44px',
      }}>
        {['1','2','3','4','5','6','7','8','9'].map(d => (
          <button key={d} className="lock-key" onClick={() => press(d)} style={keyStyle}>{d}</button>
        ))}

        {/* Biometría */}
        <button className="lock-key" onClick={bioUnlock}
          aria-label={`Entrar con ${bioLabel}`}
          style={{ ...keyStyle, background: 'transparent', border: '1px solid transparent', color: 'var(--ink-2)' }}>
          <Icon name={prov.bioIcon} size={28} stroke={1.6} />
        </button>

        <button className="lock-key" onClick={() => press('0')} style={keyStyle}>0</button>

        {/* Borrar */}
        <button className="lock-key" onClick={backspace}
          aria-label="Borrar"
          style={{ ...keyStyle, background: 'transparent', border: '1px solid transparent', color: 'var(--ink-2)' }}>
          <Icon name="arrowLeft" size={24} stroke={1.8} />
        </button>
      </div>

      {/* Pie */}
      <div style={{
        padding: '22px 0 34px', display: 'flex', justifyContent: 'center',
      }}>
        <button onClick={bioUnlock} style={{
          appearance: 'none', border: 0, background: 'transparent',
          color: 'var(--ink-2)', cursor: 'pointer',
          fontFamily: 'inherit', fontSize: 13, fontWeight: 600,
          letterSpacing: '-0.005em',
          textDecoration: 'underline',
          textDecorationColor: 'var(--line)',
          textUnderlineOffset: 3,
        }}>
          Entrar con {bioLabel}
        </button>
      </div>
    </div>
  );
}

// Exportar al global
Object.assign(window, { LockScreen });
