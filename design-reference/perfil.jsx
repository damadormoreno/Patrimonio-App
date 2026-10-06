// perfil.jsx — Pantalla de edición de perfil (push desde Ajustes)
// Foto · nombre y apellidos · correo · seguridad · copia de seguridad (iCloud / Drive según plataforma)
// Carga DESPUÉS de shared.jsx y screens.jsx.

// Iconos extra
ICONS.camera = "M3 9a1 1 0 0 1 1-1h2.5l1.8-2.6A1 1 0 0 1 9.1 5h5.8a1 1 0 0 1 .8.4L17.5 8H20a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1zM12 17a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z";
ICONS.cloud  = "M7 18h10a4 4 0 0 0 .8-7.9A6 6 0 0 0 6.2 9.5 4.2 4.2 0 0 0 7 18z";
ICONS.lock   = "M7 11V8a5 5 0 0 1 10 0v3M5 11h14v9a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1zM12 15v2.5";
ICONS.faceid = "M4 8V6a2 2 0 0 1 2-2h2M20 8V6a2 2 0 0 0-2-2h-2M4 16v2a2 2 0 0 0 2 2h2M20 16v2a2 2 0 0 1-2 2h-2M9 10v1.5M15 10v1.5M12 10v3.5l-1.2.8M9.5 16.5c1.4 1 3.6 1 5 0";
ICONS.finger = "M12 11a2 2 0 0 0-2 2c0 2.5-.4 4.4-1.2 6M12 11a2 2 0 0 1 2 2c0 3-.3 5-1 7M8.5 9.5A5 5 0 0 1 17 13c0 2.4-.2 4.4-.7 6M6.2 12c-.1.4-.2.7-.2 1 0 1.8-.3 3.4-.8 4.7M5 8a8 8 0 0 1 14 2";

// Nombre del proveedor según plataforma
function backupProvider(platform) {
  return platform === 'android'
    ? { name: 'Google Drive', bio: 'Huella dactilar', bioIcon: 'finger' }
    : { name: 'iCloud', bio: 'Face ID', bioIcon: 'faceid' };
}

// ─────────────────────────────────────────────────────────
// Fila de perfil: label + input inline
// ─────────────────────────────────────────────────────────
function ProfileField({ label, value, onChange, type = 'text', placeholder, last }) {
  return (
    <label style={{
      display: 'flex', alignItems: 'center', gap: 12,
      padding: '13px 16px', cursor: 'text',
      borderBottom: last ? 0 : '1px solid var(--line-2)',
    }}>
      <span style={{
        width: 84, flexShrink: 0,
        fontSize: 13, color: 'var(--muted)', fontWeight: 500,
      }}>{label}</span>
      <input
        type={type}
        value={value}
        placeholder={placeholder}
        onChange={(e) => onChange(e.target.value)}
        style={{
          flex: 1, minWidth: 0,
          appearance: 'none', border: 0, background: 'transparent',
          outline: 0, fontFamily: 'inherit',
          fontSize: 15, fontWeight: 500, color: 'var(--ink)',
          letterSpacing: '-0.005em', padding: 0,
        }} />
    </label>
  );
}

// Fila con toggle controlado
function ProfileToggleRow({ icon, label, sub, value, onChange, last }) {
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 12,
      padding: '13px 16px',
      borderBottom: last ? 0 : '1px solid var(--line-2)',
    }}>
      <div style={{
        width: 30, height: 30, borderRadius: 8,
        background: 'var(--bg-2)', color: 'var(--ink-2)',
        display: 'grid', placeItems: 'center', flexShrink: 0,
      }}>
        <Icon name={icon} size={16} />
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 15, color: 'var(--ink)' }}>{label}</div>
        {sub && <div style={{ fontSize: 11.5, color: 'var(--muted)', marginTop: 1 }}>{sub}</div>}
      </div>
      <button onClick={() => onChange(!value)} aria-label={label} style={{
        position: 'relative', width: 42, height: 24, borderRadius: 999,
        background: value ? 'var(--income)' : 'var(--line)',
        border: 0, cursor: 'pointer', padding: 0, flexShrink: 0,
        transition: 'background 200ms ease',
      }}>
        <span style={{
          position: 'absolute', top: 2, left: value ? 20 : 2,
          width: 20, height: 20, borderRadius: '50%',
          background: 'var(--surface-2)',
          boxShadow: '0 1px 3px rgba(0,0,0,0.2)',
          transition: 'left 200ms ease',
        }} />
      </button>
    </div>
  );
}

// Fila estática con valor (reutiliza patrón de Ajustes)
function ProfileValueRow({ icon, label, value, danger, onClick, last }) {
  const Tag = onClick ? 'button' : 'div';
  return (
    <Tag onClick={onClick} style={{
      width: '100%', appearance: 'none', border: 0, background: 'transparent',
      display: 'flex', alignItems: 'center', gap: 12,
      padding: '13px 16px', textAlign: 'left',
      fontFamily: 'inherit', cursor: onClick ? 'pointer' : 'default',
      borderBottom: last ? 0 : '1px solid var(--line-2)',
    }}>
      <div style={{
        width: 30, height: 30, borderRadius: 8,
        background: danger ? 'var(--expense-soft)' : 'var(--bg-2)',
        color: danger ? 'var(--expense)' : 'var(--ink-2)',
        display: 'grid', placeItems: 'center', flexShrink: 0,
      }}>
        <Icon name={icon} size={16} />
      </div>
      <div style={{ flex: 1, fontSize: 15, color: danger ? 'var(--expense)' : 'var(--ink)' }}>{label}</div>
      {value !== undefined && (
        <span style={{ fontSize: 13, color: 'var(--muted)', fontFeatureSettings: '"tnum"' }}>{value}</span>
      )}
      {onClick && <Icon name="chevronR" size={16} color="var(--muted)" />}
    </Tag>
  );
}

// ═════════════════════════════════════════════════════════
// PANTALLA · Perfil
// ═════════════════════════════════════════════════════════
function PerfilScreen({ profile, setProfile, platform = 'ios', onBack }) {
  const p = profile;
  const set = (patch) => setProfile(prev => ({ ...prev, ...patch }));
  const prov = backupProvider(platform);
  const [photoMenu, setPhotoMenu] = React.useState(false);
  const [lastBackup, setLastBackup] = React.useState('Hoy · 14:32');

  const initials = `${(p.firstName || 'M').charAt(0)}${(p.lastName || '').charAt(0)}`.toUpperCase();

  return (
    <div style={SCREEN_BASE}>
      {/* Cabecera con back */}
      <header style={{
        padding: '8px 20px 14px',
        display: 'flex', alignItems: 'center', gap: 12,
      }}>
        <button onClick={onBack} aria-label="Volver a Ajustes" style={{
          width: 38, height: 38, borderRadius: '50%',
          background: 'var(--surface)', border: '1px solid var(--line)',
          color: 'var(--ink-2)', display: 'grid', placeItems: 'center', cursor: 'pointer',
        }}>
          <Icon name="chevronL" size={18} stroke={2} />
        </button>
        <h1 style={{
          margin: 0, fontFamily: 'var(--font-sans)',
          fontSize: 22, fontWeight: 600, letterSpacing: '-0.02em',
          color: 'var(--ink)',
        }}>Perfil</h1>
      </header>

      <div className="no-scrollbar" style={SCROLL_AREA}>
        {/* Avatar + cambiar foto */}
        <div style={{
          display: 'flex', flexDirection: 'column', alignItems: 'center',
          paddingTop: 8, paddingBottom: 4, position: 'relative',
        }}>
          <div style={{ position: 'relative' }}>
            <div style={{
              width: 92, height: 92, borderRadius: '50%',
              background: p.photo
                ? 'linear-gradient(135deg, var(--brand), var(--cat-fun))'
                : 'var(--brand-soft)',
              color: p.photo ? 'var(--surface-2)' : 'var(--brand)',
              display: 'grid', placeItems: 'center',
              fontFamily: 'var(--font-display)', fontStyle: 'italic',
              fontSize: 36, letterSpacing: '-0.01em',
              border: '1px solid var(--line)',
              transition: 'background 240ms ease',
            }}>
              {initials}
            </div>
            <button
              onClick={() => setPhotoMenu(m => !m)}
              aria-label="Cambiar foto"
              style={{
                position: 'absolute', right: -2, bottom: -2,
                width: 32, height: 32, borderRadius: '50%',
                background: 'var(--ink)', color: 'var(--bg)',
                border: '2px solid var(--bg)',
                display: 'grid', placeItems: 'center', cursor: 'pointer',
              }}>
              <Icon name="camera" size={15} stroke={1.9} />
            </button>
          </div>

          {/* Menú de foto */}
          {photoMenu && (
            <>
              <div onClick={() => setPhotoMenu(false)} style={{ position: 'fixed', inset: 0, zIndex: 30 }} />
              <div style={{
                position: 'absolute', top: 104, zIndex: 31,
                background: 'var(--surface-2)', border: '1px solid var(--line)',
                borderRadius: 16, padding: 6, minWidth: 200,
                boxShadow: 'var(--sh-2)',
                display: 'flex', flexDirection: 'column', gap: 2,
              }}>
                {[
                  { icon: 'camera', label: 'Hacer foto',         action: () => set({ photo: true }) },
                  { icon: 'upload', label: 'Elegir de galería',  action: () => set({ photo: true }) },
                  ...(p.photo ? [{ icon: 'trash', label: 'Quitar foto', action: () => set({ photo: false }), danger: true }] : []),
                ].map(o => (
                  <button key={o.label}
                    onClick={() => { o.action(); setPhotoMenu(false); }}
                    style={{
                      appearance: 'none', border: 0, background: 'transparent',
                      display: 'flex', alignItems: 'center', gap: 10,
                      padding: '10px 12px', borderRadius: 10,
                      fontFamily: 'inherit', fontSize: 14, fontWeight: 500,
                      color: o.danger ? 'var(--expense)' : 'var(--ink)',
                      cursor: 'pointer', textAlign: 'left',
                    }}>
                    <Icon name={o.icon} size={17} stroke={1.8}
                      style={{ color: o.danger ? 'var(--expense)' : 'var(--ink-2)' }} />
                    {o.label}
                  </button>
                ))}
              </div>
            </>
          )}

          <div style={{
            marginTop: 12, fontSize: 16, fontWeight: 600,
            color: 'var(--ink)', letterSpacing: '-0.01em',
          }}>{p.firstName} {p.lastName}</div>
          <div style={{ fontSize: 12, color: 'var(--muted)', marginTop: 2 }}>
            {p.backup ? `Sincronizada con ${prov.name}` : 'Cuenta local · offline'}
          </div>
        </div>

        {/* Datos personales */}
        <SettingsSection title="Datos personales" />
        <SettingsCard>
          <ProfileField label="Nombre" value={p.firstName} placeholder="Nombre"
            onChange={(v) => set({ firstName: v })} />
          <ProfileField label="Apellidos" value={p.lastName} placeholder="Apellidos"
            onChange={(v) => set({ lastName: v })} />
          <ProfileField label="Correo" type="email" value={p.email} placeholder="tu@correo.com"
            onChange={(v) => set({ email: v })} last />
        </SettingsCard>

        {/* Seguridad */}
        <SettingsSection title="Seguridad" />
        <SettingsCard>
          <ProfileToggleRow icon={prov.bioIcon}
            label={`Bloqueo con ${prov.bio}`}
            sub="Pedir al abrir la app"
            value={p.bioLock}
            onChange={(v) => set({ bioLock: v })} />
          <ProfileValueRow icon="lock" label="Código PIN" value="••••" onClick={() => {}} />
          <ProfileValueRow icon="bell" label="Bloqueo automático" value="Al minuto" onClick={() => {}} last />
        </SettingsCard>

        {/* Copia de seguridad */}
        <SettingsSection title="Copia de seguridad" />
        <SettingsCard>
          <ProfileToggleRow icon="cloud"
            label={`Copia en ${prov.name}`}
            sub={platform === 'android' ? 'Cuenta de Google · marta@gmail.com' : 'ID de Apple · marta@icloud.com'}
            value={p.backup}
            onChange={(v) => set({ backup: v })}
            last={!p.backup} />
          {p.backup && (
            <>
              <ProfileValueRow icon="check" label="Última copia" value={lastBackup} />
              <ProfileValueRow icon="calendar" label="Frecuencia" value="Diaria" onClick={() => {}} />
              <div style={{ padding: '12px 16px', borderTop: '1px solid var(--line-2)' }}>
                <button
                  onClick={() => setLastBackup('Ahora mismo')}
                  style={{
                    width: '100%', appearance: 'none', cursor: 'pointer',
                    background: 'var(--bg-2)', color: 'var(--ink)',
                    border: '1px solid var(--line)',
                    borderRadius: 12, padding: '11px 14px',
                    fontFamily: 'inherit', fontSize: 13.5, fontWeight: 600,
                    display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
                  }}>
                  <Icon name="upload" size={15} stroke={2} />
                  Hacer copia ahora
                </button>
              </div>
            </>
          )}
        </SettingsCard>
        <div style={{
          marginTop: 8, fontSize: 11, color: 'var(--muted-2)',
          lineHeight: 1.5, padding: '0 4px',
        }}>
          La copia incluye movimientos, presupuestos y patrimonio.
          Se cifra antes de salir del dispositivo.
        </div>

        {/* Zona de peligro */}
        <SettingsSection title="Cuenta" />
        <SettingsCard>
          <ProfileValueRow icon="download" label="Descargar mis datos" onClick={() => {}} />
          <ProfileValueRow icon="trash" label="Eliminar cuenta y datos" danger onClick={() => {}} last />
        </SettingsCard>

        <div style={{
          marginTop: 18, marginBottom: 4,
          fontSize: 11, color: 'var(--muted-2)',
          textAlign: 'center', letterSpacing: '0.04em',
        }}>
          Los cambios se guardan automáticamente.
        </div>
      </div>
    </div>
  );
}

// Exportar al global
Object.assign(window, {
  PerfilScreen, backupProvider,
});
