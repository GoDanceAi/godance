/// <reference types="vite/client" />

/** The short commit the bundle was built from, or "dev". Set in vite.config.ts. */
declare const __GODANCE_BUILD__: string;

interface ImportMetaEnv {
  readonly VITE_API_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
