// patrimonio.jsx — Pantalla de Patrimonio (activos · pasivos · patrimonio neto)
// Carga DESPUÉS de shared.jsx y screens.jsx (reutiliza SCREEN_BASE, SCROLL_AREA,
// Avatar, Icon, formatMoney, etc.)

// ════════════════════════════════════════════════════════════
// PATRIMONIO
// ════════════════════════════════════════════════════════════
function PatrimonioScreen({ assets, liabs, view, setView, onOpenAdd, onOpenGroups, groupsCount, onNavigate, currentMonth }) {
  // Fallback por si se renderiza sin control externo de la vista
  const [localView, setLocalView] = React.useState('activos');
  const currentView = view ?? localView;
  const updateView = setView || setLocalView;

  const totalA = totalAssets(assets);
  const totalL = totalLiabs(liabs);
  const netWorth = totalA - totalL;

  // Delta del patrimonio neto (último mes vs anterior)
  const last = NETWORTH_6M[NETWORTH_6M.length - 1].v;
  const prev = NETWORTH_6M[NETWORTH_6M.length - 2].v;
  const deltaAbs = last - prev;
  const deltaPct = (deltaAbs / prev) * 100;

  const groupsA = assetsByGroup(assets);
  const groupsL = liabsByGroup(liabs);

  return (
    <div style={SCREEN_BASE}>
      <ScreenHeader
        eyebrow={currentMonth}
        title="Patrimonio"
        right={<Avatar initials="M" onClick={() => onNavigate('settings')} />}
      />
      <div style={SCROLL_AREA}>
        {/* Hero: Patrimonio neto + sparkline */}
        <NetWorthCard
          value={netWorth}
          deltaAbs={deltaAbs}
          deltaPct={deltaPct}
          series={NETWORTH_6M}
        />

        {/* Stat pair: Activos · Pasivos */}
        <div style={{
          display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10,
          marginTop: 12,
        }}>
          <PatrimonioStat
            label="Activos"
            amount={totalA}
            tone="income"
            count={assets.length}
            active={currentView === 'activos'}
            onClick={() => updateView('activos')}
          />
          <PatrimonioStat
            label="Pasivos"
            amount={totalL}
            tone="expense"
            count={liabs.length}
            active={currentView === 'pasivos'}
            onClick={() => updateView('pasivos')}
          />
        </div>

        {/* Mis grupos · vistas personalizadas de cuentas */}
        {currentView === 'activos' && onOpenGroups && (
          <button onClick={onOpenGroups} style={{
            width: '100%', marginTop: 10,
            appearance: 'none', cursor: 'pointer',
            background: 'var(--surface)',
            border: '1px solid var(--line)',
            borderRadius: 16, padding: '12px 14px',
            display: 'flex', alignItems: 'center', gap: 12,
            fontFamily: 'inherit', color: 'inherit', textAlign: 'left',
          }}>
            <span style={{
              width: 32, height: 32, borderRadius: 9,
              background: 'var(--brand-soft)', color: 'var(--brand)',
              display: 'grid', placeItems: 'center', flexShrink: 0,
            }}>
              <Icon name="folder" size={17} stroke={1.8} />
            </span>
            <span style={{ flex: 1, minWidth: 0 }}>
              <span style={{
                display: 'block', fontSize: 14, fontWeight: 600,
                letterSpacing: '-0.005em', color: 'var(--ink)',
              }}>Mis grupos</span>
              <span style={{
                display: 'block', fontSize: 11.5, color: 'var(--muted)', marginTop: 1,
              }}>Vistas personalizadas · una cuenta, varios grupos</span>
            </span>
            {typeof groupsCount === 'number' && (
              <span style={{
                fontSize: 11, color: 'var(--muted)',
                background: 'var(--bg-2)', border: '1px solid var(--line)',
                padding: '1px 8px', borderRadius: 999, flexShrink: 0,
              }}>{groupsCount}</span>
            )}
            <Icon name="chevronR" size={17} stroke={2} style={{ color: 'var(--muted)', flexShrink: 0 }} />
          </button>
        )}

        {/* Distribución (barra apilada por grupo) */}
        <SectionRow
          title={currentView === 'activos' ? 'Composición · Activos' : 'Composición · Pasivos'}
          action={
            <span style={{ fontSize: 12, color: 'var(--muted)' }}>
              {(currentView === 'activos' ? groupsA : groupsL).length} grupos
            </span>
          }
        />
        <StackedShare
          groups={currentView === 'activos' ? groupsA : groupsL}
          total={currentView === 'activos' ? totalA : totalL}
          dict={currentView === 'activos' ? ASSET_GROUPS : LIAB_GROUPS}
        />

        {/* Lista por grupos */}
        <SectionRow
          title={currentView === 'activos' ? 'Mis activos' : 'Mis pasivos'}
          action={
            <span style={{
              fontSize: 12, color: 'var(--muted)',
              fontFeatureSettings: '"tnum"',
            }}>
              {formatMoney(currentView === 'activos' ? totalA : totalL, 'EUR')}
            </span>
          }
        />
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          {(currentView === 'activos' ? groupsA : groupsL).map(g => (
            <PatrimonioGroup
              key={g.group}
              g={g}
              dict={currentView === 'activos' ? ASSET_GROUPS : LIAB_GROUPS}
              total={currentView === 'activos' ? totalA : totalL}
              onAdd={() => onOpenAdd && onOpenAdd(currentView === 'activos' ? 'asset' : 'liab', g.group)}
            />
          ))}
        </div>

        {/* CTA añadir */}
        <button
          onClick={() => onOpenAdd && onOpenAdd(currentView === 'activos' ? 'asset' : 'liab')}
          style={{
          width: '100%', marginTop: 16,
          appearance: 'none', border: '1px dashed var(--line)',
          background: 'transparent', color: 'var(--ink-2)',
          padding: '14px 16px', borderRadius: 16,
          display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
          fontFamily: 'inherit', fontSize: 14, fontWeight: 500,
          cursor: 'pointer',
        }}>
          <Icon name="plus" size={16} stroke={2} />
          {currentView === 'activos' ? 'Nuevo activo' : 'Nuevo pasivo'}
        </button>

        {/* Pie discreto */}
        <div style={{
          marginTop: 18, marginBottom: 4,
          fontSize: 11, color: 'var(--muted-2)',
          textAlign: 'center', letterSpacing: '0.04em',
        }}>
          Actualizado a {currentMonth.toLowerCase()} · saldos en EUR
        </div>
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────
// Hero · Patrimonio neto
// ─────────────────────────────────────────────────────────
function NetWorthCard({ value, deltaAbs, deltaPct, series }) {
  const intStr = Math.trunc(value).toLocaleString('es-ES', { useGrouping: 'always' });
  const decStr = String(Math.round((Math.abs(value) - Math.trunc(Math.abs(value))) * 100)).padStart(2, '0');
  const up = deltaAbs >= 0;
  const pctStr = Math.abs(deltaPct).toLocaleString('es-ES', { minimumFractionDigits: 1, maximumFractionDigits: 1 });
  const absStr = Math.abs(deltaAbs).toLocaleString('es-ES', { maximumFractionDigits: 0, useGrouping: 'always' });

  return (
    <div style={{
      marginTop: 8,
      background: 'linear-gradient(180deg, var(--surface-2), var(--surface))',
      border: '1px solid var(--line)',
      borderRadius: 22,
      padding: '18px 20px 4px',
      boxShadow: 'var(--sh-2)',
      position: 'relative', overflow: 'hidden',
    }}>
      <div style={{
        display: 'flex', alignItems: 'center', gap: 8,
        fontSize: 11, letterSpacing: '0.12em', textTransform: 'uppercase',
        color: 'var(--muted)', fontWeight: 600,
      }}>
        Patrimonio neto
        <span style={{
          background: 'var(--bg-2)', border: '1px solid var(--line)',
          padding: '2px 8px', borderRadius: 999, fontSize: 10,
          letterSpacing: '0.04em', color: 'var(--ink-2)', fontWeight: 600,
        }}>EUR · €</span>
      </div>
      <div style={{
        display: 'flex', alignItems: 'baseline', gap: 12, marginTop: 10,
      }}>
        <span style={{
          fontFamily: 'var(--font-display)', fontStyle: 'italic',
          fontSize: 48, lineHeight: 1.05, letterSpacing: '-0.005em',
          color: 'var(--ink)',
          paddingRight: 4,
        }}>
          {intStr}<span style={{ opacity: 0.45 }}>,{decStr}</span>
        </span>
        <span style={{
          fontSize: 22, fontWeight: 500, color: 'var(--ink-2)',
        }}>€</span>
      </div>
      <div style={{
        marginTop: 10, display: 'flex', alignItems: 'center', gap: 10,
        flexWrap: 'wrap',
      }}>
        <span style={{
          display: 'inline-flex', alignItems: 'center', gap: 5,
          padding: '4px 10px', borderRadius: 999,
          background: up ? 'var(--income-soft)' : 'var(--expense-soft)',
          color: up ? 'var(--income)' : 'var(--expense)',
          fontSize: 12, fontWeight: 600,
          fontVariantNumeric: 'tabular-nums',
        }}>
          <Icon name={up ? 'arrowUp' : 'arrowDown'} size={13} stroke={2.4} />
          {up ? '+' : '−'}{pctStr}%
        </span>
        <span style={{
          fontSize: 12, color: 'var(--muted)',
          fontFeatureSettings: '"tnum"',
        }}>
          {`${up ? '+' : '−'}${absStr} € · últimos 30 días`}
        </span>
      </div>
      {/* Sparkline */}
      <div style={{ marginTop: 6, marginLeft: -4, marginRight: -4 }}>
        <NetWorthSpark data={series} />
      </div>
    </div>
  );
}

function NetWorthSpark({ data }) {
  const W = 340, H = 64, P = 8;
  const min = Math.min(...data.map(d => d.v));
  const max = Math.max(...data.map(d => d.v));
  const range = max - min || 1;
  const step = (W - P * 2) / (data.length - 1);
  const pts = data.map((d, i) => ({
    x: P + i * step,
    y: H - P - ((d.v - min) / range) * (H - P * 2),
    v: d.v, m: d.m,
  }));
  const linePath = pts.map((p, i) => `${i === 0 ? 'M' : 'L'} ${p.x} ${p.y}`).join(' ');
  const areaPath = linePath + ` L ${pts[pts.length - 1].x} ${H - P} L ${pts[0].x} ${H - P} Z`;
  return (
    <svg viewBox={`0 0 ${W} ${H}`} width="100%" style={{ display: 'block' }}>
      <defs>
        <linearGradient id="nw-spark" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="var(--income)" stopOpacity="0.22"/>
          <stop offset="100%" stopColor="var(--income)" stopOpacity="0"/>
        </linearGradient>
      </defs>
      <path d={areaPath} fill="url(#nw-spark)" />
      <path d={linePath} stroke="var(--income)" strokeWidth="2"
            fill="none" strokeLinecap="round" strokeLinejoin="round" />
      <circle cx={pts[pts.length - 1].x} cy={pts[pts.length - 1].y} r="3.5"
              fill="var(--surface-2)" stroke="var(--income)" strokeWidth="2" />
    </svg>
  );
}

// ─────────────────────────────────────────────────────────
// Stat clicable · Activos / Pasivos
// ─────────────────────────────────────────────────────────
function PatrimonioStat({ label, amount, tone, count, active, onClick }) {
  const isAsset = tone === 'income';
  const fg = isAsset ? 'var(--income)' : 'var(--expense)';
  const bg = isAsset ? 'var(--income-soft)' : 'var(--expense-soft)';
  return (
    <button onClick={onClick} style={{
      appearance: 'none', textAlign: 'left',
      background: 'var(--surface)',
      border: active ? '1.5px solid var(--ink)' : '1px solid var(--line)',
      borderRadius: 16, padding: '12px 14px',
      display: 'flex', flexDirection: 'column', gap: 6,
      cursor: 'pointer', fontFamily: 'inherit', color: 'inherit',
      transition: 'border-color 160ms ease, transform 140ms ease',
      minWidth: 0,
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <span style={{
          width: 20, height: 20, borderRadius: 6, background: bg,
          color: fg, display: 'grid', placeItems: 'center', flexShrink: 0,
        }}>
          <Icon name={isAsset ? 'arrowUp' : 'arrowDown'} size={13} stroke={2.4} />
        </span>
        <span style={{
          fontSize: 12, color: 'var(--muted)', fontWeight: 600,
          letterSpacing: '0.02em',
        }}>{label}</span>
        <span style={{
          marginLeft: 'auto',
          fontSize: 11, color: 'var(--muted)',
          background: 'var(--bg-2)', border: '1px solid var(--line)',
          padding: '1px 7px', borderRadius: 999,
        }}>{count}</span>
      </div>
      <div style={{
        fontFeatureSettings: '"tnum"',
        fontWeight: 700, fontSize: 18, letterSpacing: '-0.01em',
        color: 'var(--ink)',
        whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
      }}>
        {formatMoney(amount, 'EUR')}
      </div>
    </button>
  );
}

// ─────────────────────────────────────────────────────────
// Barra apilada por grupos
// ─────────────────────────────────────────────────────────
function StackedShare({ groups, total, dict }) {
  if (total <= 0 || groups.length === 0) return null;
  return (
    <div style={{
      background: 'var(--surface)', border: '1px solid var(--line)',
      borderRadius: 16, padding: '14px 16px',
    }}>
      {/* Barra */}
      <div style={{
        display: 'flex', height: 12, borderRadius: 999, overflow: 'hidden',
        background: 'var(--bg-2)', border: '1px solid var(--line-2)',
      }}>
        {groups.map((g, i) => {
          const w = (g.total / total) * 100;
          const meta = dict[g.group];
          return (
            <div key={g.group} title={`${meta.label} · ${Math.round(w)}%`}
                 style={{
                   width: `${w}%`, background: meta.tone,
                   borderRight: i === groups.length - 1 ? 0 : '1px solid var(--surface)',
                 }} />
          );
        })}
      </div>
      {/* Leyenda */}
      <div style={{
        marginTop: 12, display: 'flex', flexWrap: 'wrap',
        rowGap: 8, columnGap: 14,
      }}>
        {groups.map(g => {
          const share = (g.total / total) * 100;
          const meta = dict[g.group];
          return (
            <div key={g.group} style={{
              display: 'inline-flex', alignItems: 'center', gap: 6,
              fontSize: 12,
            }}>
              <span style={{
                width: 8, height: 8, borderRadius: 2,
                background: meta.tone, flexShrink: 0,
              }} />
              <span style={{ color: 'var(--ink-2)', fontWeight: 500 }}>{meta.label}</span>
              <span style={{
                color: 'var(--muted)', fontFeatureSettings: '"tnum"',
              }}>{Math.round(share)}%</span>
            </div>
          );
        })}
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────
// Tarjeta de grupo con sus items
// ─────────────────────────────────────────────────────────
function PatrimonioGroup({ g, dict, total, onAdd }) {
  const meta = dict[g.group];
  const share = total > 0 ? (g.total / total) * 100 : 0;

  return (
    <div style={{
      background: 'var(--surface)', border: '1px solid var(--line)',
      borderRadius: 16, overflow: 'hidden',
    }}>
      {/* Cabecera del grupo */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: 12,
        padding: '12px 16px',
        borderBottom: '1px solid var(--line-2)',
        background: 'var(--surface-2)',
      }}>
        <span style={{
          width: 32, height: 32, borderRadius: 9, background: meta.tone,
          color: 'var(--surface-2)',
          display: 'grid', placeItems: 'center', flexShrink: 0,
        }}>
          <Icon name={meta.icon} size={16} stroke={1.9} />
        </span>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{
            fontSize: 14, fontWeight: 600, letterSpacing: '-0.005em',
            color: 'var(--ink)',
          }}>{meta.label}</div>
          <div style={{
            fontSize: 11, color: 'var(--muted)', marginTop: 1,
            fontFeatureSettings: '"tnum"',
          }}>
            {g.items.length} {g.items.length === 1 ? 'elemento' : 'elementos'} · {Math.round(share)}% del total
          </div>
        </div>
        <span style={{
          fontFeatureSettings: '"tnum"',
          fontWeight: 700, fontSize: 15, letterSpacing: '-0.005em',
          color: 'var(--ink)',
        }}>{formatMoney(g.total, 'EUR')}</span>
        {onAdd && (
          <button
            onClick={onAdd}
            aria-label={`Añadir a ${meta.label}`}
            style={{
              appearance: 'none', border: '1px solid var(--line)',
              background: 'var(--bg-2)', color: 'var(--ink-2)',
              width: 28, height: 28, borderRadius: '50%',
              display: 'grid', placeItems: 'center',
              cursor: 'pointer', flexShrink: 0,
              transition: 'background 160ms ease',
            }}>
            <Icon name="plus" size={14} stroke={2.2} />
          </button>
        )}
      </div>

      {/* Items */}
      <div style={{ padding: '0 16px' }}>
        {g.items.map((it, i) => (
          <PatrimonioItemRow
            key={it.id}
            item={it}
            tone={meta.tone}
            last={i === g.items.length - 1}
          />
        ))}
      </div>
    </div>
  );
}

function PatrimonioItemRow({ item, tone, last }) {
  const cfg = LOCALES[item.currency] || LOCALES.EUR;
  const decimals = item.currency === 'JPY' ? 0 : 2;
  const num = Math.abs(item.amount).toLocaleString(cfg.locale, {
    minimumFractionDigits: decimals, maximumFractionDigits: decimals,
    useGrouping: 'always',
  });
  return (
    <button style={{
      width: '100%', appearance: 'none', border: 0, background: 'transparent',
      display: 'flex', alignItems: 'center', gap: 12,
      padding: '12px 0', cursor: 'pointer',
      textAlign: 'left', fontFamily: 'inherit', color: 'inherit',
      borderBottom: last ? 0 : '1px solid var(--line-2)',
    }}>
      {/* Punto del grupo */}
      <span style={{
        width: 6, height: 6, borderRadius: '50%', background: tone,
        flexShrink: 0, marginLeft: 4,
      }} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{
          fontSize: 14, fontWeight: 600, letterSpacing: '-0.005em',
          color: 'var(--ink)',
          whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
        }}>{item.name}</div>
        <div style={{
          fontSize: 12, color: 'var(--muted)', marginTop: 1,
          whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
        }}>{item.subtitle}</div>
      </div>
      <span style={{
        display: 'inline-flex', alignItems: 'baseline', gap: 6,
        fontFeatureSettings: '"tnum"',
        fontWeight: 600, fontSize: 14, color: 'var(--ink)',
        letterSpacing: '-0.005em',
        whiteSpace: 'nowrap',
      }}>
        <span>{num}&nbsp;{cfg.symbol}</span>
        {item.currency !== 'EUR' && (
          <span style={{
            fontSize: 10, fontWeight: 600, letterSpacing: '0.05em',
            color: 'var(--muted)',
            background: 'var(--bg-2)', border: '1px solid var(--line)',
            padding: '1px 5px', borderRadius: 999,
          }}>{item.currency}</span>
        )}
      </span>
    </button>
  );
}

// ════════════════════════════════════════════════════════════
// SHEET · Añadir activo / pasivo
// ════════════════════════════════════════════════════════════
function AddPatrimonioSheet({ open, initialMode = 'asset', initialGroup, onClose, onSave }) {
  const [mode, setMode] = React.useState(initialMode);      // 'asset' | 'liab'
  const [group, setGroup] = React.useState(initialGroup || null);
  const [name, setName] = React.useState('');
  const [subtitle, setSubtitle] = React.useState('');
  const [amount, setAmount] = React.useState('');
  const [currency, setCurrency] = React.useState('EUR');
  const [mounted, setMounted] = React.useState(false);

  // Reset al abrir
  React.useEffect(() => {
    if (open) {
      setMode(initialMode || 'asset');
      setGroup(initialGroup || null);
      setName('');
      setSubtitle('');
      setAmount('');
      setCurrency('EUR');
      const id = requestAnimationFrame(() => setMounted(true));
      return () => cancelAnimationFrame(id);
    } else {
      setMounted(false);
    }
  }, [open, initialMode, initialGroup]);

  if (!open && !mounted) return null;

  const dict = mode === 'asset' ? ASSET_GROUPS : LIAB_GROUPS;
  const groupIds = Object.keys(dict);
  const isLiab = mode === 'liab';
  const canSave = name.trim() !== '' && group !== null && amount.trim() !== '';

  const handleSave = () => {
    if (!canSave) return;
    const parsed = parseFloat(amount.replace(',', '.'));
    if (isNaN(parsed)) return;
    onSave({
      id: `${mode === 'asset' ? 'a' : 'l'}-${Date.now()}`,
      group,
      name: name.trim(),
      subtitle: subtitle.trim() || dict[group].label,
      amount: parsed,
      currency,
    }, mode === 'asset' ? 'asset' : 'liab');
    onClose();
  };

  // Placeholders por grupo (ayuda contextual al usuario)
  const namePh = {
    bank:       'Ej: BBVA · Cuenta corriente',
    invest:     'Ej: MyInvestor · Indexada',
    realestate: 'Ej: Piso · Madrid',
    crypto:     'Ej: Bitcoin',
    cash:       'Ej: Efectivo en casa',
    mortgage:   'Ej: Hipoteca piso Madrid',
    loan:       'Ej: Préstamo coche',
    card:       'Ej: Tarjeta American Express',
  };
  const subPh = {
    bank:       'Últimos 4 dígitos · banco',
    invest:     'Tipo · estrategia',
    realestate: 'Uso o ubicación',
    crypto:     'Cantidad o ticker',
    cash:       'Dónde lo guardas',
    mortgage:   'Banco · plazo restante',
    loan:       'Banco · plazo',
    card:       'Saldo pendiente',
  };

  return (
    <>
      <div onClick={onClose} style={{
        position: 'absolute', inset: 0, zIndex: 40,
        background: mounted ? 'rgba(0,0,0,0.45)' : 'rgba(0,0,0,0)',
        transition: 'background 280ms cubic-bezier(.32,.72,0,1)',
      }} />

      <div style={{
        position: 'absolute', left: 0, right: 0, bottom: 0,
        zIndex: 45,
        height: '94%',
        background: 'var(--bg)',
        borderRadius: '28px 28px 0 0',
        boxShadow: '0 -18px 40px rgba(0,0,0,0.3)',
        transform: mounted ? 'translateY(0)' : 'translateY(100%)',
        transition: 'transform 360ms cubic-bezier(.32,.72,0,1)',
        display: 'flex', flexDirection: 'column',
        overflow: 'hidden',
        fontFamily: 'var(--font-sans)',
        color: 'var(--ink)',
      }}>
        {/* Handle */}
        <div style={{ display: 'flex', justifyContent: 'center', padding: '10px 0 4px' }}>
          <div style={{
            width: 36, height: 4, borderRadius: 999,
            background: 'var(--muted-2)', opacity: 0.5,
          }} />
        </div>

        {/* Top bar */}
        <div style={{
          display: 'flex', justifyContent: 'space-between', alignItems: 'center',
          padding: '6px 20px 12px',
        }}>
          <button onClick={onClose} style={{
            appearance: 'none', border: 0, background: 'transparent',
            color: 'var(--ink-2)', fontFamily: 'inherit',
            fontSize: 15, fontWeight: 500, cursor: 'pointer',
            padding: 6, margin: -6,
          }}>Cancelar</button>
          <h2 style={{
            margin: 0, fontSize: 16, fontWeight: 600,
            letterSpacing: '-0.01em', color: 'var(--ink)',
          }}>{isLiab ? 'Nuevo pasivo' : 'Nuevo activo'}</h2>
          <button onClick={handleSave} disabled={!canSave} style={{
            appearance: 'none', border: 0, background: 'transparent',
            color: canSave ? 'var(--brand)' : 'var(--muted-2)',
            fontFamily: 'inherit', fontSize: 15, fontWeight: 700,
            cursor: canSave ? 'pointer' : 'not-allowed',
            padding: 6, margin: -6,
          }}>Guardar</button>
        </div>

        <div style={{ flex: 1, overflow: 'auto', padding: '8px 20px 24px' }}>
          {/* Mode segmented */}
          <div style={{
            display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 6,
            background: 'var(--bg-2)', borderRadius: 12, padding: 4,
            border: '1px solid var(--line-2)', marginBottom: 22,
          }}>
            {[
              { id: 'asset', label: 'Activo',  color: 'var(--income)',  icon: 'arrowUp' },
              { id: 'liab',  label: 'Pasivo',  color: 'var(--expense)', icon: 'arrowDown' },
            ].map(o => {
              const active = mode === o.id;
              return (
                <button key={o.id}
                  onClick={() => { setMode(o.id); setGroup(null); }}
                  style={{
                    appearance: 'none', border: 0,
                    background: active ? 'var(--surface-2)' : 'transparent',
                    color: active ? o.color : 'var(--muted)',
                    padding: '10px 6px', borderRadius: 8,
                    fontFamily: 'inherit', fontSize: 14, fontWeight: 600,
                    cursor: 'pointer',
                    boxShadow: active ? 'var(--sh-1)' : 'none',
                    display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 6,
                  }}>
                  <Icon name={o.icon} size={14} stroke={2.4} />
                  {o.label}
                </button>
              );
            })}
          </div>

          {/* Tipo (grupo) */}
          <div style={{ marginBottom: 22 }}>
            <Label>Tipo</Label>
            <div style={{
              display: 'grid',
              gridTemplateColumns: groupIds.length >= 4 ? 'repeat(3, 1fr)' : 'repeat(3, 1fr)',
              gap: 10,
            }}>
              {groupIds.map(id => {
                const meta = dict[id];
                const selected = group === id;
                return (
                  <button key={id}
                    onClick={() => setGroup(id)}
                    style={{
                      appearance: 'none', border: '1px solid',
                      borderColor: selected ? 'var(--ink)' : 'var(--line)',
                      background: selected ? 'var(--surface-2)' : 'var(--surface)',
                      borderRadius: 14, padding: '12px 6px',
                      display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6,
                      cursor: 'pointer', fontFamily: 'inherit',
                      transition: 'border-color 160ms ease, background 160ms ease',
                      boxShadow: selected ? '0 0 0 3px var(--brand-soft)' : 'none',
                    }}>
                    <div style={{
                      width: 36, height: 36, borderRadius: 10,
                      background: meta.tone, color: 'var(--surface-2)',
                      display: 'grid', placeItems: 'center',
                    }}>
                      <Icon name={meta.icon} size={18} stroke={1.9} />
                    </div>
                    <span style={{
                      fontSize: 11, fontWeight: 500, color: 'var(--ink-2)',
                      lineHeight: 1.2, textAlign: 'center',
                    }}>{meta.label}</span>
                  </button>
                );
              })}
            </div>
          </div>

          {/* Nombre */}
          <div style={{ marginBottom: 22 }}>
            <Label>Nombre</Label>
            <div style={{
              background: 'var(--surface-2)', border: '1px solid var(--line)',
              borderRadius: 14, padding: '14px 16px',
            }}>
              <input
                type="text"
                placeholder={group ? namePh[group] : 'Cómo lo llamarás'}
                value={name}
                onChange={e => setName(e.target.value)}
                style={{
                  width: '100%', appearance: 'none', border: 0, background: 'transparent',
                  outline: 0, fontFamily: 'inherit', fontSize: 15,
                  color: 'var(--ink)',
                }} />
            </div>
          </div>

          {/* Valor + divisa */}
          <div style={{ marginBottom: 22 }}>
            <Label>{isLiab ? 'Importe pendiente' : 'Valor actual'}</Label>
            <div style={{
              background: 'var(--surface-2)', border: '1px solid var(--line)',
              borderRadius: 14, padding: '14px 16px',
              display: 'flex', alignItems: 'baseline', gap: 10,
            }}>
              <span style={{
                fontSize: 28, color: 'var(--muted-2)', fontWeight: 500,
                fontFeatureSettings: '"tnum"',
              }}>{LOCALES[currency].symbol}</span>
              <input
                inputMode="decimal"
                placeholder="0,00"
                value={amount}
                onChange={e => {
                  const v = e.target.value.replace(/[^\d.,]/g, '').replace('.', ',');
                  setAmount(v);
                }}
                style={{
                  flex: 1, appearance: 'none', border: 0, background: 'transparent',
                  outline: 0, fontFamily: 'inherit',
                  fontSize: 32, fontWeight: 600, color: 'var(--ink)',
                  fontFeatureSettings: '"tnum"',
                  letterSpacing: '-0.02em', minWidth: 0,
                  textAlign: 'right', padding: 0,
                }} />
              <CurrencyDropdown value={currency} onChange={setCurrency} />
            </div>
            {currency !== 'EUR' && amount && !isNaN(parseFloat(amount.replace(',', '.'))) && (
              <div style={{
                fontSize: 12, color: 'var(--muted)', marginTop: 8,
                fontFeatureSettings: '"tnum"', textAlign: 'right',
              }}>
                ≈ {formatMoney(toEUR(parseFloat(amount.replace(',', '.')), currency), 'EUR')} al cambio
              </div>
            )}
          </div>

          {/* Detalle */}
          <div style={{ marginBottom: 12 }}>
            <Label>Detalle <span style={{ color: 'var(--muted-2)', fontWeight: 400, textTransform: 'none', letterSpacing: 0 }}>(opcional)</span></Label>
            <div style={{
              background: 'var(--surface-2)', border: '1px solid var(--line)',
              borderRadius: 14, padding: '14px 16px',
            }}>
              <input
                type="text"
                placeholder={group ? subPh[group] : 'Una nota corta'}
                value={subtitle}
                onChange={e => setSubtitle(e.target.value)}
                style={{
                  width: '100%', appearance: 'none', border: 0, background: 'transparent',
                  outline: 0, fontFamily: 'inherit', fontSize: 15,
                  color: 'var(--ink)',
                }} />
            </div>
          </div>

          {/* Pista */}
          <div style={{
            marginTop: 8, fontSize: 12, color: 'var(--muted)',
            lineHeight: 1.5,
          }}>
            {isLiab
              ? 'El importe pendiente se resta del patrimonio neto. Podrás actualizarlo cuando hagas pagos.'
              : 'El valor se suma al patrimonio neto. Si está en otra divisa se convierte a EUR al cambio actual.'}
          </div>
        </div>
      </div>
    </>
  );
}

// ─────────────────────────────────────────────────────────
// Exportar al global
// ─────────────────────────────────────────────────────────
Object.assign(window, {
  PatrimonioScreen, AddPatrimonioSheet,
});
