// app.jsx — Shell de App Finanzas
// Renderiza: marco iOS + app interna + tweaks (tema, plataforma)

const TWEAK_DEFAULTS = /*EDITMODE-BEGIN*/{
  "dark": false,
  "tab": "home",
  "platform": "ios"
}/*EDITMODE-END*/;

function App() {
  const [t, setTweak] = useTweaks(TWEAK_DEFAULTS);

  // Estado de la app real (no se persiste)
  const [txs, setTxs] = React.useState(SAMPLE_TXS);
  const [assets, setAssets] = React.useState(SAMPLE_ASSETS);
  const [liabs, setLiabs] = React.useState(SAMPLE_LIABS);
  const [accGroups, setAccGroups] = React.useState(SAMPLE_ACCOUNT_GROUPS);
  const [profile, setProfile] = React.useState({
    firstName: 'Marta', lastName: 'Aldea',
    email: 'marta@aldea.es',
    photo: false, bioLock: true, backup: false,
  });
  const [locked, setLocked] = React.useState(true); // bloqueo al abrir
  const [patView, setPatView] = React.useState('activos'); // 'activos' | 'pasivos'
  const [tab, setTab] = React.useState(t.tab || 'home');
  const [sheet, setSheet] = React.useState(null); // 'add' | { kind:'pat', mode, group? } | null
  const [themeMode, setThemeMode] = React.useState(t.dark ? 'dark' : 'light');

  // Auto-escalar el escenario para que entre el teléfono completo
  const [scale, setScale] = React.useState(1);
  React.useEffect(() => {
    const NEEDED_H = 1000; // teléfono + caption + footer + padding
    const NEEDED_W = 460;  // teléfono + padding lateral
    const fit = () => {
      const s = Math.min(
        1,
        window.innerHeight / NEEDED_H,
        window.innerWidth / NEEDED_W,
      );
      setScale(Math.max(0.5, s));
      document.documentElement.style.setProperty('--dc-inv-zoom', String(1 / Math.max(0.5, s)));
    };
    fit();
    window.addEventListener('resize', fit);
    return () => window.removeEventListener('resize', fit);
  }, []);

  // Sincronizar tweak → estado real cuando cambia desde el panel
  React.useEffect(() => {
    setThemeMode(t.dark ? 'dark' : 'light');
  }, [t.dark]);
  React.useEffect(() => {
    if (t.tab && t.tab !== tab) setTab(t.tab);
  }, [t.tab]);

  // El usuario también puede cambiar el tema desde Ajustes:
  const setDark = (val) => {
    setTweak('dark', val);
  };

  const onNavigate = (where) => {
    if (where === 'add') setSheet('add');
    else setTab(where);
  };

  const onAddTx = (tx) => {
    setTxs(prev => [tx, ...prev]);
  };

  const onAddPatrimonio = (item, kind) => {
    if (kind === 'asset') setAssets(prev => [item, ...prev]);
    else setLiabs(prev => [item, ...prev]);
  };

  const openPatrimonioSheet = (mode, group) => {
    setSheet({ kind: 'pat', mode: mode || (patView === 'activos' ? 'asset' : 'liab'), group });
  };

  // Banner del mes (compartido)
  const currentMonth = 'Mayo 2026';

  return (
    <div style={{
      minHeight: '100vh',
      width: '100%',
      background: t.dark ? '#0F0D0A' : '#E8E0D0',
      transition: 'background 280ms ease',
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      padding: '16px 0',
      boxSizing: 'border-box',
      fontFamily: 'var(--font-sans)',
    }}>
    <div style={{
      width: 460 * scale,
      height: 1000 * scale,
      position: 'relative',
      flexShrink: 0,
    }}>
    <div style={{
      width: 460,
      height: 1000,
      transform: `scale(${scale})`,
      transformOrigin: 'top left',
      display: 'flex', flexDirection: 'column',
      alignItems: 'center',
      gap: 14,
    }}>
      {/* Caption discreta */}
      <div style={{
        textAlign: 'center',
        color: t.dark ? 'rgba(242,236,224,0.6)' : 'rgba(28,24,20,0.55)',
        transition: 'color 280ms ease',
      }}>
        <div style={{
          fontFamily: 'var(--font-display)', fontStyle: 'italic',
          fontSize: 18, letterSpacing: '-0.005em',
        }}>App Finanzas</div>
        <div style={{
          fontSize: 11, letterSpacing: '0.1em', textTransform: 'uppercase',
          fontWeight: 600, marginTop: 2,
        }}>Prototipo · MVP · {tab === 'home' ? 'Inicio' :
          tab === 'movs' ? 'Movimientos' :
          tab === 'budgets' ? 'Presupuestos' :
          tab === 'stats' ? 'Analíticas' :
          tab === 'patrimonio' ? 'Patrimonio' :
          tab === 'perfil' ? 'Perfil' : 'Ajustes'}</div>
      </div>

      {/* Marco del teléfono */}
      <div style={{ position: 'relative' }}>
        <IOSDevice dark={t.dark} width={402} height={874}>
          <div data-theme={t.dark ? 'dark' : 'light'}
               style={{
                 height: '100%', position: 'relative',
                 background: 'var(--bg)',
                 transition: 'background 280ms ease',
               }}>
            {/* Pantalla activa */}
            {tab === 'home' && (
              <HomeScreen txs={txs} onNavigate={onNavigate} currentMonth={currentMonth} />
            )}
            {tab === 'movs' && (
              <MovimientosScreen txs={txs} currentMonth={currentMonth} />
            )}
            {tab === 'budgets' && (
              <PresupuestosScreen txs={txs} currentMonth={currentMonth} />
            )}
            {tab === 'stats' && (
              <AnaliticasScreen txs={txs} currentMonth={currentMonth} />
            )}
            {tab === 'patrimonio' && (
              <PatrimonioScreen
                assets={assets}
                liabs={liabs}
                view={patView}
                setView={setPatView}
                onOpenAdd={openPatrimonioSheet}
                onOpenGroups={() => setSheet('grupos')}
                groupsCount={accGroups.length}
                onNavigate={onNavigate}
                currentMonth={currentMonth}
              />
            )}
            {tab === 'settings' && (
              <AjustesScreen
                dark={t.dark}
                setDark={setDark}
                themeMode={themeMode}
                setThemeMode={setThemeMode}
                profile={profile}
                platform={t.platform || 'ios'}
                onOpenProfile={() => setTab('perfil')}
              />
            )}
            {tab === 'perfil' && (
              <PerfilScreen
                profile={profile}
                setProfile={setProfile}
                platform={t.platform || 'ios'}
                onBack={() => setTab('settings')}
              />
            )}

            {/* Tab bar + FAB (ocultos cuando hay sheet abierto) */}
            <TabBar tab={tab} setTab={(id) => { setTab(id); setTweak('tab', id); }} />
            {tab !== 'settings' && tab !== 'perfil' && (
              <FAB
                onClick={() => {
                  if (tab === 'patrimonio') openPatrimonioSheet();
                  else setSheet('add');
                }}
              />
            )}

            {/* Modal Añadir movimiento */}
            <AddSheet
              open={sheet === 'add'}
              onClose={() => setSheet(null)}
              onSave={onAddTx}
            />

            {/* Sheet Mis grupos (vistas solapadas de cuentas) */}
            <GruposSheet
              open={sheet === 'grupos'}
              groups={accGroups}
              assets={assets}
              onClose={() => setSheet(null)}
              onNewGroup={() => setSheet('nuevoGrupo')}
              onNewAsset={() => openPatrimonioSheet('asset')}
              onDelete={(id) => setAccGroups(prev => prev.filter(g => g.id !== id))}
            />
            <NuevoGrupoSheet
              open={sheet === 'nuevoGrupo'}
              assets={assets}
              onClose={() => setSheet('grupos')}
              onSave={(g) => { setAccGroups(prev => [...prev, g]); setSheet('grupos'); }}
            />

            {/* Modal Añadir activo / pasivo */}
            <AddPatrimonioSheet
              open={!!(sheet && sheet.kind === 'pat')}
              initialMode={sheet?.mode || 'asset'}
              initialGroup={sheet?.group}
              onClose={() => setSheet(null)}
              onSave={onAddPatrimonio}
            />
            {/* Pantalla de bloqueo (PIN · biometría) */}
            {locked && (
              <LockScreen
                profile={profile}
                platform={t.platform || 'ios'}
                onUnlock={() => setLocked(false)}
              />
            )}
          </div>
        </IOSDevice>
      </div>

      {/* Notas inferiores */}
      <div style={{
        maxWidth: 380, textAlign: 'center', fontSize: 11,
        color: t.dark ? 'rgba(242,236,224,0.4)' : 'rgba(28,24,20,0.4)',
        lineHeight: 1.5, letterSpacing: '0.01em',
        marginTop: 4,
      }}>
        Diseño compartido para Compose Multiplatform (Android · iOS · Web).
        El mismo sistema visual se adapta al lenguaje de cada plataforma.
        <br />
        <a href="Design System.html" style={{
          color: 'inherit', textDecoration: 'underline',
          textDecorationColor: 'currentColor', textUnderlineOffset: 3,
          opacity: 0.85,
        }}>Ver sistema de diseño →</a>
      </div>
    </div>
    </div>

      {/* Tweaks panel (fuera del transform para que no se escale) */}
      <TweaksPanel title="Tweaks">
        <TweakSection label="Tema" />
        <TweakToggle label="Modo oscuro"
          value={t.dark}
          onChange={(v) => setTweak('dark', v)} />
        <TweakRadio label="Plataforma"
          value={t.platform || 'ios'}
          options={[{ value: 'ios', label: 'iOS' }, { value: 'android', label: 'Android' }]}
          onChange={(v) => setTweak('platform', v)} />
        <TweakSection label="Navegar" />
        <TweakSelect label="Pantalla"
          value={tab}
          options={[
            { value: 'home', label: 'Inicio' },
            { value: 'movs', label: 'Movimientos' },
            { value: 'budgets', label: 'Presupuestos' },
            { value: 'stats', label: 'Analíticas' },
            { value: 'patrimonio', label: 'Patrimonio' },
            { value: 'settings', label: 'Ajustes' },
            { value: 'perfil', label: 'Perfil' },
          ]}
          onChange={(v) => { setTab(v); setTweak('tab', v); }} />
        <TweakButton label="Abrir 'Añadir movimiento'"
          onClick={() => setSheet('add')} />
        <TweakButton label="Abrir 'Añadir activo/pasivo'"
          onClick={() => openPatrimonioSheet()} />
        <TweakButton label="Abrir 'Mis grupos'"
          onClick={() => { setTab('patrimonio'); setTweak('tab', 'patrimonio'); setSheet('grupos'); }} />
        <TweakButton label="Abrir 'Nuevo grupo'"
          onClick={() => { setTab('patrimonio'); setTweak('tab', 'patrimonio'); setSheet('nuevoGrupo'); }} />
        <TweakButton label="Bloquear app"
          onClick={() => setLocked(true)} />
      </TweaksPanel>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<App />);
