import react from "@vitejs/plugin-react";
import type { ProxyOptions } from "vite";
import { defineConfig } from "vitest/config";
// Server-only switch; neither the target nor environment is injected into browser code.
const target =
  process.env.EAAP_WEB_CONTAINER === "1"
    ? "http://control-plane:8080"
    : "http://127.0.0.1:8080";
const proxy: Record<string, ProxyOptions> = {
  "^/api/v1/": {
    target,
    changeOrigin: false,
    ws: false,
    proxyTimeout: 120_000,
    timeout: 125_000,
    configure(proxy) {
      proxy.on("error", (_error, _request, response) => {
        if ("writeHead" in response && !response.headersSent) {
          response.writeHead(502, {
            "Content-Type": "application/json",
            "Cache-Control": "no-store",
          });
          response.end('{"code":"upstream_unavailable"}');
        }
      });
      proxy.on("proxyRes", (response) => {
        response.headers["cache-control"] = "no-store";
        delete response.headers["set-cookie"];
      });
    },
  },
};
const headers = {
  "Cache-Control": "no-store",
  "X-Content-Type-Options": "nosniff",
  "Referrer-Policy": "no-referrer",
  "Content-Security-Policy":
    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'",
};
export default defineConfig({
  plugins: [react()],
  server: { host: "127.0.0.1", strictPort: true, cors: false, proxy },
  preview: { strictPort: true, cors: false, proxy, headers },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test-setup.ts"],
    restoreMocks: true,
  },
});
