/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_CONTROL_PLANE_API_URL?: string
  readonly VITE_AGENT_RUNTIME_API_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
