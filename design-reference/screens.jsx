// screens.jsx — 5 pantallas principales de App Finanzas
// Home · Movimientos · Presupuestos · Analíticas · Ajustes
// Carga DESPUÉS de shared.jsx

// ─────────────────────────────────────────────────────────
// Layout base de pantalla
// ─────────────────────────────────────────────────────────
const SCREEN_BASE = {
  height: '100%',
  display: 'flex',
  flexDirection: 'column',
  background: 'var(--bg)',
  color: 'var(--ink)',
  fontFamily: 'var(--font-sans)',
  paddingTop: 56, // status bar
};
const SCROLL_AREA = {
  flex: 1,
  overflowY: 'auto',
  overflowX: 'hidden',
  padding: '0 20px',
  paddingBottom: 110, // tab bar + safe area
};

// ─────────────────────────────────────────────────────────
// Subcomponentes compartidos por las pantallas
// ─────────────────────────────────────────────────────────

// Avatar pequeño · iniciales
// `interactive={false}` lo renderiza como <span> para poder anidarlo
// dentro de otra superficie clicable (evita <button> dentro de <button>).
function Avatar({ initials = 'M', onClick, size = 40, interactive = true }) {
  const Tag = interactive ? 'button' : 'span';
  return (
    <Tag
      {...(interactive ? { onClick, 'aria-label': 'Cuenta' } : {})}
      style={{
        width: size, height: size, borderRadius: '50%',
        background: 'var(--brand-soft)', color: 'var(--brand)',
        border: 0, cursor: interactive ? 'pointer' : 'inherit',
        display: 'grid', placeItems: 'center',
        fontFamily: 'var(--font-display)', fontStyle: 'italic',
        fontSize: 19, fontWeight: 400,
        letterSpacing: '-0.01em',
        flexShrink: 0,
      }}>{initials}</Tag>
  );
}

// Cabecera de subsección dentro del scroll
function SectionRow({ title, action, paddingTop = 28 }) {
  return (
    <div style={{
      display: 'flex', justifyContent: 'space-between', alignItems: 'baseline',
      paddingTop, paddingBottom: 8,
    }}>
      <h2 style={{
        margin: 0, fontSize: 17, fontWeight: 600,
        letterSpacing: '-0.01em', color: 'var(--ink)',
      }}>{title}</h2>
      {action}
    </div>
  );
}
function LinkBtn({ children, onClick }) {
  return (
    <button onClick={onClick} style={{
      appearance: 'none', border: 0, background: 'transparent',
      color: 'var(--ink-2)', fontFamily: 'inherit',
      fontSize: 13, fontWeight: 600, cursor: 'pointer',
      padding: '4px 6px', margin: '-4px -6px',
      borderRadius: 8,
      display: 'inline-flex', alignItems: 'center', gap: 2,
    }}>
      {children}
      <Icon name="chevronR" size={14} stroke={2} />
    </button>
  );
}

// Fila de transacción (uso compartido Home/Movs)
function TxRow({ tx, onClick }) {
  const c = CATS[tx.cat] || CATS.other;
  return (
    <button onClick={() => onClick && onClick(tx)} style={{
      width: '100%', appearance: 'none', border: 0, background: 'transparent',
      display: 'flex', alignItems: 'center', gap: 12,
      padding: '10px 0', cursor: onClick ? 'pointer' : 'default',
      textAlign: 'left', fontFamily: 'inherit', color: 'inherit',
      borderBottom: '1px solid var(--line-2)',
    }}>
      <CatIcon cat={tx.cat} size={40} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{
          fontSize: 15, fontWeight: 600, letterSpacing: '-0.005em',
          color: 'var(--ink)',
          whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
        }}>{tx.title}</div>
        <div style={{
          fontSize: 12, color: 'var(--muted)', marginTop: 2,
          display: 'flex', alignItems: 'center', gap: 6,
        }}>
          <span>{relDate(tx.date)}</span>
          <span style={{ width: 3, height: 3, borderRadius: '50%', background: 'var(--muted-2)', opacity: 0.6 }} />
          <span>{c.label}</span>
        </div>
      </div>
      <Amount type={tx.type} amount={tx.amount} currency={tx.currency} size={15} />
    </button>
  );
}

// ════════════════════════════════════════════════════════════
// 1 · HOME / DASHBOARD
// ════════════════════════════════════════════════════════════
function HomeScreen({ txs, onNavigate, currentMonth }) {
  const income = totalIncome(txs);
  const expense = totalSpent(txs);
  // Saldo: balance base + flujos del mes
  const balance = 1932.75 + income - expense;
  // Presupuesto total del mes
  const totalBudget = SAMPLE_BUDGETS.reduce((s, b) => s + b.limit, 0);
  const budgetPct = (expense / totalBudget) * 100;
  const recent = [...txs].slice(0, 4);

  return (
    <div style={SCREEN_BASE}>
      <ScreenHeader
        eyebrow={currentMonth}
        title="Hola, Marta"
        right={<Avatar initials="M" onClick={() => onNavigate('settings')} />}
      />
      <div style={SCROLL_AREA}>
        {/* Tarjeta de saldo */}
        <BalanceCard amount={balance} delta={{ pct: 2.4, sign: 'up' }} />

        {/* Stats del mes — ingresos/gastos */}
        <div style={{
          display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10,
          marginTop: 12,
        }}>
          <MiniStat label="Ingresos" amount={income} type="income" />
          <MiniStat label="Gastos" amount={expense} type="expense" />
        </div>

        {/* Resumen presupuesto del mes */}
        <BudgetSummaryCard spent={expense} limit={totalBudget} pct={budgetPct} />

        {/* Movimientos recientes */}
        <SectionRow
          title="Movimientos recientes"
          action={<LinkBtn onClick={() => onNavigate('movs')}>Ver todo</LinkBtn>}
        />
        <div style={{ background: 'var(--surface)', borderRadius: 18,
                      border: '1px solid var(--line)', padding: '4px 16px' }}>
          {recent.map((tx, i) => (
            <div key={tx.id} style={{
              borderBottom: i === recent.length - 1 ? 0 : '1px solid var(--line-2)',
            }}>
              <TxRow tx={tx} />
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function BalanceCard({ amount, delta }) {
  // Mostrar el importe completo en itálico (entero,decimales) para evitar
  // que el flourish del glifo itálico colisione con las decimales.
  // Las decimales se atenúan con opacidad para mantener la jerarquía.
  const intStr = Math.trunc(amount).toLocaleString('es-ES', { useGrouping: 'always' });
  const decStr = String(Math.round((Math.abs(amount) - Math.trunc(Math.abs(amount))) * 100)).padStart(2, '0');
  const upPos = delta && delta.sign === 'up';
  const pctStr = Math.abs(delta?.pct ?? 0).toLocaleString('es-ES', { minimumFractionDigits: 1, maximumFractionDigits: 1 });
  return (
    <div style={{
      marginTop: 16,
      background: 'linear-gradient(180deg, var(--surface-2), var(--surface))',
      border: '1px solid var(--line)',
      borderRadius: 22,
      padding: '18px 20px 20px',
      boxShadow: 'var(--sh-2)',
    }}>
      <div style={{
        display: 'flex', alignItems: 'center', gap: 8,
        fontSize: 11, letterSpacing: '0.12em', textTransform: 'uppercase',
        color: 'var(--muted)', fontWeight: 600,
      }}>
        Saldo total
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
          fontSize: 52, lineHeight: 1.05, letterSpacing: '-0.005em',
          color: 'var(--ink)',
          paddingRight: 4,
        }}>
          {intStr}<span style={{ opacity: 0.45 }}>,{decStr}</span>
        </span>
        <span style={{
          fontSize: 24, fontWeight: 500, color: 'var(--ink-2)',
        }}>€</span>
      </div>
      {delta && (
        <div style={{
          marginTop: 12, display: 'inline-flex', alignItems: 'center', gap: 5,
          padding: '4px 10px', borderRadius: 999,
          background: upPos ? 'var(--income-soft)' : 'var(--expense-soft)',
          color: upPos ? 'var(--income)' : 'var(--expense)',
          fontSize: 12, fontWeight: 600,
          fontVariantNumeric: 'tabular-nums',
        }}>
          <Icon name={upPos ? 'arrowUp' : 'arrowDown'} size={13} stroke={2.4} />
          {upPos ? '+' : '−'}{pctStr}% vs. abril
        </div>
      )}
    </div>
  );
}

function MiniStat({ label, amount, type }) {
  const color = type === 'income' ? 'var(--income)' : 'var(--ink)';
  const iconBg = type === 'income' ? 'var(--income-soft)' : 'var(--expense-soft)';
  const iconFg = type === 'income' ? 'var(--income)' : 'var(--expense)';
  const cfg = LOCALES.EUR;
  const num = Math.abs(amount).toLocaleString(cfg.locale, {
    minimumFractionDigits: 2, maximumFractionDigits: 2,
    useGrouping: 'always',
  });
  return (
    <div style={{
      background: 'var(--surface)', border: '1px solid var(--line)',
      borderRadius: 16, padding: '12px 14px',
      display: 'flex', flexDirection: 'column', gap: 4, minWidth: 0,
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <span style={{
          width: 20, height: 20, borderRadius: 6, background: iconBg,
          color: iconFg, display: 'grid', placeItems: 'center', flexShrink: 0,
        }}>
          <Icon name={type === 'income' ? 'arrowUp' : 'arrowDown'} size={13} stroke={2.4} />
        </span>
        <span style={{
          fontSize: 12, color: 'var(--muted)', fontWeight: 600,
          letterSpacing: '0.02em',
        }}>{label}</span>
      </div>
      <div style={{
        fontFeatureSettings: '"tnum"',
        fontWeight: 700, fontSize: 18, letterSpacing: '-0.01em',
        color, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
      }}>
        {type === 'income' ? '+' : '−'}{num} €
      </div>
    </div>
  );
}

function BudgetSummaryCard({ spent, limit, pct }) {
  const state = pct > 100 ? 'over' : pct > 80 ? 'warn' : 'ok';
  const remaining = limit - spent;
  return (
    <div style={{
      marginTop: 10,
      background: 'var(--surface)',
      border: '1px solid var(--line)',
      borderRadius: 16, padding: '14px 16px',
    }}>
      <div style={{
        display: 'flex', justifyContent: 'space-between', alignItems: 'baseline',
        marginBottom: 6,
      }}>
        <div style={{
          fontSize: 13, fontWeight: 600, color: 'var(--ink-2)',
        }}>Presupuesto del mes</div>
        <div style={{
          fontFeatureSettings: '"tnum"', fontSize: 12, color: 'var(--muted)',
        }}>
          <b style={{ color: 'var(--ink)', fontWeight: 600 }}>
            {formatMoney(spent, 'EUR')}
          </b> de {formatMoney(limit, 'EUR')}
        </div>
      </div>
      <BudgetBar pct={pct} state={state} />
      <div style={{
        marginTop: 8, fontSize: 12, color: 'var(--muted)',
        fontFeatureSettings: '"tnum"',
        display: 'flex', alignItems: 'center', gap: 6,
      }}>
        <Icon name={remaining > 0 ? 'check' : 'info'} size={13} stroke={2.2} />
        {remaining > 0
          ? <span>Te quedan <b style={{ color: 'var(--ink)', fontWeight: 600 }}>{formatMoney(remaining, 'EUR')}</b> · 10 días restantes</span>
          : <span style={{ color: 'var(--expense)', fontWeight: 600 }}>Excedido en {formatMoney(-remaining, 'EUR')}</span>}
      </div>
    </div>
  );
}

// ════════════════════════════════════════════════════════════
// 2 · LISTA DE MOVIMIENTOS
// ════════════════════════════════════════════════════════════
function MovimientosScreen({ txs, currentMonth }) {
  const [filter, setFilter] = React.useState('all'); // all | income | expense | <catId>
  const [showSearch, setShowSearch] = React.useState(false);
  const [query, setQuery] = React.useState('');

  // Filtrar
  const filtered = txs.filter(t => {
    if (filter === 'income' && t.type !== 'income') return false;
    if (filter === 'expense' && t.type !== 'expense') return false;
    if (filter !== 'all' && filter !== 'income' && filter !== 'expense' && t.cat !== filter) return false;
    if (query && !t.title.toLowerCase().includes(query.toLowerCase())) return false;
    return true;
  });

  // Total filtrado en EUR
  const totalEUR = filtered.reduce((s, t) =>
    s + (t.type === 'expense' ? -toEUR(t.amount, t.currency) : toEUR(t.amount, t.currency)), 0);

  // Agrupar por dateGroup, manteniendo orden cronológico inverso
  const groups = [];
  const groupMap = {};
  filtered.forEach(t => {
    const g = dateGroup(t.date);
    if (!groupMap[g]) {
      groupMap[g] = { label: g, items: [], total: 0 };
      groups.push(groupMap[g]);
    }
    groupMap[g].items.push(t);
    groupMap[g].total += t.type === 'expense' ? -toEUR(t.amount, t.currency) : toEUR(t.amount, t.currency);
  });

  const chips = [
    { id: 'all', label: 'Todos' },
    { id: 'expense', label: 'Gastos' },
    { id: 'income', label: 'Ingresos' },
    { id: 'food', label: 'Alimentación' },
    { id: 'restaurants', label: 'Restaurantes' },
    { id: 'transport', label: 'Transporte' },
    { id: 'home', label: 'Hogar' },
    { id: 'subs', label: 'Suscripciones' },
    { id: 'fun', label: 'Ocio' },
  ];

  return (
    <div style={SCREEN_BASE}>
      <ScreenHeader
        eyebrow={currentMonth}
        title="Movimientos"
        right={
          <div style={{ display: 'flex', gap: 8 }}>
            <HeaderIconBtn icon="search" label="Buscar"
              onClick={() => setShowSearch(s => !s)} />
            <HeaderIconBtn icon="filter" label="Filtros" />
          </div>
        }
      />

      {/* Buscador (colapsable) */}
      {showSearch && (
        <div style={{ padding: '0 20px 10px' }}>
          <div style={{
            display: 'flex', alignItems: 'center', gap: 10,
            background: 'var(--surface)', border: '1px solid var(--line)',
            borderRadius: 12, padding: '0 12px', height: 40,
          }}>
            <Icon name="search" size={16} color="var(--muted)" />
            <input autoFocus type="text" placeholder="Buscar movimiento…"
              value={query} onChange={e => setQuery(e.target.value)}
              style={{
                flex: 1, border: 0, background: 'transparent', outline: 0,
                fontFamily: 'inherit', fontSize: 14, color: 'var(--ink)',
              }} />
            {query && (
              <button onClick={() => setQuery('')} style={{
                appearance: 'none', border: 0, background: 'transparent',
                color: 'var(--muted)', cursor: 'pointer', padding: 4,
              }}>
                <Icon name="close" size={14} stroke={2} />
              </button>
            )}
          </div>
        </div>
      )}

      {/* Chips de filtro */}
      <div style={{
        display: 'flex', gap: 8, overflowX: 'auto', overflowY: 'hidden',
        padding: '4px 20px 14px',
        scrollbarWidth: 'none',
      }} className="no-scrollbar">
        {chips.map(c => (
          <button key={c.id}
            onClick={() => setFilter(c.id)}
            style={{
              appearance: 'none', border: '1px solid var(--line)',
              padding: '7px 14px', borderRadius: 999,
              background: filter === c.id ? 'var(--ink)' : 'var(--bg-2)',
              color: filter === c.id ? 'var(--bg)' : 'var(--ink-2)',
              fontFamily: 'inherit', fontSize: 13, fontWeight: 500,
              cursor: 'pointer', whiteSpace: 'nowrap',
              transition: 'background 160ms ease, color 160ms ease',
              flexShrink: 0,
            }}>{c.label}</button>
        ))}
      </div>

      {/* Sub-cabecera con total del filtro */}
      <div style={{
        padding: '0 20px 4px',
        display: 'flex', justifyContent: 'space-between', alignItems: 'baseline',
      }}>
        <span style={{ fontSize: 12, color: 'var(--muted)', fontWeight: 500 }}>
          {filtered.length} movimiento{filtered.length === 1 ? '' : 's'}
        </span>
        <span style={{
          fontSize: 13, fontWeight: 600,
          fontFeatureSettings: '"tnum"',
          color: totalEUR >= 0 ? 'var(--income)' : 'var(--ink)',
        }}>
          Neto: {totalEUR >= 0 ? '+' : '−'}{formatMoney(Math.abs(totalEUR), 'EUR')}
        </span>
      </div>

      <div style={{ ...SCROLL_AREA, paddingTop: 8 }}>
        {filtered.length === 0 ? (
          <EmptyState
            icon="receipt"
            title="Sin movimientos con esos filtros"
            sub="Prueba a ampliar la búsqueda o quita algún filtro."
          />
        ) : (
          groups.map(g => (
            <div key={g.label} style={{ marginBottom: 14 }}>
              <div style={{
                display: 'flex', justifyContent: 'space-between',
                alignItems: 'baseline',
                padding: '14px 4px 6px',
              }}>
                <span style={{
                  fontSize: 11, letterSpacing: '0.1em', textTransform: 'uppercase',
                  color: 'var(--muted)', fontWeight: 600,
                }}>{g.label}</span>
                <span style={{
                  fontSize: 11, fontWeight: 600,
                  fontFeatureSettings: '"tnum"',
                  color: 'var(--muted)',
                }}>
                  {g.total >= 0 ? '+' : '−'}{formatMoney(Math.abs(g.total), 'EUR')}
                </span>
              </div>
              <div style={{
                background: 'var(--surface)', border: '1px solid var(--line)',
                borderRadius: 16, padding: '0 16px',
              }}>
                {g.items.map((tx, i) => (
                  <div key={tx.id} style={{
                    borderBottom: i === g.items.length - 1 ? 0 : '1px solid var(--line-2)',
                  }}>
                    <TxRow tx={tx} />
                  </div>
                ))}
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  );
}

function EmptyState({ icon, title, sub, action }) {
  return (
    <div style={{
      textAlign: 'center',
      padding: '36px 20px',
      background: 'var(--bg-2)',
      border: '1px dashed var(--line)',
      borderRadius: 16,
      marginTop: 8,
    }}>
      <div style={{
        width: 48, height: 48, borderRadius: '50%',
        background: 'var(--surface)', border: '1px solid var(--line)',
        margin: '0 auto 12px',
        display: 'grid', placeItems: 'center',
        color: 'var(--muted)',
      }}>
        <Icon name={icon} size={20} />
      </div>
      <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--ink)', marginBottom: 6 }}>{title}</div>
      {sub && <div style={{ fontSize: 13, color: 'var(--muted)', lineHeight: 1.45, maxWidth: 260, margin: '0 auto' }}>{sub}</div>}
      {action && <div style={{ marginTop: 14 }}>{action}</div>}
    </div>
  );
}

// ════════════════════════════════════════════════════════════
// 4 · PRESUPUESTOS
// ════════════════════════════════════════════════════════════
function PresupuestosScreen({ txs, currentMonth }) {
  // Calcular gasto por presupuesto
  const items = SAMPLE_BUDGETS.map(b => {
    const spent = spentByCat(txs, b.cat);
    const pct = (spent / b.limit) * 100;
    const state = pct > 100 ? 'over' : pct >= 80 ? 'warn' : 'ok';
    return { ...b, spent, pct, state };
  }).sort((a, b) => b.pct - a.pct);

  const totalLimit = SAMPLE_BUDGETS.reduce((s, b) => s + b.limit, 0);
  const totalSpentVal = items.reduce((s, b) => s + b.spent, 0);
  const remaining = totalLimit - totalSpentVal;
  const totalPct = (totalSpentVal / totalLimit) * 100;
  const totalState = totalPct > 100 ? 'over' : totalPct >= 80 ? 'warn' : 'ok';

  return (
    <div style={SCREEN_BASE}>
      <ScreenHeader
        eyebrow={currentMonth}
        title="Presupuestos"
        right={<HeaderIconBtn icon="calendar" label="Cambiar mes" />}
      />
      <div style={SCROLL_AREA}>
        {/* Resumen mensual */}
        <div style={{
          marginTop: 8,
          background: 'linear-gradient(180deg, var(--surface-2), var(--surface))',
          border: '1px solid var(--line)',
          borderRadius: 22, padding: '18px 20px 20px',
          boxShadow: 'var(--sh-2)',
        }}>
          <div style={{
            fontSize: 11, letterSpacing: '0.12em', textTransform: 'uppercase',
            color: 'var(--muted)', fontWeight: 600,
          }}>Te quedan este mes</div>
          <div style={{
            display: 'flex', alignItems: 'baseline', gap: 12, marginTop: 8,
          }}>
            <span style={{
              fontFamily: 'var(--font-display)', fontStyle: 'italic',
              fontSize: 44, lineHeight: 1.05, letterSpacing: '-0.005em',
              color: remaining >= 0 ? 'var(--ink)' : 'var(--expense)',
              paddingRight: 4,
            }}>
              {Math.trunc(Math.abs(remaining)).toLocaleString('es-ES', { useGrouping: 'always' })}
              <span style={{ opacity: 0.45 }}>,{String(Math.round((Math.abs(remaining) - Math.trunc(Math.abs(remaining))) * 100)).padStart(2,'0')}</span>
            </span>
            <span style={{
              fontSize: 22, fontWeight: 500, color: 'var(--ink-2)',
            }}>€</span>
          </div>
          <div style={{ marginTop: 14 }}>
            <BudgetBar pct={totalPct} state={totalState} />
          </div>
          <div style={{
            marginTop: 8, display: 'flex', justifyContent: 'space-between',
            fontSize: 12, color: 'var(--muted)',
            fontFeatureSettings: '"tnum"',
          }}>
            <span>Gastado <b style={{ color: 'var(--ink)', fontWeight: 600 }}>{formatMoney(totalSpentVal, 'EUR')}</b></span>
            <span>Total <b style={{ color: 'var(--ink)', fontWeight: 600 }}>{formatMoney(totalLimit, 'EUR')}</b></span>
          </div>
        </div>

        {/* Lista de presupuestos por categoría */}
        <SectionRow title="Por categoría" action={
          <span style={{ fontSize: 12, color: 'var(--muted)' }}>{items.length} activos</span>
        } />
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          {items.map(b => <BudgetRow key={b.cat} item={b} />)}
        </div>

        {/* CTA añadir */}
        <button style={{
          width: '100%', marginTop: 16,
          appearance: 'none', border: '1px dashed var(--line)',
          background: 'transparent', color: 'var(--ink-2)',
          padding: '14px 16px', borderRadius: 16,
          display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
          fontFamily: 'inherit', fontSize: 14, fontWeight: 500,
          cursor: 'pointer',
        }}>
          <Icon name="plus" size={16} stroke={2} />
          Nuevo presupuesto
        </button>
      </div>
    </div>
  );
}

function BudgetRow({ item }) {
  const c = CATS[item.cat];
  const statusText = {
    ok:   ['En camino', 'check'],
    warn: ['Atención · cerca del límite', 'info'],
    over: [`Excedido en ${formatMoney(item.spent - item.limit, 'EUR')}`, 'close'],
  }[item.state];
  const statusColor = {
    ok: 'var(--muted)', warn: 'var(--alert)', over: 'var(--expense)',
  }[item.state];
  return (
    <div style={{
      background: 'var(--surface)', border: '1px solid var(--line)',
      borderRadius: 16, padding: '14px 16px',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 10 }}>
        <CatIcon cat={item.cat} size={36} />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{
            fontSize: 15, fontWeight: 600, letterSpacing: '-0.005em',
            color: 'var(--ink)',
          }}>{c.label}</div>
          <div style={{
            fontSize: 12, color: 'var(--muted)',
            fontFeatureSettings: '"tnum"', marginTop: 1,
          }}>
            <b style={{ color: 'var(--ink-2)', fontWeight: 600 }}>{formatMoney(item.spent, 'EUR')}</b> de {formatMoney(item.limit, 'EUR')}
          </div>
        </div>
        <span style={{
          fontFeatureSettings: '"tnum"', fontWeight: 700, fontSize: 16,
          color: item.state === 'over' ? 'var(--expense)'
               : item.state === 'warn' ? 'var(--alert)' : 'var(--ink)',
          letterSpacing: '-0.01em',
        }}>{Math.round(item.pct)}%</span>
      </div>
      <BudgetBar pct={item.pct} state={item.state} />
      <div style={{
        marginTop: 8, display: 'flex', alignItems: 'center', gap: 5,
        fontSize: 12, color: statusColor, fontWeight: item.state === 'ok' ? 500 : 600,
      }}>
        <Icon name={statusText[1]} size={13} stroke={2.2} />
        {statusText[0]}
      </div>
    </div>
  );
}

// ════════════════════════════════════════════════════════════
// 5 · ANALÍTICAS
// ════════════════════════════════════════════════════════════
function AnaliticasScreen({ txs, currentMonth }) {
  const [view, setView] = React.useState('cat'); // 'cat' | 'trend'
  const [period, setPeriod] = React.useState('mes');

  // Por categoría
  const byCat = Object.keys(CATS)
    .filter(c => c !== 'salary')
    .map(c => ({ cat: c, total: spentByCat(txs, c) }))
    .filter(x => x.total > 0)
    .sort((a, b) => b.total - a.total);
  const maxCat = Math.max(1, ...byCat.map(x => x.total));
  const totalCat = byCat.reduce((s, x) => s + x.total, 0);

  return (
    <div style={SCREEN_BASE}>
      <ScreenHeader
        eyebrow={currentMonth}
        title="Analíticas"
        right={
          <div style={{
            display: 'inline-flex', background: 'var(--surface)',
            border: '1px solid var(--line)', borderRadius: 999, padding: 3,
            fontSize: 12, fontWeight: 600,
          }}>
            {['mes', '3M', 'año'].map(p => (
              <button key={p} onClick={() => setPeriod(p)} style={{
                appearance: 'none', border: 0, background: period === p ? 'var(--ink)' : 'transparent',
                color: period === p ? 'var(--bg)' : 'var(--muted)',
                padding: '5px 10px', borderRadius: 999, fontFamily: 'inherit',
                fontSize: 12, fontWeight: 600, cursor: 'pointer',
                letterSpacing: '0.02em',
              }}>{p}</button>
            ))}
          </div>
        }
      />
      <div style={SCROLL_AREA}>
        {/* Toggle vista */}
        <div style={{
          marginTop: 8,
          display: 'flex', background: 'var(--bg-2)', borderRadius: 12,
          padding: 4, gap: 4, border: '1px solid var(--line-2)',
        }}>
          {[
            { id: 'cat',   label: 'Por categoría' },
            { id: 'trend', label: 'Tendencia' },
          ].map(opt => (
            <button key={opt.id} onClick={() => setView(opt.id)} style={{
              flex: 1, appearance: 'none', border: 0,
              background: view === opt.id ? 'var(--surface-2)' : 'transparent',
              color: view === opt.id ? 'var(--ink)' : 'var(--muted)',
              padding: '8px 6px', borderRadius: 8,
              fontFamily: 'inherit', fontSize: 13, fontWeight: 600,
              cursor: 'pointer',
              boxShadow: view === opt.id ? 'var(--sh-1)' : 'none',
              transition: 'background 160ms ease, color 160ms ease',
            }}>{opt.label}</button>
          ))}
        </div>

        {view === 'cat' && (
          <>
            {/* Resumen */}
            <div style={{
              marginTop: 14,
              background: 'var(--surface)', border: '1px solid var(--line)',
              borderRadius: 16, padding: '14px 16px',
              display: 'flex', justifyContent: 'space-between', alignItems: 'baseline',
            }}>
              <div>
                <div style={{ fontSize: 11, letterSpacing: '0.1em', textTransform: 'uppercase', color: 'var(--muted)', fontWeight: 600 }}>
                  Total gastado
                </div>
                <div style={{
                  fontFeatureSettings: '"tnum"', fontWeight: 700, fontSize: 24,
                  letterSpacing: '-0.01em', marginTop: 2,
                }}>{formatMoney(totalCat, 'EUR')}</div>
              </div>
              <Pill tone="brand">Top: {CATS[byCat[0]?.cat]?.label || '—'}</Pill>
            </div>

            {/* Bars */}
            <SectionRow title="Gasto por categoría" />
            <div style={{
              background: 'var(--surface)', border: '1px solid var(--line)',
              borderRadius: 16, padding: '14px 16px',
              display: 'flex', flexDirection: 'column', gap: 14,
            }}>
              {byCat.map(x => {
                const pct = (x.total / maxCat) * 100;
                const share = (x.total / totalCat) * 100;
                const c = CATS[x.cat];
                return (
                  <div key={x.cat}>
                    <div style={{
                      display: 'flex', justifyContent: 'space-between', alignItems: 'baseline',
                      marginBottom: 6, gap: 12,
                    }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
                        <span style={{
                          width: 8, height: 8, borderRadius: 2, background: c.tone, flexShrink: 0,
                        }} />
                        <span style={{
                          fontSize: 13, fontWeight: 500, color: 'var(--ink)',
                          whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                        }}>{c.label}</span>
                        <span style={{
                          fontSize: 11, color: 'var(--muted)', fontFeatureSettings: '"tnum"',
                        }}>{Math.round(share)}%</span>
                      </div>
                      <span style={{
                        fontFeatureSettings: '"tnum"', fontSize: 13, fontWeight: 600,
                        color: 'var(--ink)', letterSpacing: '-0.005em',
                      }}>{formatMoney(x.total, 'EUR')}</span>
                    </div>
                    <div style={{
                      height: 8, background: 'var(--bg-2)', borderRadius: 999,
                      border: '1px solid var(--line-2)', overflow: 'hidden',
                    }}>
                      <div style={{
                        width: `${pct}%`, height: '100%',
                        background: c.tone, borderRadius: 999,
                        transition: 'width 380ms cubic-bezier(.4,.6,.2,1)',
                      }} />
                    </div>
                  </div>
                );
              })}
            </div>
          </>
        )}

        {view === 'trend' && (
          <>
            {/* Trend chart */}
            <div style={{
              marginTop: 14,
              background: 'var(--surface)', border: '1px solid var(--line)',
              borderRadius: 16, padding: '14px 16px 16px',
            }}>
              <div style={{
                display: 'flex', justifyContent: 'space-between', alignItems: 'baseline',
              }}>
                <div>
                  <div style={{ fontSize: 11, letterSpacing: '0.1em', textTransform: 'uppercase', color: 'var(--muted)', fontWeight: 600 }}>
                    Promedio 6 meses
                  </div>
                  <div style={{
                    fontFeatureSettings: '"tnum"', fontWeight: 700, fontSize: 24,
                    letterSpacing: '-0.01em', marginTop: 2,
                  }}>{formatMoney(TREND_6M.reduce((s, m) => s + m.v, 0) / TREND_6M.length, 'EUR')}</div>
                </div>
                <Pill tone="income">↓ 7,4% vs. abril</Pill>
              </div>
              <div style={{ marginTop: 14 }}>
                <TrendChart data={TREND_6M} />
              </div>
            </div>

            <SectionRow title="Por mes" />
            <div style={{
              background: 'var(--surface)', border: '1px solid var(--line)',
              borderRadius: 16, padding: '0 16px',
            }}>
              {[...TREND_6M].reverse().map((m, i) => (
                <div key={m.m} style={{
                  display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                  padding: '12px 0',
                  borderBottom: i === TREND_6M.length - 1 ? 0 : '1px solid var(--line-2)',
                }}>
                  <div>
                    <div style={{ fontSize: 14, fontWeight: 600 }}>{m.m} 2026</div>
                    {i === 0 && <div style={{ fontSize: 11, color: 'var(--muted)', marginTop: 2 }}>Mes en curso · parcial</div>}
                  </div>
                  <span style={{
                    fontFeatureSettings: '"tnum"', fontWeight: 600, fontSize: 15,
                    color: 'var(--ink)',
                  }}>{formatMoney(m.v, 'EUR')}</span>
                </div>
              ))}
            </div>
          </>
        )}
      </div>
    </div>
  );
}

function TrendChart({ data }) {
  const W = 320, H = 140, P = 18;
  const max = Math.max(...data.map(d => d.v)) * 1.1;
  const step = (W - P * 2) / (data.length - 1);
  const pts = data.map((d, i) => ({
    x: P + i * step,
    y: H - P - ((d.v / max) * (H - P * 2)),
    v: d.v, m: d.m,
  }));
  const linePath = pts.map((p, i) => `${i === 0 ? 'M' : 'L'} ${p.x} ${p.y}`).join(' ');
  const areaPath = linePath + ` L ${pts[pts.length-1].x} ${H - P} L ${pts[0].x} ${H - P} Z`;
  return (
    <svg viewBox={`0 0 ${W} ${H}`} width="100%" style={{ display: 'block' }}>
      <defs>
        <linearGradient id="trend-area" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="var(--brand)" stopOpacity="0.22"/>
          <stop offset="100%" stopColor="var(--brand)" stopOpacity="0"/>
        </linearGradient>
      </defs>
      {/* grid */}
      {[0.25, 0.5, 0.75].map(g => (
        <line key={g} x1={P} x2={W - P}
              y1={P + (H - P * 2) * g} y2={P + (H - P * 2) * g}
              stroke="var(--line-2)" strokeDasharray="2 4" />
      ))}
      <path d={areaPath} fill="url(#trend-area)" />
      <path d={linePath} stroke="var(--brand)" strokeWidth="2.2" fill="none"
            strokeLinecap="round" strokeLinejoin="round" />
      {pts.map((p, i) => (
        <g key={i}>
          <circle cx={p.x} cy={p.y} r={i === pts.length - 1 ? 5 : 3}
                  fill="var(--surface)" stroke="var(--brand)" strokeWidth="2" />
          <text x={p.x} y={H - 4} textAnchor="middle"
                fontFamily="var(--font-sans)" fontSize="10"
                fill="var(--muted)" fontWeight="600">{p.m}</text>
        </g>
      ))}
    </svg>
  );
}

// ════════════════════════════════════════════════════════════
// 6 · AJUSTES
// ════════════════════════════════════════════════════════════
function AjustesScreen({ dark, setDark, themeMode, setThemeMode, profile, platform = 'ios', onOpenProfile }) {
  const p = profile || { firstName: 'Marta', lastName: 'Aldea', backup: false };
  const provName = platform === 'android' ? 'Google Drive' : 'iCloud';
  return (
    <div style={SCREEN_BASE}>
      <ScreenHeader title="Ajustes" />
      <div style={SCROLL_AREA}>
        {/* Perfil */}
        <button onClick={onOpenProfile} data-comment-anchor="ajustes-perfil" style={{
          width: '100%',
          display: 'flex', alignItems: 'center', gap: 14,
          marginTop: 4, padding: '14px 16px',
          background: 'var(--surface)', border: '1px solid var(--line)',
          borderRadius: 16,
          appearance: 'none', cursor: 'pointer',
          fontFamily: 'inherit', color: 'inherit', textAlign: 'left',
        }}>
          <Avatar interactive={false} initials={`${(p.firstName || 'M').charAt(0)}${(p.lastName || '').charAt(0)}`.toUpperCase()} size={48} />
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: 16, fontWeight: 600, color: 'var(--ink)' }}>{p.firstName} {p.lastName}</div>
            <div style={{ fontSize: 12, color: 'var(--muted)', marginTop: 2 }}>
              {p.backup ? `Sincronizada con ${provName}` : 'Cuenta local · offline'}
            </div>
          </div>
          <Icon name="chevronR" size={18} color="var(--muted)" />
        </button>

        {/* Cuenta */}
        <SettingsSection title="Cuenta" />
        <SettingsCard>
          <SettingsRow icon="globe" label="Divisa principal" value="EUR · €" />
          <SettingsRow icon="tag" label="Categorías" value="8" />
          <SettingsRow icon="swap" label="Tasas de cambio" value="Manuales" />
          <SettingsRow icon="bell" label="Notificaciones" toggle value={true} last />
        </SettingsCard>

        {/* Apariencia */}
        <SettingsSection title="Apariencia" />
        <SettingsCard>
          <div style={{ padding: '14px 16px' }}>
            <div style={{
              display: 'flex', alignItems: 'center', gap: 12, marginBottom: 12,
            }}>
              <div style={{
                width: 30, height: 30, borderRadius: 8,
                background: 'var(--bg-2)', color: 'var(--ink-2)',
                display: 'grid', placeItems: 'center',
              }}>
                <Icon name={dark ? 'moon' : 'sun'} size={16} />
              </div>
              <span style={{ fontSize: 15, color: 'var(--ink)' }}>Tema</span>
            </div>
            <div style={{
              display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 6,
              background: 'var(--bg-2)', borderRadius: 12, padding: 4,
              border: '1px solid var(--line-2)',
            }}>
              {[
                { id: 'light', label: 'Claro' },
                { id: 'dark',  label: 'Oscuro' },
                { id: 'system', label: 'Sistema' },
              ].map(o => (
                <button key={o.id}
                  onClick={() => {
                    setThemeMode(o.id);
                    if (o.id === 'dark') setDark(true);
                    else if (o.id === 'light') setDark(false);
                  }}
                  style={{
                    appearance: 'none', border: 0,
                    background: themeMode === o.id ? 'var(--surface-2)' : 'transparent',
                    color: themeMode === o.id ? 'var(--ink)' : 'var(--muted)',
                    padding: '8px 6px', borderRadius: 8,
                    fontFamily: 'inherit', fontSize: 13, fontWeight: 600,
                    cursor: 'pointer',
                    boxShadow: themeMode === o.id ? 'var(--sh-1)' : 'none',
                  }}>{o.label}</button>
              ))}
            </div>
          </div>
        </SettingsCard>

        {/* Datos */}
        <SettingsSection title="Datos" />
        <SettingsCard>
          <SettingsRow icon="download" label="Exportar datos" value="CSV · JSON" />
          <SettingsRow icon="upload" label="Importar datos" />
          <SettingsRow icon="trash" label="Borrar todos los datos" danger last />
        </SettingsCard>

        {/* Información */}
        <SettingsSection title="Información" />
        <SettingsCard>
          <SettingsRow icon="info" label="Acerca de" />
          <SettingsRow icon="receipt" label="Términos y privacidad" />
          <SettingsRow icon="user" label="Versión" value="1.0.0" last chevron={false} />
        </SettingsCard>

        <div style={{
          marginTop: 18, fontSize: 11, color: 'var(--muted-2)',
          textAlign: 'center', letterSpacing: '0.04em',
        }}>
          Tus datos viven solo en este dispositivo.
        </div>
      </div>
    </div>
  );
}

function SettingsSection({ title }) {
  return (
    <div style={{
      paddingTop: 20, paddingBottom: 6,
      fontSize: 11, letterSpacing: '0.1em', textTransform: 'uppercase',
      color: 'var(--muted)', fontWeight: 600,
    }}>{title}</div>
  );
}
function SettingsCard({ children }) {
  return (
    <div style={{
      background: 'var(--surface)', border: '1px solid var(--line)',
      borderRadius: 16, overflow: 'hidden',
    }}>{children}</div>
  );
}
function SettingsRow({ icon, label, value, toggle, danger, chevron = true, last }) {
  const [on, setOn] = React.useState(value);
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 12,
      padding: '14px 16px',
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
      <div style={{
        flex: 1, fontSize: 15, color: danger ? 'var(--expense)' : 'var(--ink)',
      }}>{label}</div>
      {value !== undefined && !toggle && (
        <span style={{
          fontSize: 13, color: 'var(--muted)',
          fontFeatureSettings: '"tnum"',
        }}>{value}</span>
      )}
      {toggle && (
        <button onClick={() => setOn(!on)} style={{
          position: 'relative', width: 42, height: 24, borderRadius: 999,
          background: on ? 'var(--income)' : 'var(--line)',
          border: 0, cursor: 'pointer', padding: 0,
          transition: 'background 200ms ease',
        }}>
          <span style={{
            position: 'absolute', top: 2, left: on ? 20 : 2,
            width: 20, height: 20, borderRadius: '50%',
            background: 'var(--surface-2)',
            boxShadow: '0 1px 3px rgba(0,0,0,0.2)',
            transition: 'left 200ms ease',
          }} />
        </button>
      )}
      {chevron && !toggle && value === undefined && (
        <Icon name="chevronR" size={16} color="var(--muted)" />
      )}
    </div>
  );
}

// ─────────────────────────────────────────────────────────
// Exportar al global
// ─────────────────────────────────────────────────────────
Object.assign(window, {
  HomeScreen, MovimientosScreen, PresupuestosScreen,
  AnaliticasScreen, AjustesScreen,
  Avatar, TxRow, EmptyState, SectionRow, LinkBtn,
  SCREEN_BASE, SCROLL_AREA,
});
