// Vite injects these public values at build time. Never put secrets in VITE_*.
// Reserved for the approved API connectivity task; the current screen makes no requests.
export const apiConfiguration = Object.freeze({
  controlPlaneUrl: import.meta.env.VITE_CONTROL_PLANE_API_URL ?? '',
  agentRuntimeUrl: import.meta.env.VITE_AGENT_RUNTIME_API_URL ?? '',
})
