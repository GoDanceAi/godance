/**
 * Godance brand primitives — "Unified Ledger" mark, wordmark, lockup,
 * theme toggle, and the 24px outline icon set. Ported from the design
 * system UI kit. Strokes inherit currentColor so the mark auto-flips
 * with the active theme.
 */
import { useTheme } from '../lib/theme';

/** Unified Ledger symbol — open arc, three ledger strokes, routed path,
 *  cyan endpoint ring. */
export function LogoMark({ size = 32 }: { size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 512 512"
      role="img"
      aria-label="Godance"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      style={{ color: 'var(--color-fg)' }}
    >
      <path d="M341 149A150 150 0 1 0 341 363" stroke="currentColor" strokeWidth="31" strokeLinecap="round" />
      <path d="M154 208H231" stroke="currentColor" strokeWidth="23" strokeLinecap="round" />
      <path d="M154 256H245" stroke="currentColor" strokeWidth="23" strokeLinecap="round" />
      <path d="M154 304H226" stroke="currentColor" strokeWidth="23" strokeLinecap="round" />
      <path d="M245 256H294C315 256 315 220 337 220H394" stroke="currentColor" strokeWidth="25" strokeLinecap="round" />
      <circle cx="420" cy="220" r="32" fill="var(--color-bg)" stroke="#06B6D4" strokeWidth="18" />
    </svg>
  );
}

/** "Godance" wordmark — Inter 700, capital G, tight tracking. */
export function Wordmark({ size = 20 }: { size?: number }) {
  return (
    <span
      style={{
        font: `700 ${size}px/1 var(--font-sans, Inter, sans-serif)`,
        letterSpacing: '-0.025em',
        color: 'var(--color-fg)',
      }}
    >
      Godance
    </span>
  );
}

/** Inline lockup — mark + wordmark at matched scale. */
export function LogoLockup({ size = 20 }: { size?: number }) {
  return (
    <span className="inline-flex items-center gap-2">
      <LogoMark size={size + 10} />
      <Wordmark size={size} />
    </span>
  );
}

/** Sun/moon theme toggle — bordered icon button. */
export function ThemeToggle({ compact = false }: { compact?: boolean }) {
  const { theme, toggle } = useTheme();
  const isDark = theme === 'dark';
  const px = compact ? 30 : 34;
  return (
    <button
      type="button"
      onClick={toggle}
      aria-label={`Switch to ${isDark ? 'light' : 'dark'} mode`}
      title={`Switch to ${isDark ? 'light' : 'dark'} mode`}
      className="inline-flex items-center justify-center rounded-md border border-border
                 text-fg-muted transition-colors hover:border-border-strong hover:text-fg"
      style={{ width: px, height: px, background: 'transparent' }}
    >
      {isDark ? (
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round">
          <circle cx="12" cy="12" r="4" />
          <path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41" />
        </svg>
      ) : (
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round">
          <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
        </svg>
      )}
    </button>
  );
}

/** 24px outline icon set — matched to godance-icon-system.svg geometry. */
export type IconName =
  | 'dashboard' | 'chat' | 'key' | 'billing' | 'settings'
  | 'copy' | 'chevronRight' | 'arrowRight' | 'plus' | 'book'
  | 'eye' | 'eyeOff';

export function Icon({ name, size = 18 }: { name: IconName; size?: number }) {
  const s = {
    width: size,
    height: size,
    viewBox: '0 0 24 24',
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth: 1.75,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
  };
  switch (name) {
    case 'dashboard':
      return <svg {...s}><rect x="3" y="3" width="7" height="9" rx="1.5" /><rect x="14" y="3" width="7" height="5" rx="1.5" /><rect x="14" y="12" width="7" height="9" rx="1.5" /><rect x="3" y="16" width="7" height="5" rx="1.5" /></svg>;
    case 'chat':
      return <svg {...s}><path d="M21 12c0 4.4-4 8-9 8-1.3 0-2.5-.2-3.6-.6L3 21l1.6-4.5C3.6 15.2 3 13.7 3 12c0-4.4 4-8 9-8s9 3.6 9 8z" /></svg>;
    case 'key':
      return <svg {...s}><circle cx="8" cy="12" r="4" /><path d="M12 12h9M18 12v3M21 12v3" /></svg>;
    case 'billing':
      return <svg {...s}><circle cx="12" cy="12" r="9" /><path d="M8 12h8M12 8v8" /></svg>;
    case 'settings':
      return <svg {...s}><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1A2 2 0 1 1 4.4 17l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1 1.7 1.7 0 0 0-.3-1.8l-.1-.1A2 2 0 1 1 7 4.4l.1.1a1.7 1.7 0 0 0 1.8.3h0a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1A2 2 0 1 1 19.6 7l-.1.1a1.7 1.7 0 0 0-.3 1.8v0a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z" /></svg>;
    case 'copy':
      return <svg {...s}><rect x="9" y="9" width="11" height="11" rx="2" /><path d="M5 15V5a2 2 0 0 1 2-2h10" /></svg>;
    case 'chevronRight':
      return <svg {...s}><path d="M9 6l6 6-6 6" /></svg>;
    case 'arrowRight':
      return <svg {...s}><path d="M5 12h14M13 6l6 6-6 6" /></svg>;
    case 'plus':
      return <svg {...s}><path d="M12 5v14M5 12h14" /></svg>;
    case 'book':
      return <svg {...s}><path d="M4 5a2 2 0 0 1 2-2h13v18H6a2 2 0 0 1-2-2zM4 18h15" /></svg>;
    case 'eye':
      return <svg {...s}><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z" /><circle cx="12" cy="12" r="3" /></svg>;
    case 'eyeOff':
      return <svg {...s}><path d="M3 3l18 18M10.6 6.1A10 10 0 0 1 12 6c6.5 0 10 6 10 6a16.6 16.6 0 0 1-3.1 3.8M6.1 6.1A16 16 0 0 0 2 12s3.5 6 10 6c1.4 0 2.7-.3 3.9-.7" /></svg>;
    default:
      return null;
  }
}
