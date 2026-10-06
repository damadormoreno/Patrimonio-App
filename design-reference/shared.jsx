// shared.jsx — Icons, formatters, primitives compartidos en App Finanzas
// Carga ANTES de screens.jsx y app.jsx.

// ─────────────────────────────────────────────────────────
// ICONOS · stroke 1.7, line/round-cap, 24×24 viewBox
// ─────────────────────────────────────────────────────────
const ICONS = {
  home:    "M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1z",
  list:    "M4 6h16M4 12h16M4 18h10",
  chart:   "M4 19V10M10 19V5M16 19v-7M22 19V8",
  pie:     "M12 3a9 9 0 1 0 9 9h-9V3z",
  cog:     "M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6zm9.4 3l-2 1.2.2 2.3-2 1-1.4-1.8-2.3.4-.7 2.2H9.8l-.7-2.2-2.3-.4L5.4 17l-2-1 .2-2.3-2-1.2 2-1.2-.2-2.3 2-1 1.4 1.8 2.3-.4.7-2.2h2.4l.7 2.2 2.3.4 1.4-1.8 2 1-.2 2.3z",
  plus:    "M12 5v14M5 12h14",
  minus:   "M5 12h14",
  arrowUp:   "M7 14l5-5 5 5",
  arrowDown: "M7 10l5 5 5-5",
  arrowRight: "M5 12h14M13 5l7 7-7 7",
  arrowLeft:  "M19 12H5M11 5l-7 7 7 7",
  search:  "M11 4a7 7 0 1 0 4.6 12.3l4.4 4.4M16 11a5 5 0 1 1-10 0 5 5 0 0 1 10 0",
  filter:  "M3 5h18l-7 9v6l-4-2v-4z",
  calendar:"M5 4h14a1 1 0 0 1 1 1v15a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zM3 9h18M8 2v4M16 2v4",
  chevronD:"M6 9l6 6 6-6",
  chevronR:"M9 6l6 6-6 6",
  chevronL:"M15 6l-6 6 6 6",
  close:   "M5 5l14 14M19 5L5 19",
  check:   "M5 12l4 4L19 7",
  pencil:  "M14 4l6 6-11 11H3v-6L14 4z",
  trash:   "M4 7h16M9 7V4h6v3M6 7l1 13a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1l1-13",
  download:"M12 4v12m0 0l-5-5m5 5l5-5M4 20h16",
  upload:  "M12 20V8m0 0l-5 5m5-5l5 5M4 4h16",
  globe:   "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zm-9 9h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18",
  bell:    "M6 16V11a6 6 0 0 1 12 0v5l2 2H4l2-2zM10 20a2 2 0 0 0 4 0",
  tag:     "M3 12l9-9h7v7l-9 9-7-7zM15 8a1 1 0 1 0 0-2 1 1 0 0 0 0 2z",
  cart:    "M3 4h2l2 12h11l2-8H6M9 20a1 1 0 1 0 0-2 1 1 0 0 0 0 2zM17 20a1 1 0 1 0 0-2 1 1 0 0 0 0 2z",
  utensils:"M7 3v9a3 3 0 0 1-3 3M7 3v18M14 3c-1 2-1 6 0 8h4M18 3v18",
  car:     "M4 11l2-5h12l2 5M3 17V11h18v6M5 17v2H3v-2M21 17v2h-2v-2M7 14h.01M17 14h.01",
  house:   "M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-4v-7h-8v7H4a1 1 0 0 1-1-1z",
  music:   "M9 18V5l11-2v13M9 18a3 3 0 1 1-3-3 3 3 0 0 1 3 3zM20 16a3 3 0 1 1-3-3 3 3 0 0 1 3 3z",
  spark:   "M12 3l2.4 5.6L20 11l-5.6 2.4L12 19l-2.4-5.6L4 11l5.6-2.4L12 3z",
  briefcase:"M3 8h18v12H3zM8 8V5a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v3M3 14h18",
  receipt: "M5 3h14v18l-3-2-2 2-2-2-2 2-2-2-3 2zM8 8h8M8 12h8M8 16h5",
  user:    "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 21a8 8 0 0 1 16 0",
  sun:     "M12 4V2M12 22v-2M4 12H2M22 12h-2M5.6 5.6L4.2 4.2M19.8 19.8l-1.4-1.4M5.6 18.4l-1.4 1.4M19.8 4.2l-1.4 1.4M12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10z",
  moon:    "M20 14a8 8 0 0 1-10-10 8 8 0 1 0 10 10z",
  info:    "M12 21a9 9 0 1 1 0-18 9 9 0 0 1 0 18zM12 11v6M12 7.5h.01",
  trend:   "M3 17l6-6 4 4 8-8M14 7h7v7",
  swap:    "M7 4l-4 4 4 4M3 8h14M17 20l4-4-4-4M21 16H7",
  wallet:  "M3 7v11a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7h-5.5a2.5 2.5 0 0 1 0-5H21V6a1 1 0 0 0-1-1H5a2 2 0 0 0-2 2zM17 13h.01",
  bank:    "M3 10l9-6 9 6M5 10v8M9 10v8M15 10v8M19 10v8M3 19h18M3 22h18",
  card:    "M3 7a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2zM3 11h18M7 16h3",
  building:"M5 21V5a1 1 0 0 1 1-1h7a1 1 0 0 1 1 1v16M14 9h4a1 1 0 0 1 1 1v11M3 21h18M9 8h.01M9 12h.01M9 16h.01M17 13h.01M17 17h.01",
  coins:   "M9 5a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM15 11a4 4 0 1 1 0 8 4 4 0 0 1 0-8M9 13v2a4 4 0 0 0 4 4",
  plusMin: "M12 6v12M6 12h12",
};

function Icon({ name, size = 22, color = "currentColor", stroke = 1.7, style = {} }) {
  const d = ICONS[name];
  if (!d) return null;
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none"
         stroke={color} strokeWidth={stroke}
         strokeLinecap="round" strokeLinejoin="round"
         style={style} aria-hidden="true">
      <path d={d} />
    </svg>
  );
}

// ─────────────────────────────────────────────────────────
// CATEGORÍAS · data + helpers
// ─────────────────────────────────────────────────────────
const CATS = {
  salary:      { label: 'Salario',       icon: 'briefcase', tone: 'var(--cat-salary)' },
  food:        { label: 'Alimentación',  icon: 'cart',      tone: 'var(--cat-food)' },
  restaurants: { label: 'Restaurantes',  icon: 'utensils',  tone: 'var(--cat-rest)' },
  subs:        { label: 'Suscripciones', icon: 'music',     tone: 'var(--cat-subs)' },
  transport:   { label: 'Transporte',    icon: 'car',       tone: 'var(--cat-trans)' },
  home:        { label: 'Hogar',         icon: 'house',     tone: 'var(--cat-home)' },
  fun:         { label: 'Ocio',          icon: 'spark',     tone: 'var(--cat-fun)' },
  other:       { label: 'Otros',         icon: 'tag',       tone: 'var(--cat-other)' },
};

// ─────────────────────────────────────────────────────────
// MONEY · formatos locales
// ─────────────────────────────────────────────────────────
const LOCALES = {
  EUR: { locale: 'es-ES', symbol: '€', code: 'EUR' },
  USD: { locale: 'en-US', symbol: '$', code: 'USD' },
  JPY: { locale: 'ja-JP', symbol: '¥', code: 'JPY' },
  GBP: { locale: 'en-GB', symbol: '£', code: 'GBP' },
};
// Tasa de cambio offline (estática, para conversión a EUR base)
const FX_TO_EUR = { EUR: 1, USD: 0.92, JPY: 0.0061, GBP: 1.17 };

function formatMoney(amount, currency = 'EUR', opts = {}) {
  const cfg = LOCALES[currency] || LOCALES.EUR;
  const decimals = currency === 'JPY' ? 0 : 2;
  const sign = opts.signed ? (amount > 0 ? '+' : amount < 0 ? '−' : '') : '';
  const abs = Math.abs(amount);
  const num = abs.toLocaleString(cfg.locale, {
    minimumFractionDigits: decimals, maximumFractionDigits: decimals,
    useGrouping: 'always',
  });
  return `${sign}${num} ${cfg.symbol}`;
}
function toEUR(amount, currency) { return amount * (FX_TO_EUR[currency] || 1); }

// ─────────────────────────────────────────────────────────
// FECHAS · helpers en español
// ─────────────────────────────────────────────────────────
const MESES = ['enero','febrero','marzo','abril','mayo','junio','julio','agosto','septiembre','octubre','noviembre','diciembre'];
const MESES_CORTO = ['ene','feb','mar','abr','may','jun','jul','ago','sep','oct','nov','dic'];
const DIAS = ['domingo','lunes','martes','miércoles','jueves','viernes','sábado'];

function parseISO(s) {
  const [y, m, d] = s.split('-').map(Number);
  return new Date(y, m - 1, d);
}
// "Hoy", "Ayer", "Mar · 19 may" — relativo a la fecha de referencia (21 may 2026)
const TODAY = parseISO('2026-05-21');
function relDate(iso) {
  const d = parseISO(iso);
  const diff = Math.round((TODAY - d) / 86400000);
  if (diff === 0) return 'Hoy';
  if (diff === 1) return 'Ayer';
  if (diff < 7)   return `${DIAS[d.getDay()].charAt(0).toUpperCase()}${DIAS[d.getDay()].slice(1,3)} · ${d.getDate()} ${MESES_CORTO[d.getMonth()]}`;
  return `${d.getDate()} ${MESES_CORTO[d.getMonth()]}`;
}
function dateGroup(iso) {
  const d = parseISO(iso);
  const diff = Math.round((TODAY - d) / 86400000);
  if (diff === 0) return 'Hoy';
  if (diff === 1) return 'Ayer';
  if (diff < 7)   return 'Esta semana';
  if (diff < 14)  return 'Semana pasada';
  return `${MESES[d.getMonth()].charAt(0).toUpperCase()}${MESES[d.getMonth()].slice(1)} ${d.getFullYear()}`;
}

// ─────────────────────────────────────────────────────────
// SAMPLE DATA · movimientos de mayo 2026
// ─────────────────────────────────────────────────────────
const SAMPLE_TXS = [
  { id: 1,  type: 'income',  title: 'Nómina · Mayo',        cat: 'salary',      amount: 1840.00, currency: 'EUR', date: '2026-05-21', note: '' },
  { id: 2,  type: 'expense', title: 'Carrefour',            cat: 'food',        amount: 42.50,   currency: 'EUR', date: '2026-05-20', note: 'Compra semanal' },
  { id: 3,  type: 'expense', title: 'Spotify Family',       cat: 'subs',        amount: 16.99,   currency: 'USD', date: '2026-05-19', note: '' },
  { id: 4,  type: 'expense', title: 'Café Federal',         cat: 'restaurants', amount: 4.20,    currency: 'EUR', date: '2026-05-18', note: '' },
  { id: 5,  type: 'expense', title: 'Gasolina BP',          cat: 'transport',   amount: 58.30,   currency: 'EUR', date: '2026-05-17', note: '' },
  { id: 6,  type: 'expense', title: 'La Llave',             cat: 'restaurants', amount: 38.50,   currency: 'EUR', date: '2026-05-15', note: 'Cena con amigos' },
  { id: 7,  type: 'expense', title: 'Mercadona',            cat: 'food',        amount: 67.40,   currency: 'EUR', date: '2026-05-14', note: '' },
  { id: 8,  type: 'expense', title: 'Restaurantes varios',  cat: 'restaurants', amount: 56.80,   currency: 'EUR', date: '2026-05-13', note: '' },
  { id: 9,  type: 'income',  title: 'Devolución Hacienda',  cat: 'salary',      amount: 156.30,  currency: 'EUR', date: '2026-05-12', note: '' },
  { id: 10, type: 'expense', title: 'Netflix',              cat: 'subs',        amount: 12.99,   currency: 'EUR', date: '2026-05-10', note: '' },
  { id: 11, type: 'expense', title: 'IKEA',                 cat: 'home',        amount: 124.00,  currency: 'EUR', date: '2026-05-08', note: 'Lámpara salón' },
  { id: 12, type: 'expense', title: 'Bar Vermut',           cat: 'restaurants', amount: 18.50,   currency: 'EUR', date: '2026-05-05', note: '' },
  { id: 13, type: 'expense', title: 'Mercadona',            cat: 'food',        amount: 51.20,   currency: 'EUR', date: '2026-05-04', note: '' },
  { id: 14, type: 'expense', title: 'Metro Madrid',         cat: 'transport',   amount: 22.00,   currency: 'EUR', date: '2026-05-02', note: 'Abono mensual' },
  { id: 15, type: 'expense', title: 'Cine Yelmo',           cat: 'fun',         amount: 19.00,   currency: 'EUR', date: '2026-05-02', note: '' },
];

// Tendencia 6 meses (gasto total mensual)
const TREND_6M = [
  { m: 'Dic',  v: 1280 },
  { m: 'Ene',  v: 1450 },
  { m: 'Feb',  v: 1620 },
  { m: 'Mar',  v: 1380 },
  { m: 'Abr',  v: 1540 },
  { m: 'May',  v: 547 }, // mes en curso, parcial
];

// Presupuestos mensuales (en EUR) — mezcla intencional de estados
// Resultado: food 40% (ok) · restaurantes 118% (excedido) · transporte 80% (warn)
// hogar 62% (ok) · suscripciones 95% (warn) · ocio 19% (ok)
const SAMPLE_BUDGETS = [
  { cat: 'food',        limit: 400 },
  { cat: 'restaurants', limit: 100 },
  { cat: 'transport',   limit: 100 },
  { cat: 'home',        limit: 200 },
  { cat: 'subs',        limit: 30 },
  { cat: 'fun',         limit: 100 },
];

// ─────────────────────────────────────────────────────────
// PATRIMONIO · activos y pasivos (datos de ejemplo)
// ─────────────────────────────────────────────────────────
// Grupos de activos · cada uno tiene label + icon + tono visual
const ASSET_GROUPS = {
  bank:    { label: 'Cuentas bancarias', icon: 'bank',     tone: 'var(--cat-trans)' },
  invest:  { label: 'Inversión',         icon: 'trend',    tone: 'var(--cat-salary)' },
  realestate:{ label: 'Inmuebles',       icon: 'building', tone: 'var(--cat-home)' },
  crypto:  { label: 'Cripto',            icon: 'coins',    tone: 'var(--cat-fun)' },
  cash:    { label: 'Efectivo',          icon: 'wallet',   tone: 'var(--cat-other)' },
};
const LIAB_GROUPS = {
  mortgage:{ label: 'Hipotecas',         icon: 'house',    tone: 'var(--cat-rest)' },
  loan:    { label: 'Préstamos',         icon: 'briefcase',tone: 'var(--cat-subs)' },
  card:    { label: 'Tarjetas',          icon: 'card',     tone: 'var(--cat-food)' },
};

const SAMPLE_ASSETS = [
  // Cuentas bancarias
  { id: 'a1', group: 'bank',       name: 'BBVA · Cuenta corriente', subtitle: '••• 4821',          amount: 4250.30,   currency: 'EUR' },
  { id: 'a2', group: 'bank',       name: 'N26 · Ahorro',            subtitle: 'Cuenta de ahorro',  amount: 8400.00,   currency: 'EUR' },
  { id: 'a3', group: 'bank',       name: 'Wise · Multidivisa',      subtitle: 'Balance USD',       amount: 1280.00,   currency: 'USD' },
  // Inversión
  { id: 'a4', group: 'invest',     name: 'MyInvestor · Indexada',   subtitle: 'Roboadvisor · 60/40', amount: 15820.00,currency: 'EUR' },
  { id: 'a5', group: 'invest',     name: 'IBKR · Acciones',         subtitle: 'Bróker · 12 valores', amount: 9320.00, currency: 'USD' },
  { id: 'a6', group: 'invest',     name: 'Plan de pensiones',       subtitle: 'BBVA · RV 80%',     amount: 6240.00,   currency: 'EUR' },
  // Inmuebles
  { id: 'a7', group: 'realestate', name: 'Piso · Madrid',           subtitle: 'Vivienda habitual', amount: 245000.00, currency: 'EUR' },
  { id: 'a8', group: 'realestate', name: 'Plaza de garaje',         subtitle: 'Adyacente',         amount: 22000.00,  currency: 'EUR' },
  // Cripto
  { id: 'a9', group: 'crypto',     name: 'Bitcoin',                 subtitle: '0,084 BTC',         amount: 4280.00,   currency: 'EUR' },
  // Efectivo
  { id: 'a10',group: 'cash',       name: 'Efectivo',                subtitle: 'En casa',           amount: 320.00,    currency: 'EUR' },
];

const SAMPLE_LIABS = [
  { id: 'l1', group: 'mortgage', name: 'Hipoteca · Piso Madrid',  subtitle: 'BBVA · 18 años restantes', amount: 168400.00, currency: 'EUR' },
  { id: 'l2', group: 'loan',     name: 'Préstamo coche',          subtitle: 'Santander · 2 años',       amount: 6320.00,   currency: 'EUR' },
  { id: 'l3', group: 'card',     name: 'Tarjeta American Express',subtitle: 'Saldo pendiente',          amount: 480.00,    currency: 'EUR' },
];

// Snapshots históricos del patrimonio neto (6 meses) — para sparkline
const NETWORTH_6M = [
  { m: 'Dic', v: 281200 },
  { m: 'Ene', v: 286400 },
  { m: 'Feb', v: 290100 },
  { m: 'Mar', v: 293800 },
  { m: 'Abr', v: 298500 },
  { m: 'May', v: 302530 },
];

function totalAssets(assets) {
  return assets.reduce((s, a) => s + toEUR(a.amount, a.currency), 0);
}
function totalLiabs(liabs) {
  return liabs.reduce((s, l) => s + toEUR(l.amount, l.currency), 0);
}
function assetsByGroup(assets) {
  const map = {};
  assets.forEach(a => {
    if (!map[a.group]) map[a.group] = { group: a.group, items: [], total: 0 };
    map[a.group].items.push(a);
    map[a.group].total += toEUR(a.amount, a.currency);
  });
  return Object.values(map).sort((a, b) => b.total - a.total);
}
function liabsByGroup(liabs) {
  const map = {};
  liabs.forEach(l => {
    if (!map[l.group]) map[l.group] = { group: l.group, items: [], total: 0 };
    map[l.group].items.push(l);
    map[l.group].total += toEUR(l.amount, l.currency);
  });
  return Object.values(map).sort((a, b) => b.total - a.total);
}

// Cálculos derivados
function spentByCat(txs, cat) {
  return txs
    .filter(t => t.type === 'expense' && t.cat === cat)
    .reduce((sum, t) => sum + toEUR(t.amount, t.currency), 0);
}
function totalSpent(txs) {
  return txs.filter(t => t.type === 'expense')
    .reduce((s, t) => s + toEUR(t.amount, t.currency), 0);
}
function totalIncome(txs) {
  return txs.filter(t => t.type === 'income')
    .reduce((s, t) => s + toEUR(t.amount, t.currency), 0);
}

// ─────────────────────────────────────────────────────────
// PRIMITIVAS UI compartidas
// ─────────────────────────────────────────────────────────

// Icono de categoría con su tono (chip cuadrado redondeado)
function CatIcon({ cat, size = 40 }) {
  const c = CATS[cat] || CATS.other;
  return (
    <div style={{
      width: size, height: size,
      borderRadius: size <= 32 ? 9 : 11,
      background: c.tone,
      display: 'grid', placeItems: 'center',
      color: 'var(--surface-2)',
      flexShrink: 0,
    }}>
      <Icon name={c.icon} size={Math.round(size * 0.52)} stroke={1.9} />
    </div>
  );
}

// Importe con signo + color semántico + soporte multi-divisa
function Amount({ type, amount, currency = 'EUR', size = 15, weight = 600, showCurrencyTag = true }) {
  const sign = type === 'income' ? '+' : '−';
  const color = type === 'income' ? 'var(--income)' : 'var(--ink)';
  const cfg = LOCALES[currency] || LOCALES.EUR;
  const decimals = currency === 'JPY' ? 0 : 2;
  const abs = Math.abs(amount);
  const num = abs.toLocaleString(cfg.locale, {
    minimumFractionDigits: decimals, maximumFractionDigits: decimals,
    useGrouping: 'always',
  });
  return (
    <span style={{
      fontVariantNumeric: 'tabular-nums',
      fontFeatureSettings: '"tnum"',
      fontWeight: weight, fontSize: size,
      color, letterSpacing: '-0.005em',
      display: 'inline-flex', alignItems: 'baseline', gap: 6,
      whiteSpace: 'nowrap',
    }}>
      <span>{sign}{num}&nbsp;{cfg.symbol}</span>
      {showCurrencyTag && currency !== 'EUR' && (
        <span style={{
          fontSize: 10, fontWeight: 600, letterSpacing: '0.05em',
          color: 'var(--muted)',
          background: 'var(--bg-2)', border: '1px solid var(--line)',
          padding: '1px 5px', borderRadius: 999,
        }}>{currency}</span>
      )}
    </span>
  );
}

// Píldora cápsula reutilizable
function Pill({ children, tone = 'neutral', size = 'sm' }) {
  const styles = {
    neutral: { bg: 'var(--bg-2)',     fg: 'var(--ink-2)', bd: 'var(--line)' },
    income:  { bg: 'var(--income-soft)', fg: 'var(--income)',  bd: 'transparent' },
    expense: { bg: 'var(--expense-soft)',fg: 'var(--expense)', bd: 'transparent' },
    alert:   { bg: 'var(--alert-soft)',  fg: 'var(--alert)',   bd: 'transparent' },
    brand:   { bg: 'var(--brand-soft)',  fg: 'var(--brand)',   bd: 'transparent' },
  };
  const s = styles[tone];
  const isSm = size === 'sm';
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 4,
      background: s.bg, color: s.fg, border: `1px solid ${s.bd}`,
      padding: isSm ? '2px 8px' : '4px 10px',
      borderRadius: 999,
      fontSize: isSm ? 11 : 12,
      fontWeight: 600,
      lineHeight: 1.2,
      letterSpacing: '0.01em',
      whiteSpace: 'nowrap',
    }}>{children}</span>
  );
}

// Barra de progreso para presupuestos
function BudgetBar({ pct, state = 'ok' }) {
  const bg = {
    ok:   'var(--income)',
    warn: 'var(--alert)',
    over: 'var(--expense)',
  }[state];
  const w = Math.min(100, Math.max(0, pct));
  return (
    <div style={{
      position: 'relative', height: 8,
      background: 'var(--bg-2)',
      borderRadius: 999,
      border: '1px solid var(--line)',
      overflow: 'hidden',
    }}>
      <div style={{
        position: 'absolute', inset: 0, right: 'auto', width: `${w}%`,
        background: bg, borderRadius: 999,
        transition: 'width 380ms cubic-bezier(.4,.6,.2,1)',
      }} />
    </div>
  );
}

// ─────────────────────────────────────────────────────────
// TAB BAR · barra de navegación inferior
// ─────────────────────────────────────────────────────────
const TABS = [
  { id: 'home',       label: 'Inicio',      icon: 'home' },
  { id: 'movs',       label: 'Movim.',      icon: 'list' },
  { id: 'budgets',    label: 'Presup.',     icon: 'pie' },
  { id: 'stats',      label: 'Analítica',   icon: 'chart' },
  { id: 'patrimonio', label: 'Patrimonio',  icon: 'wallet' },
];

function TabBar({ tab, setTab }) {
  return (
    <nav style={{
      position: 'absolute', left: 0, right: 0, bottom: 0,
      paddingTop: 8, paddingBottom: 36,
      background: 'var(--surface)',
      borderTop: '1px solid var(--line)',
      display: 'grid',
      gridTemplateColumns: `repeat(${TABS.length}, 1fr)`,
      zIndex: 30,
      backdropFilter: 'blur(20px)',
      WebkitBackdropFilter: 'blur(20px)',
    }}>
      {TABS.map(t => {
        const active = tab === t.id;
        return (
          <button key={t.id}
            onClick={() => setTab(t.id)}
            aria-current={active ? 'page' : undefined}
            style={{
              appearance: 'none', border: 0, background: 'transparent',
              padding: '8px 4px 4px',
              display: 'flex', flexDirection: 'column', alignItems: 'center',
              gap: 3,
              color: active ? 'var(--ink)' : 'var(--muted)',
              cursor: 'pointer',
              fontFamily: 'var(--font-sans)',
              fontSize: 10.5,
              fontWeight: active ? 600 : 500,
              letterSpacing: '0.01em',
              transition: 'color 160ms ease',
              minHeight: 44,
            }}>
            <Icon name={t.icon} size={22} stroke={active ? 2 : 1.7} />
            <span>{t.label}</span>
          </button>
        );
      })}
    </nav>
  );
}

// ─────────────────────────────────────────────────────────
// FAB · botón flotante para añadir movimiento
// ─────────────────────────────────────────────────────────
function FAB({ onClick }) {
  return (
    <button
      onClick={onClick}
      aria-label="Añadir movimiento"
      style={{
        position: 'absolute', right: 20, bottom: 100,
        width: 56, height: 56, borderRadius: '50%',
        background: 'var(--ink)', color: 'var(--bg)',
        border: 0, cursor: 'pointer',
        display: 'grid', placeItems: 'center',
        boxShadow: '0 12px 28px -10px rgba(0,0,0,0.45), 0 4px 10px rgba(0,0,0,0.15)',
        zIndex: 25,
        transition: 'transform 140ms ease, background 200ms ease',
      }}
      onMouseDown={(e) => e.currentTarget.style.transform = 'scale(0.94)'}
      onMouseUp={(e) => e.currentTarget.style.transform = 'scale(1)'}
      onMouseLeave={(e) => e.currentTarget.style.transform = 'scale(1)'}
    >
      <Icon name="plus" size={26} stroke={2.2} />
    </button>
  );
}

// ─────────────────────────────────────────────────────────
// Cabecera de pantalla (compacta, sobre el status bar)
// ─────────────────────────────────────────────────────────
function ScreenHeader({ title, eyebrow, right, large = true, sticky = false }) {
  return (
    <header style={{
      padding: large ? '8px 20px 14px' : '6px 20px 10px',
      position: sticky ? 'sticky' : 'static',
      top: 0, zIndex: 5,
      background: 'var(--bg)',
    }}>
      {eyebrow && (
        <div style={{
          fontSize: 12, color: 'var(--muted)', fontWeight: 500,
          marginBottom: 2, letterSpacing: '0.01em',
        }}>{eyebrow}</div>
      )}
      <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', gap: 12 }}>
        <h1 style={{
          margin: 0,
          fontFamily: 'var(--font-sans)',
          fontSize: large ? 30 : 22,
          fontWeight: 600,
          letterSpacing: '-0.02em',
          lineHeight: 1.1,
          color: 'var(--ink)',
        }}>{title}</h1>
        {right}
      </div>
    </header>
  );
}

// Botón circular discreto para acciones de cabecera
function HeaderIconBtn({ icon, onClick, label }) {
  return (
    <button onClick={onClick} aria-label={label} style={{
      width: 38, height: 38, borderRadius: '50%',
      background: 'var(--surface)',
      border: '1px solid var(--line)',
      color: 'var(--ink-2)',
      display: 'grid', placeItems: 'center',
      cursor: 'pointer',
      transition: 'background 160ms ease',
    }}>
      <Icon name={icon} size={19} />
    </button>
  );
}

// ─────────────────────────────────────────────────────────
// Exportar al global (cada <script type="text/babel"> es un scope aparte)
// ─────────────────────────────────────────────────────────
Object.assign(window, {
  ICONS, Icon, CATS, LOCALES, FX_TO_EUR,
  formatMoney, toEUR, parseISO, relDate, dateGroup,
  MESES, MESES_CORTO, DIAS, TODAY,
  SAMPLE_TXS, TREND_6M, SAMPLE_BUDGETS,
  SAMPLE_ASSETS, SAMPLE_LIABS, ASSET_GROUPS, LIAB_GROUPS, NETWORTH_6M,
  totalAssets, totalLiabs, assetsByGroup, liabsByGroup,
  spentByCat, totalSpent, totalIncome,
  CatIcon, Amount, Pill, BudgetBar,
  TABS, TabBar, FAB, ScreenHeader, HeaderIconBtn,
});
