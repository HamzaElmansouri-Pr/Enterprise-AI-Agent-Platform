import type { Decoder } from "./contracts";
import { isUuid } from "./contracts";
const messages: Record<number, string> = {
  400: "Check the fields and their limits.",
  401: "Your session expired. Please sign in again.",
  403: "Your current account does not have permission for this operation. Membership may have changed; switch tenant and refresh organizations, or ask the owner.",
  404: "This record is unavailable or inaccessible.",
  409: "The record changed or this transition is unavailable. Refresh and review it again. Archived agents and closed conversations cannot accept new work; rejected proposals cannot execute.",
  410: "This invitation is unavailable, expired, cancelled or already used. Check its details with the inviter.",
  413: "The file or request is too large. Documents must be at most 2 MiB.",
  415: "This file or request type is unsupported.",
  422: "Not enough usable knowledge is available for this question.",
  429: "A request limit or quota has been reached. Wait before a deliberate retry; daily execution budgets reset at UTC midnight. No automatic retry occurs.",
  502: "The runtime returned an unusable result.",
  503: "The service or model is unavailable. Check configuration.",
  504: "The request timed out. A provider call may have occurred. Check the outcome before retrying.",
};
export class ApiError extends Error {
  readonly status: number;
  readonly requestId: string | null;
  readonly runId: string | null;
  constructor(
    status: number,
    requestId: string | null = null,
    runId: string | null = null,
  ) {
    super(
      messages[status] ??
        (status === 0
          ? "Could not reach the service. Check the outcome before retrying."
          : "The service could not complete this request."),
    );
    this.name = "ApiError";
    this.status = status;
    this.requestId = requestId;
    this.runId = runId;
  }
}
export function wasAborted(error: unknown) {
  return error instanceof DOMException && error.name === "AbortError";
}
export type RequestOptions = {
  method?: "GET" | "POST" | "PUT" | "DELETE";
  json?: unknown;
  form?: FormData;
  signal?: AbortSignal;
};
export class ApiClient {
  #token: string | null;
  #closed = false;
  #controllers = new Set<AbortController>();
  #unauthorized: () => void;
  constructor(token: string | null, unauthorized: () => void = () => {}) {
    this.#token = token;
    this.#unauthorized = unauthorized;
  }
  dispose() {
    this.#closed = true;
    this.#token = null;
    this.#controllers.forEach((c) => c.abort());
    this.#controllers.clear();
  }
  async request<T>(
    path: string,
    decode: Decoder<T>,
    options: RequestOptions = {},
  ): Promise<T> {
    if (this.#closed) throw new DOMException("Cancelled", "AbortError");
    if (!/^\/api\/v1\/[a-zA-Z0-9/?=&-]+$/.test(path)) throw new ApiError(400);
    const controller = new AbortController();
    this.#controllers.add(controller);
    const abort = () => controller.abort();
    options.signal?.addEventListener("abort", abort, { once: true });
    if (options.signal?.aborted) controller.abort();
    const timer = setTimeout(abort, 120_000);
    try {
      const response = await fetch(path, {
        method: options.method ?? "GET",
        headers: {
          Accept: "application/json",
          ...(this.#token ? { Authorization: `Bearer ${this.#token}` } : {}),
          ...(options.json !== undefined
            ? { "Content-Type": "application/json" }
            : {}),
        },
        body:
          options.form ??
          (options.json !== undefined
            ? JSON.stringify(options.json)
            : undefined),
        signal: controller.signal,
        cache: "no-store",
        credentials: "omit",
        redirect: "error",
        referrerPolicy: "no-referrer",
      });
      // A response from a disposed tenant scope cannot expire or populate a newer scope.
      if (this.#closed || controller.signal.aborted)
        throw new DOMException("Cancelled", "AbortError");
      const rawId = response.headers.get("X-Request-ID");
      const requestId = rawId && isUuid(rawId) ? rawId : null;
      if (response.status === 401) {
        this.#unauthorized();
        throw new ApiError(401, requestId);
      }
      const reader = response.body?.getReader();
      const chunks: Uint8Array[] = [];
      let bytes = 0;
      if (!reader) throw new ApiError(502, requestId);
      while (true) {
        const chunk = await reader.read();
        if (chunk.done) break;
        bytes += chunk.value.length;
        if (bytes > 8 * 1024 * 1024) {
          await reader.cancel();
          throw new ApiError(502, requestId);
        }
        chunks.push(chunk.value);
      }
      let body: unknown;
      try {
        const buffer = new Uint8Array(bytes);
        let offset = 0;
        for (const chunk of chunks) {
          buffer.set(chunk, offset);
          offset += chunk.length;
        }
        body = JSON.parse(
          new TextDecoder("utf-8", { fatal: true }).decode(buffer),
        );
      } catch {
        throw new ApiError(response.ok ? 502 : response.status, requestId);
      }
      if (!response.ok) {
        const id =
          body && typeof body === "object" && "runId" in body
            ? body.runId
            : null;
        throw new ApiError(
          response.status,
          requestId,
          typeof id === "string" && isUuid(id) ? id : null,
        );
      }
      if (this.#closed || controller.signal.aborted)
        throw new DOMException("Cancelled", "AbortError");
      try {
        return decode(body);
      } catch {
        throw new ApiError(502, requestId);
      }
    } catch (error) {
      if (this.#closed || options.signal?.aborted)
        throw new DOMException("Cancelled", "AbortError");
      if (error instanceof ApiError) throw error;
      if (controller.signal.aborted) throw new ApiError(504);
      throw new ApiError(0);
    } finally {
      clearTimeout(timer);
      options.signal?.removeEventListener("abort", abort);
      this.#controllers.delete(controller);
    }
  }
}
