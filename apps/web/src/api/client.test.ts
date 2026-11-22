import { describe, expect, it, vi } from "vitest";
import { ApiClient, ApiError } from "./client";
import { agentContract, contextContract } from "./contracts";
import { agent, context, response } from "../tests/fixtures";
describe("typed HTTP boundary", () => {
  it("sends only same-origin bearer requests without cookies or caching", async () => {
    const fetch = vi.fn().mockResolvedValue(response(context));
    vi.stubGlobal("fetch", fetch);
    expect(
      await new ApiClient("memory-token").request(
        "/api/v1/tenants/x/context",
        contextContract,
      ),
    ).toEqual(context);
    expect(fetch.mock.calls[0][1]).toMatchObject({
      credentials: "omit",
      cache: "no-store",
      redirect: "error",
      headers: { Authorization: "Bearer memory-token" },
    });
  });
  it("rejects an external destination before fetch", async () => {
    const fetch = vi.fn();
    vi.stubGlobal("fetch", fetch);
    await expect(
      new ApiClient("private").request(
        "https://example.test/api",
        contextContract,
      ),
    ).rejects.toBeInstanceOf(ApiError);
    expect(fetch).not.toHaveBeenCalled();
  });
  it.each([400, 403, 404, 409, 413, 415, 422, 429, 502, 503, 504])(
    "sanitizes HTTP %i and retains only safe request/run identifiers",
    async (status) => {
      vi.stubGlobal(
        "fetch",
        vi
          .fn()
          .mockResolvedValue(
            response(
              { message: "private-password", code: "private-token" },
              status,
            ),
          ),
      );
      try {
        await new ApiClient(null).request("/api/v1/test", contextContract);
        expect.fail();
      } catch (error) {
        expect(error).toBeInstanceOf(ApiError);
        expect(String(error)).not.toContain("private");
        expect((error as ApiError).status).toBe(status);
      }
    },
  );
  it("401 clears the session exactly once", async () => {
    const expired = vi.fn();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response({}, 401)));
    await expect(
      new ApiClient("token", expired).request("/api/v1/test", contextContract),
    ).rejects.toMatchObject({ status: 401 });
    expect(expired).toHaveBeenCalledOnce();
  });
  it("rejects malformed success DTOs without exposing the body", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        response({
          ...agent,
          status: "ACTIVE",
          systemInstructions: "private",
        }),
      ),
    );
    await expect(
      new ApiClient(null).request("/api/v1/test", agentContract),
    ).rejects.toMatchObject({ status: 502 });
  });
  it("disposal cancels requests and refuses future work", async () => {
    let signal: AbortSignal;
    vi.stubGlobal(
      "fetch",
      vi.fn((_url, options) => {
        signal = options.signal;
        return new Promise((_, reject) =>
          signal.addEventListener("abort", () =>
            reject(new DOMException("aborted", "AbortError")),
          ),
        );
      }),
    );
    const client = new ApiClient("token");
    const pending = client.request("/api/v1/test", contextContract);
    client.dispose();
    await expect(pending).rejects.toMatchObject({ name: "AbortError" });
    expect(signal!.aborted).toBe(true);
    await expect(
      client.request("/api/v1/test", contextContract),
    ).rejects.toMatchObject({ name: "AbortError" });
  });
  it("does not accept malformed JSON or redirects as success", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("<secret>")));
    await expect(
      new ApiClient(null).request("/api/v1/test", contextContract),
    ).rejects.toMatchObject({ status: 502 });
  });
});

describe("request scope limits", () => {
  it("ignores a late 401 from a disposed tenant scope", async () => {
    let resolve: (value: Response) => void = () => {};
    const expired = vi.fn();
    vi.stubGlobal(
      "fetch",
      vi.fn(
        () =>
          new Promise<Response>((r) => {
            resolve = r;
          }),
      ),
    );
    const client = new ApiClient("token", expired);
    const pending = client.request("/api/v1/test", contextContract);
    client.dispose();
    resolve(response({}, 401));
    await expect(pending).rejects.toMatchObject({ name: "AbortError" });
    expect(expired).not.toHaveBeenCalled();
  });
  it("bounds response bytes before JSON decoding", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response("x".repeat(8 * 1024 * 1024 + 1))),
    );
    await expect(
      new ApiClient(null).request("/api/v1/test", contextContract),
    ).rejects.toMatchObject({ status: 502 });
  });
  it("times out once and never automatically repeats the request", async () => {
    vi.useFakeTimers();
    try {
      const fetcher = vi.fn(
        (_path, options) =>
          new Promise((_resolve, reject) =>
            options.signal.addEventListener("abort", () =>
              reject(new DOMException("Aborted", "AbortError")),
            ),
          ),
      );
      vi.stubGlobal("fetch", fetcher);
      const pending = expect(
        new ApiClient("token").request("/api/v1/test", contextContract),
      ).rejects.toMatchObject({ status: 504 });
      await vi.advanceTimersByTimeAsync(120001);
      await pending;
      expect(fetcher).toHaveBeenCalledOnce();
    } finally {
      vi.useRealTimers();
    }
  });
});
