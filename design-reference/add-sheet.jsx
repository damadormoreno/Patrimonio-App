// add-sheet.jsx — Modal de Añadir / Editar movimiento
// Carga DESPUÉS de shared.jsx y screens.jsx

function AddSheet({ open, onClose, onSave, editing }) {
  const [type, setType] = React.useState(editing?.type || 'expense');
  const [amount, setAmount] = React.useState(editing ? String(editing.amount).replace('.', ',') : '');
  const [currency, setCurrency] = React.useState(editing?.currency || 'EUR');
  const [cat, setCat] = React.useState(editing?.cat || null);
  const [date, setDate] = React.useState(editing?.date || '2026-05-21');
  const [note, setNote] = React.useState(editing?.note || '');
  const [mounted, setMounted] = React.useState(false);

  // Reset al abrir
  React.useEffect(() => {
    if (open) {
      setType(editing?.type || 'expense');
      setAmount(editing ? String(editing.amount).replace('.', ',') : '');
      setCurrency(editing?.currency || 'EUR');
      setCat(editing?.cat || null);
      setDate(editing?.date || '2026-05-21');
      setNote(editing?.note || '');
      // Trigger animation
      const id = requestAnimationFrame(() => setMounted(true));
      return () => cancelAnimationFrame(id);
    } else {
      setMounted(false);
    }
  }, [open, editing]);

  if (!open && !mounted) return null;

  const isExpense = type === 'expense';
  // Categorías relevantes para el tipo
  const cats = isExpense
    ? ['food', 'restaurants', 'transport', 'home', 'fun', 'subs', 'other']
    : ['salary', 'other'];

  const canSave = amount.trim() !== '' && cat !== null;
  const handleSave = () => {
    if (!canSave) return;
    const parsedAmount = parseFloat(amount.replace(',', '.'));
    if (isNaN(parsedAmount)) return;
    onSave({
      id: editing?.id || Date.now(),
      type, cat, currency, date, note,
      title: note || CATS[cat].label,
      amount: parsedAmount,
    });
    onClose();
  };

  return (
    <>
      {/* Backdrop */}
      <div
        onClick={onClose}
        style={{
          position: 'absolute', inset: 0, zIndex: 40,
          background: mounted ? 'rgba(0,0,0,0.45)' : 'rgba(0,0,0,0)',
          transition: 'background 280ms cubic-bezier(.32,.72,0,1)',
        }}
      />

      {/* Sheet */}
      <div style={{
        position: 'absolute', left: 0, right: 0, bottom: 0,
        zIndex: 45,
        height: '92%',
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
        <div style={{
          display: 'flex', justifyContent: 'center', padding: '10px 0 4px',
        }}>
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
          }}>{editing ? 'Editar movimiento' : 'Nuevo movimiento'}</h2>
          <button onClick={handleSave} disabled={!canSave} style={{
            appearance: 'none', border: 0, background: 'transparent',
            color: canSave ? 'var(--brand)' : 'var(--muted-2)',
            fontFamily: 'inherit', fontSize: 15, fontWeight: 700,
            cursor: canSave ? 'pointer' : 'not-allowed',
            padding: 6, margin: -6,
          }}>Guardar</button>
        </div>

        {/* Scrollable body */}
        <div style={{
          flex: 1, overflow: 'auto', padding: '8px 20px 24px',
        }}>
          {/* Type segmented */}
          <div style={{
            display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 6,
            background: 'var(--bg-2)', borderRadius: 12, padding: 4,
            border: '1px solid var(--line-2)', marginBottom: 22,
          }}>
            {[
              { id: 'expense', label: 'Gasto',   color: 'var(--expense)', icon: 'minus' },
              { id: 'income',  label: 'Ingreso', color: 'var(--income)',  icon: 'plus' },
            ].map(o => {
              const active = type === o.id;
              return (
                <button key={o.id}
                  onClick={() => { setType(o.id); setCat(null); }}
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

          {/* Amount input — big */}
          <div style={{ marginBottom: 22 }}>
            <Label>Importe</Label>
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
                  // Aceptar solo dígitos y una coma/punto
                  const v = e.target.value.replace(/[^\d.,]/g, '').replace('.', ',');
                  setAmount(v);
                }}
                style={{
                  flex: 1, appearance: 'none', border: 0, background: 'transparent',
                  outline: 0, fontFamily: 'inherit',
                  fontSize: 36, fontWeight: 600, color: 'var(--ink)',
                  fontFeatureSettings: '"tnum"',
                  letterSpacing: '-0.02em', minWidth: 0,
                  textAlign: 'right',
                  padding: 0,
                }} />
              {/* Currency selector */}
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

          {/* Category */}
          <div style={{ marginBottom: 22 }}>
            <Label>Categoría</Label>
            <div style={{
              display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 10,
            }}>
              {cats.map(cid => {
                const c = CATS[cid];
                const selected = cat === cid;
                return (
                  <button key={cid}
                    onClick={() => setCat(cid)}
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
                      background: c.tone, color: 'var(--surface-2)',
                      display: 'grid', placeItems: 'center',
                    }}>
                      <Icon name={c.icon} size={18} stroke={1.9} />
                    </div>
                    <span style={{
                      fontSize: 11, fontWeight: 500, color: 'var(--ink-2)',
                      lineHeight: 1.2, textAlign: 'center',
                    }}>{c.label}</span>
                  </button>
                );
              })}
            </div>
          </div>

          {/* Date */}
          <div style={{ marginBottom: 18 }}>
            <Label>Fecha</Label>
            <div style={{
              background: 'var(--surface-2)', border: '1px solid var(--line)',
              borderRadius: 14, padding: '14px 16px',
              display: 'flex', alignItems: 'center', gap: 12,
            }}>
              <Icon name="calendar" size={18} color="var(--muted)" />
              <span style={{ flex: 1, fontSize: 15, color: 'var(--ink)' }}>
                {formatDateLong(date)}
              </span>
              <Icon name="chevronD" size={16} color="var(--muted)" />
            </div>
          </div>

          {/* Note */}
          <div style={{ marginBottom: 12 }}>
            <Label>Nota <span style={{ color: 'var(--muted-2)', fontWeight: 400, textTransform: 'none', letterSpacing: 0 }}>(opcional)</span></Label>
            <div style={{
              background: 'var(--surface-2)', border: '1px solid var(--line)',
              borderRadius: 14, padding: '14px 16px',
            }}>
              <input
                type="text"
                placeholder={isExpense ? 'Ej: Comida del sábado' : 'Ej: Nómina mayo'}
                value={note}
                onChange={e => setNote(e.target.value)}
                style={{
                  width: '100%', appearance: 'none', border: 0, background: 'transparent',
                  outline: 0, fontFamily: 'inherit', fontSize: 15,
                  color: 'var(--ink)',
                }} />
            </div>
          </div>
        </div>
      </div>
    </>
  );
}

function Label({ children }) {
  return (
    <div style={{
      fontSize: 11, color: 'var(--muted)', fontWeight: 600,
      letterSpacing: '0.06em', textTransform: 'uppercase',
      marginBottom: 8,
    }}>{children}</div>
  );
}

function CurrencyDropdown({ value, onChange }) {
  const [open, setOpen] = React.useState(false);
  const codes = ['EUR', 'USD', 'GBP', 'JPY'];
  return (
    <div style={{ position: 'relative' }}>
      <button onClick={() => setOpen(o => !o)} style={{
        appearance: 'none', border: '1px solid var(--line)',
        background: 'var(--bg-2)',
        padding: '6px 10px', borderRadius: 999,
        fontFamily: 'inherit', fontSize: 12, fontWeight: 700,
        color: 'var(--ink-2)', cursor: 'pointer',
        display: 'inline-flex', alignItems: 'center', gap: 4,
        letterSpacing: '0.04em',
      }}>
        {value}
        <Icon name="chevronD" size={11} stroke={2.4} />
      </button>
      {open && (
        <div style={{
          position: 'absolute', top: 'calc(100% + 6px)', right: 0,
          background: 'var(--surface-2)', border: '1px solid var(--line)',
          borderRadius: 12, boxShadow: 'var(--sh-3)',
          padding: 4, zIndex: 100, minWidth: 110,
        }}>
          {codes.map(c => (
            <button key={c}
              onClick={() => { onChange(c); setOpen(false); }}
              style={{
                appearance: 'none', border: 0, background: 'transparent',
                width: '100%', textAlign: 'left',
                padding: '8px 10px', borderRadius: 8,
                fontFamily: 'inherit', fontSize: 13, color: 'var(--ink)',
                cursor: 'pointer',
                display: 'flex', justifyContent: 'space-between',
                alignItems: 'center',
                background: value === c ? 'var(--bg-2)' : 'transparent',
              }}>
              <span style={{ fontWeight: 600 }}>{c} · {LOCALES[c].symbol}</span>
              {value === c && <Icon name="check" size={14} color="var(--brand)" stroke={2.4} />}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function formatDateLong(iso) {
  const d = parseISO(iso);
  const diff = Math.round((TODAY - d) / 86400000);
  const prefix = diff === 0 ? 'Hoy · ' : diff === 1 ? 'Ayer · ' : '';
  return `${prefix}${d.getDate()} de ${MESES[d.getMonth()]} de ${d.getFullYear()}`;
}

Object.assign(window, { AddSheet, Label, CurrencyDropdown, formatDateLong });
