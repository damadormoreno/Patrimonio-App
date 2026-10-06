// grupos.jsx — Grupos de cuentas (vistas personalizadas y solapadas sobre los activos)
// Inspirado en Money Flow: una cuenta puede vivir en varios grupos a la vez.
// Carga DESPUÉS de shared.jsx y patrimonio.jsx.

// Iconos extra
ICONS.folder = "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z";
ICONS.grip   = "M4 9h16M4 15h16";

// ─────────────────────────────────────────────────────────
// Datos de ejemplo · grupos definidos por el usuario
// (ids referencian SAMPLE_ASSETS; el solapamiento es intencional)
// ─────────────────────────────────────────────────────────
const SAMPLE_ACCOUNT_GROUPS = [
  { id: 'g-all', name: 'Todas las cuentas',               builtin: true,  showBalance: true,  accounts: 'all' },
  { id: 'g-2',   name: 'Todas sin inversión',             builtin: false, showBalance: true,  accounts: ['a1','a2','a3','a7','a8','a9','a10'] },
  { id: 'g-3',   name: 'Personal',                        builtin: false, showBalance: true,  accounts: ['a1','a2','a4','a9','a10'] },
  { id: 'g-4',   name: 'Personal sin inversión',          builtin: false, showBalance: true,  accounts: ['a1','a2','a10'] },
  { id: 'g-5',   name: 'Inversiones',                     builtin: false, showBalance: true,  accounts: ['a4','a5','a6'] },
  { id: 'g-6',   name: 'Monetarios',                      builtin: false, showBalance: true,  accounts: ['a2','a3'] },
  { id: 'g-7',   name: 'Monetarios (fondo emergencia)',   builtin: false, showBalance: false, accounts: ['a2'] },
  { id: 'g-8',   name: 'Personal sin inv ni monetarios',  builtin: false, showBalance: true,  accounts: ['a1','a10'] },
];

function groupMembers(g, assets) {
  if (g.accounts === 'all') return assets;
  return assets.filter(a => g.accounts.includes(a.id));
}
function groupTotal(g, assets) {
  return groupMembers(g, assets).reduce((s, a) => s + toEUR(a.amount, a.currency), 0);
}

// Chip pequeño con el icono del tipo de activo
function AssetChip({ asset, size = 28 }) {
  const meta = ASSET_GROUPS[asset.group] || ASSET_GROUPS.bank;
  return (
    <span style={{
      width: size, height: size, borderRadius: 8,
      background: meta.tone, color: 'var(--surface-2)',
      display: 'grid', placeItems: 'center', flexShrink: 0,
    }}>
      <Icon name={meta.icon} size={Math.round(size * 0.55)} stroke={1.9} />
    </span>
  );
}

// ═════════════════════════════════════════════════════════
// SHEET · Mis grupos (lista expandible)
// ═════════════════════════════════════════════════════════
function GruposSheet({ open, groups, assets, onClose, onNewGroup, onNewAsset, onDelete }) {
  const [mounted, setMounted] = React.useState(false);
  const [expanded, setExpanded] = React.useState({});   // { [id]: bool }
  const [editing, setEditing] = React.useState(false);
  const [plusOpen, setPlusOpen] = React.useState(false);

  React.useEffect(() => {
    if (open) {
      const id = requestAnimationFrame(() => setMounted(true));
      return () => cancelAnimationFrame(id);
    } else {
      setMounted(false);
      setEditing(false);
      setPlusOpen(false);
    }
  }, [open]);

  if (!open && !mounted) return null;

  const toggle = (id) => setExpanded(prev => ({ ...prev, [id]: !prev[id] }));

  return (
    <>
      <div onClick={onClose} style={{
        position: 'absolute', inset: 0, zIndex: 40,
        background: mounted ? 'rgba(0,0,0,0.45)' : 'rgba(0,0,0,0)',
        transition: 'background 280ms cubic-bezier(.32,.72,0,1)',
      }} />

      <div style={{
        position: 'absolute', left: 0, right: 0, bottom: 0,
        zIndex: 45, height: '94%',
        background: 'var(--bg)',
        borderRadius: '28px 28px 0 0',
        boxShadow: '0 -18px 40px rgba(0,0,0,0.3)',
        transform: mounted ? 'translateY(0)' : 'translateY(100%)',
        transition: 'transform 360ms cubic-bezier(.32,.72,0,1)',
        display: 'flex', flexDirection: 'column',
        overflow: 'hidden',
        fontFamily: 'var(--font-sans)', color: 'var(--ink)',
      }}>
        {/* Handle */}
        <div style={{ display: 'flex', justifyContent: 'center', padding: '10px 0 4px' }}>
          <div style={{ width: 36, height: 4, borderRadius: 999, background: 'var(--muted-2)', opacity: 0.5 }} />
        </div>

        {/* Top bar */}
        <div style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          padding: '6px 20px 12px', position: 'relative',
        }}>
          <button onClick={onClose} aria-label="Cerrar" style={{
            width: 36, height: 36, borderRadius: '50%',
            background: 'var(--surface)', border: '1px solid var(--line)',
            color: 'var(--ink-2)', display: 'grid', placeItems: 'center', cursor: 'pointer',
          }}>
            <Icon name="close" size={17} stroke={2} />
          </button>
          <h2 style={{
            margin: 0, fontSize: 16, fontWeight: 600,
            letterSpacing: '-0.01em', color: 'var(--ink)',
            position: 'absolute', left: '50%', transform: 'translateX(-50%)',
          }}>Mis grupos</h2>
          <div style={{ display: 'flex', gap: 8 }}>
            <button onClick={() => { setPlusOpen(p => !p); }} aria-label="Añadir" style={{
              width: 36, height: 36, borderRadius: '50%',
              background: plusOpen ? 'var(--ink)' : 'var(--surface)',
              border: '1px solid var(--line)',
              color: plusOpen ? 'var(--bg)' : 'var(--ink-2)',
              display: 'grid', placeItems: 'center', cursor: 'pointer',
              transition: 'background 160ms ease, color 160ms ease',
            }}>
              <Icon name="plus" size={17} stroke={2.1} />
            </button>
            <button onClick={() => setEditing(e => !e)} aria-label="Editar" style={{
              width: 36, height: 36, borderRadius: '50%',
              background: editing ? 'var(--ink)' : 'var(--surface)',
              border: '1px solid var(--line)',
              color: editing ? 'var(--bg)' : 'var(--ink-2)',
              display: 'grid', placeItems: 'center', cursor: 'pointer',
              transition: 'background 160ms ease, color 160ms ease',
            }}>
              <Icon name="pencil" size={15} stroke={1.9} />
            </button>
          </div>

          {/* Popover + */}
          {plusOpen && (
            <>
              <div onClick={() => setPlusOpen(false)} style={{ position: 'fixed', inset: 0, zIndex: 48 }} />
              <div style={{
                position: 'absolute', right: 20, top: 48, zIndex: 49,
                background: 'var(--surface-2)',
                border: '1px solid var(--line)',
                borderRadius: 16, padding: 6,
                boxShadow: 'var(--sh-2)',
                display: 'flex', flexDirection: 'column', gap: 2,
                minWidth: 210,
              }}>
                {[
                  { icon: 'wallet', label: 'Nueva cuenta',           action: () => { setPlusOpen(false); onNewAsset && onNewAsset(); } },
                  { icon: 'folder', label: 'Nuevo grupo de cuentas', action: () => { setPlusOpen(false); onNewGroup && onNewGroup(); } },
                ].map(o => (
                  <button key={o.label} onClick={o.action} style={{
                    appearance: 'none', border: 0, background: 'transparent',
                    display: 'flex', alignItems: 'center', gap: 10,
                    padding: '10px 12px', borderRadius: 10,
                    fontFamily: 'inherit', fontSize: 14, fontWeight: 500,
                    color: 'var(--ink)', cursor: 'pointer', textAlign: 'left',
                  }}>
                    <Icon name={o.icon} size={18} stroke={1.8} style={{ color: 'var(--ink-2)' }} />
                    {o.label}
                  </button>
                ))}
              </div>
            </>
          )}
        </div>

        {/* Lista */}
        <div className="no-scrollbar" style={{ flex: 1, overflowY: 'auto', padding: '4px 20px 28px' }}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {groups.map(g => (
              <GrupoRow
                key={g.id}
                g={g}
                assets={assets}
                expanded={!!expanded[g.id]}
                onToggle={() => toggle(g.id)}
                editing={editing}
                onDelete={() => onDelete && onDelete(g.id)}
              />
            ))}
          </div>

          {/* Nuevo grupo (CTA discreta al final) */}
          <button onClick={onNewGroup} style={{
            width: '100%', marginTop: 14,
            appearance: 'none', border: '1px dashed var(--line)',
            background: 'transparent', color: 'var(--ink-2)',
            padding: '13px 16px', borderRadius: 14,
            display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
            fontFamily: 'inherit', fontSize: 13.5, fontWeight: 500, cursor: 'pointer',
          }}>
            <Icon name="plus" size={15} stroke={2} />
            Nuevo grupo de cuentas
          </button>

          <div style={{
            marginTop: 14, textAlign: 'center',
            fontSize: 11, color: 'var(--muted-2)', letterSpacing: '0.02em',
            lineHeight: 1.5,
          }}>
            Los grupos son vistas: una cuenta puede estar<br />en varios grupos a la vez.
          </div>
        </div>
      </div>
    </>
  );
}

// Fila de grupo (expandible)
function GrupoRow({ g, assets, expanded, onToggle, editing, onDelete }) {
  const members = groupMembers(g, assets);
  const total = groupTotal(g, assets);
  const highlight = g.builtin;

  return (
    <div style={{
      background: highlight ? 'var(--surface-2)' : 'var(--surface)',
      border: highlight ? '1.5px solid var(--ink)' : '1px solid var(--line)',
      borderRadius: 16, overflow: 'hidden',
      transition: 'border-color 160ms ease',
    }}>
      <div style={{ display: 'flex', alignItems: 'center' }}>
        <button onClick={onToggle} style={{
          flex: 1, minWidth: 0,
          appearance: 'none', border: 0, background: 'transparent',
          display: 'flex', alignItems: 'center', gap: 10,
          padding: '14px 14px', cursor: 'pointer',
          fontFamily: 'inherit', color: 'inherit', textAlign: 'left',
        }}>
          <span style={{
            display: 'grid', placeItems: 'center',
            color: 'var(--muted)', flexShrink: 0,
            transform: expanded ? 'rotate(90deg)' : 'rotate(0deg)',
            transition: 'transform 200ms cubic-bezier(.4,.6,.2,1)',
          }}>
            <Icon name="chevronR" size={16} stroke={2.2} />
          </span>
          <span style={{
            flex: 1, minWidth: 0,
            fontSize: 14.5, fontWeight: 600, letterSpacing: '-0.005em',
            color: 'var(--ink)',
            whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
          }}>{g.name}</span>
          {!editing && (
            <span style={{
              fontFeatureSettings: '"tnum"', fontVariantNumeric: 'tabular-nums',
              fontWeight: 600, fontSize: 14, letterSpacing: '-0.005em',
              color: 'var(--income)', whiteSpace: 'nowrap',
            }}>
              {g.showBalance ? `+ ${formatMoney(total, 'EUR')}` : '···'}
            </span>
          )}
        </button>

        {editing && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 4, paddingRight: 12 }}>
            {!g.builtin && (
              <button onClick={onDelete} aria-label={`Eliminar ${g.name}`} style={{
                appearance: 'none', border: 0, background: 'transparent',
                color: 'var(--expense)', cursor: 'pointer',
                width: 30, height: 30, display: 'grid', placeItems: 'center',
              }}>
                <Icon name="trash" size={16} stroke={1.9} />
              </button>
            )}
            <span style={{ color: 'var(--muted-2)', display: 'grid', placeItems: 'center', cursor: 'grab', width: 26 }}>
              <Icon name="grip" size={17} stroke={2} />
            </span>
          </div>
        )}
      </div>

      {/* Miembros */}
      {expanded && (
        <div style={{ padding: '0 14px 6px 40px', borderTop: '1px solid var(--line-2)' }}>
          {members.map((a, i) => (
            <div key={a.id} style={{
              display: 'flex', alignItems: 'center', gap: 10,
              padding: '10px 0',
              borderBottom: i === members.length - 1 ? 0 : '1px solid var(--line-2)',
            }}>
              <AssetChip asset={a} size={26} />
              <span style={{
                flex: 1, minWidth: 0,
                fontSize: 13.5, fontWeight: 500, color: 'var(--ink)',
                whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
              }}>{a.name}</span>
              <span style={{
                fontFeatureSettings: '"tnum"', fontVariantNumeric: 'tabular-nums',
                fontSize: 12.5, fontWeight: 500, color: 'var(--muted)',
                whiteSpace: 'nowrap',
              }}>
                {formatMoney(toEUR(a.amount, a.currency), 'EUR')}
              </span>
            </div>
          ))}
          {members.length === 0 && (
            <div style={{ padding: '12px 0', fontSize: 12.5, color: 'var(--muted)' }}>
              Este grupo no tiene cuentas todavía.
            </div>
          )}
        </div>
      )}
    </div>
  );
}

// ═════════════════════════════════════════════════════════
// SHEET · Nuevo grupo de cuentas
// ═════════════════════════════════════════════════════════
function NuevoGrupoSheet({ open, assets, onClose, onSave }) {
  const [mounted, setMounted] = React.useState(false);
  const [title, setTitle] = React.useState('');
  const [showBalance, setShowBalance] = React.useState(true);
  const [selected, setSelected] = React.useState({}); // { [assetId]: bool }

  React.useEffect(() => {
    if (open) {
      setTitle('');
      setShowBalance(true);
      setSelected({});
      const id = requestAnimationFrame(() => setMounted(true));
      return () => cancelAnimationFrame(id);
    } else {
      setMounted(false);
    }
  }, [open]);

  if (!open && !mounted) return null;

  const selIds = Object.keys(selected).filter(id => selected[id]);
  const canSave = title.trim() !== '' && selIds.length > 0;
  const selTotal = assets
    .filter(a => selected[a.id])
    .reduce((s, a) => s + toEUR(a.amount, a.currency), 0);

  const handleSave = () => {
    if (!canSave) return;
    onSave({
      id: `g-${Date.now()}`,
      name: title.trim(),
      builtin: false,
      showBalance,
      accounts: selIds,
    });
  };

  return (
    <>
      <div onClick={onClose} style={{
        position: 'absolute', inset: 0, zIndex: 50,
        background: mounted ? 'rgba(0,0,0,0.45)' : 'rgba(0,0,0,0)',
        transition: 'background 280ms cubic-bezier(.32,.72,0,1)',
      }} />

      <div style={{
        position: 'absolute', left: 0, right: 0, bottom: 0,
        zIndex: 55, height: '94%',
        background: 'var(--bg)',
        borderRadius: '28px 28px 0 0',
        boxShadow: '0 -18px 40px rgba(0,0,0,0.3)',
        transform: mounted ? 'translateY(0)' : 'translateY(100%)',
        transition: 'transform 360ms cubic-bezier(.32,.72,0,1)',
        display: 'flex', flexDirection: 'column',
        overflow: 'hidden',
        fontFamily: 'var(--font-sans)', color: 'var(--ink)',
      }}>
        {/* Handle */}
        <div style={{ display: 'flex', justifyContent: 'center', padding: '10px 0 4px' }}>
          <div style={{ width: 36, height: 4, borderRadius: 999, background: 'var(--muted-2)', opacity: 0.5 }} />
        </div>

        {/* Top bar */}
        <div style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          padding: '6px 20px 12px',
        }}>
          <button onClick={onClose} aria-label="Volver" style={{
            width: 36, height: 36, borderRadius: '50%',
            background: 'var(--surface)', border: '1px solid var(--line)',
            color: 'var(--ink-2)', display: 'grid', placeItems: 'center', cursor: 'pointer',
          }}>
            <Icon name="close" size={17} stroke={2} />
          </button>
          <h2 style={{
            margin: 0, fontSize: 16, fontWeight: 600,
            letterSpacing: '-0.01em', color: 'var(--ink)',
          }}>Nuevo grupo de cuentas</h2>
          <button onClick={handleSave} disabled={!canSave} aria-label="Guardar grupo" style={{
            width: 36, height: 36, borderRadius: '50%',
            background: canSave ? 'var(--ink)' : 'var(--surface)',
            border: canSave ? '1px solid var(--ink)' : '1px solid var(--line)',
            color: canSave ? 'var(--bg)' : 'var(--muted-2)',
            display: 'grid', placeItems: 'center',
            cursor: canSave ? 'pointer' : 'not-allowed',
            transition: 'background 160ms ease, color 160ms ease',
          }}>
            <Icon name="check" size={17} stroke={2.3} />
          </button>
        </div>

        <div className="no-scrollbar" style={{ flex: 1, overflowY: 'auto', padding: '4px 20px 28px' }}>
          {/* Título */}
          <div style={{
            background: 'var(--surface-2)', border: '1px solid var(--line)',
            borderRadius: 14, padding: '14px 16px', marginBottom: 10,
          }}>
            <input
              type="text"
              placeholder="Título · Ej: Fondo emergencia"
              value={title}
              autoFocus
              onChange={e => setTitle(e.target.value)}
              style={{
                width: '100%', appearance: 'none', border: 0, background: 'transparent',
                outline: 0, fontFamily: 'inherit', fontSize: 15, fontWeight: 500,
                color: 'var(--ink)',
              }} />
          </div>

          {/* Moneda (fija en el prototipo) */}
          <div style={{
            background: 'var(--surface)', border: '1px solid var(--line)',
            borderRadius: 14, padding: '14px 16px', marginBottom: 10,
            display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          }}>
            <span style={{ fontSize: 14, fontWeight: 500, color: 'var(--ink)' }}>Moneda</span>
            <span style={{ fontSize: 14, color: 'var(--muted)', fontWeight: 500 }}>General (EUR · €)</span>
          </div>

          {/* Mostrar saldo */}
          <button
            onClick={() => setShowBalance(s => !s)}
            style={{
              width: '100%', appearance: 'none', cursor: 'pointer',
              background: 'var(--surface)', border: '1px solid var(--line)',
              borderRadius: 14, padding: '13px 16px', marginBottom: 22,
              display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 14,
              fontFamily: 'inherit', textAlign: 'left',
            }}>
            <span style={{
              fontSize: 13.5, fontWeight: 500, color: 'var(--ink)',
              lineHeight: 1.35,
            }}>Mostrar saldo de este grupo<br />en la lista de grupos</span>
            <span style={{
              width: 46, height: 28, borderRadius: 999, flexShrink: 0,
              background: showBalance ? 'var(--income)' : 'var(--bg-2)',
              border: `1px solid ${showBalance ? 'var(--income)' : 'var(--line)'}`,
              position: 'relative',
              transition: 'background 200ms ease',
            }}>
              <span style={{
                position: 'absolute', top: 2, left: showBalance ? 20 : 2,
                width: 22, height: 22, borderRadius: '50%',
                background: 'var(--surface-2)',
                boxShadow: '0 1px 4px rgba(0,0,0,0.2)',
                transition: 'left 200ms cubic-bezier(.4,.6,.2,1)',
              }} />
            </span>
          </button>

          {/* Checklist de cuentas */}
          <div style={{
            fontSize: 11, letterSpacing: '0.12em', textTransform: 'uppercase',
            color: 'var(--muted)', fontWeight: 600, marginBottom: 10,
          }}>
            Qué cuentas incluir en este grupo
          </div>
          <div style={{
            background: 'var(--surface)', border: '1px solid var(--line)',
            borderRadius: 16, padding: '0 14px',
          }}>
            {assets.map((a, i) => {
              const on = !!selected[a.id];
              return (
                <button key={a.id}
                  onClick={() => setSelected(prev => ({ ...prev, [a.id]: !prev[a.id] }))}
                  style={{
                    width: '100%', appearance: 'none', border: 0, background: 'transparent',
                    display: 'flex', alignItems: 'center', gap: 12,
                    padding: '12px 0', cursor: 'pointer',
                    fontFamily: 'inherit', color: 'inherit', textAlign: 'left',
                    borderBottom: i === assets.length - 1 ? 0 : '1px solid var(--line-2)',
                  }}>
                  <AssetChip asset={a} size={30} />
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{
                      fontSize: 14, fontWeight: 600, letterSpacing: '-0.005em',
                      color: 'var(--ink)',
                      whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                    }}>{a.name}</div>
                    <div style={{
                      fontSize: 11.5, color: 'var(--muted)', marginTop: 1,
                      whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                    }}>{a.subtitle}</div>
                  </div>
                  <span style={{
                    width: 24, height: 24, borderRadius: '50%', flexShrink: 0,
                    border: `1.5px solid ${on ? 'var(--ink)' : 'var(--line)'}`,
                    background: on ? 'var(--ink)' : 'transparent',
                    color: 'var(--bg)',
                    display: 'grid', placeItems: 'center',
                    transition: 'all 140ms ease',
                  }}>
                    {on && <Icon name="check" size={14} stroke={2.6} />}
                  </span>
                </button>
              );
            })}
          </div>

          {/* Resumen selección */}
          <div style={{
            marginTop: 12, display: 'flex', justifyContent: 'space-between',
            fontSize: 12.5, color: 'var(--muted)', fontWeight: 500,
            fontFeatureSettings: '"tnum"',
            padding: '0 4px',
          }}>
            <span>{selIds.length} {selIds.length === 1 ? 'cuenta seleccionada' : 'cuentas seleccionadas'}</span>
            <span>{selIds.length > 0 ? formatMoney(selTotal, 'EUR') : ''}</span>
          </div>
        </div>
      </div>
    </>
  );
}

// Exportar al global
Object.assign(window, {
  SAMPLE_ACCOUNT_GROUPS, groupMembers, groupTotal,
  GruposSheet, NuevoGrupoSheet, AssetChip,
});
