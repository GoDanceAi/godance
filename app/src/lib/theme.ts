import { useEffect, useState } from 'react';

/**
 * Light/dark theme hook. The single source of truth is the
 * `data-theme` attribute on <html> — CSS variables in index.css key
 * off it. A no-flash inline script in index.html sets it before first
 * paint; this hook keeps it in sync and persists the choice.
 */
const THEME_KEY = 'gd-theme';

export type Theme = 'light' | 'dark';

function readTheme(): Theme {
  return document.documentElement.getAttribute('data-theme') === 'dark'
    ? 'dark'
    : 'light';
}

export function useTheme() {
  const [theme, setTheme] = useState<Theme>(readTheme);

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
    try {
      localStorage.setItem(THEME_KEY, theme);
    } catch {
      /* private mode / storage disabled — attribute still applies */
    }
  }, [theme]);

  return {
    theme,
    toggle: () => setTheme((t) => (t === 'dark' ? 'light' : 'dark')),
  };
}
