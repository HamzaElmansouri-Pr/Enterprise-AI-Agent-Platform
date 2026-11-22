import { ApiClient } from "../api/client";
// A session owns all request scopes. There is deliberately no browser persistence.
export class MemorySession {
  #token: string | null;
  #clients = new Set<ApiClient>();
  readonly expiresAt: number;
  constructor(token: string, seconds: number) {
    this.#token = token;
    this.expiresAt = Date.now() + Math.min(seconds, 900) * 1000;
  }
  scope(onExpired: () => void) {
    if (!this.#token) throw new Error("Session closed");
    const client = new ApiClient(this.#token, onExpired);
    this.#clients.add(client);
    return client;
  }
  clearScopes() {
    this.#clients.forEach((c) => c.dispose());
    this.#clients.clear();
  }
  dispose() {
    this.clearScopes();
    this.#token = null;
  }
}
