/** @type {import('tailwindcss').Config} */
// Godance design system — "Unified Ledger". Mirrors frontend-marketing's
// config so brand presentation stays consistent across godance.ai and
// app.godance.ai. Semantic color tokens resolve to CSS variables
// (defined in src/index.css) so light/dark flips without `dark:` variants.
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  darkMode: ['selector', '[data-theme="dark"]'],
  theme: {
    extend: {
      colors: {
        // Brand — violet primary (Unified Ledger routing path). Static
        // across themes; the dark variant is handled by the semantic vars.
        brand: {
          50:  '#f5f3ff',
          100: '#ede9fe',
          300: '#c4b5fd',
          500: '#8b5cf6',
          600: '#7c3aed',
          700: '#6d28d9',
          900: '#4c1d95',
        },
        // Cyan accent — connection node / endpoint pill. Never a primary button.
        accent: {
          DEFAULT: '#06b6d4',
          600:     '#0891b2',
        },
        // Semantic tokens — resolve to CSS vars, auto-flip in dark mode.
        bg:               'var(--color-bg)',
        'bg-muted':       'var(--color-bg-muted)',
        surface:          'var(--color-surface)',
        'surface-alt':    'var(--color-surface-alt)',
        fg:               'var(--color-fg)',
        'fg-muted':       'var(--color-fg-muted)',
        'fg-subtle':      'var(--color-fg-subtle)',
        'fg-faint':       'var(--color-fg-faint)',
        border:           'var(--color-border)',
        'border-strong':  'var(--color-border-strong)',
        'code-bg':        'var(--color-code-bg)',
        'code-fg':        'var(--color-code-fg)',
        // Semantic status foregrounds — flip in dark mode.
        success:          'var(--color-success-fg)',
        warning:          'var(--color-warning-fg)',
        danger:           'var(--color-danger-fg)',
      },
      fontFamily: {
        sans: ['Inter', 'ui-sans-serif', 'system-ui', 'sans-serif'],
        mono: ['JetBrains Mono', 'ui-monospace', 'monospace'],
      },
      // Tailwind 3's sizes, whose line heights are fixed rem values that
      // children inherit as-is. Tailwind 4's defaults are unitless ratios,
      // which shrink the line height of smaller text inside (pills, code
      // chips).
      fontSize: {
        xs:    ['0.75rem',  { lineHeight: '1rem' }],
        sm:    ['0.875rem', { lineHeight: '1.25rem' }],
        base:  ['1rem',     { lineHeight: '1.5rem' }],
        lg:    ['1.125rem', { lineHeight: '1.75rem' }],
        xl:    ['1.25rem',  { lineHeight: '1.75rem' }],
        '2xl': ['1.5rem',   { lineHeight: '2rem' }],
        '3xl': ['1.875rem', { lineHeight: '2.25rem' }],
        '4xl': ['2.25rem',  { lineHeight: '2.5rem' }],
        '5xl': ['3rem',     { lineHeight: '1' }],
        '6xl': ['3.75rem',  { lineHeight: '1' }],
      },
      borderRadius: {
        sm: '6px',
        md: '8px',
        lg: '12px',
        xl: '16px',
      },
      boxShadow: {
        xs: '0 1px 0 rgba(17, 24, 39, 0.04)',
        sm: '0 1px 2px rgba(17, 24, 39, 0.06)',
        md: '0 4px 8px -2px rgba(17, 24, 39, 0.08), 0 2px 4px -2px rgba(17, 24, 39, 0.04)',
        lg: '0 12px 24px -6px rgba(17, 24, 39, 0.10), 0 4px 8px -4px rgba(17, 24, 39, 0.06)',
      },
    },
  },
  plugins: [],
};
