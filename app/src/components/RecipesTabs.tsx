import { NavLink } from 'react-router-dom';

const TABS = [
  { to: '/recipes', label: 'Recipes' },
  { to: '/batches', label: 'Batches' },
];

/**
 * "Recipes | Batches": the two lists under Recipes in the main navigation.
 * Batches have no entry of their own up there, so the navigation stays one
 * row on a phone.
 */
export default function RecipesTabs() {
  return (
    <nav aria-label="Recipes and batches" className="inline-flex rounded-md border border-border bg-surface p-0.5">
      {TABS.map((tab) => (
        <NavLink
          key={tab.to}
          to={tab.to}
          end
          className={({ isActive }) =>
            `rounded-sm px-4 py-1.5 text-[13px] font-semibold transition-colors ${
              isActive ? 'bg-brand-600 text-white' : 'text-fg-muted hover:text-fg'}`}
        >
          {tab.label}
        </NavLink>
      ))}
    </nav>
  );
}
