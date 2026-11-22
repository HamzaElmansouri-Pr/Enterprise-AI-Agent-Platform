import { useEffect, useReducer, useRef, useState } from "react";
import { ApiError, wasAborted } from "../api/client";
export function useRemote<T>(load: (signal: AbortSignal) => Promise<T>) {
  const [version, refresh] = useReducer((v) => v + 1, 0);
  const [state, setState] = useState<{
    load: typeof load;
    version: number;
    data?: T;
    error?: unknown;
  }>();
  useEffect(() => {
    const controller = new AbortController();
    load(controller.signal).then(
      (data) => {
        if (!controller.signal.aborted) setState({ load, version, data });
      },
      (error) => {
        if (!controller.signal.aborted && !wasAborted(error))
          setState({ load, version, error });
      },
    );
    return () => controller.abort();
  }, [load, version]);
  const current =
    state?.load === load && state.version === version ? state : undefined;
  return {
    data: current?.data,
    error: current?.error,
    loading: !current,
    refresh,
  };
}
export function useAction() {
  const [busy, setBusy] = useState(false),
    [error, setError] = useState<unknown>(null),
    [notice, setNotice] = useState("");
  const ref = useRef<AbortController | null>(null),
    alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
      ref.current?.abort();
    };
  }, []);
  async function act<T>(
    work: (signal: AbortSignal) => Promise<T>,
    done?: (data: T) => void,
  ) {
    if (ref.current) return;
    const controller = new AbortController();
    ref.current = controller;
    setBusy(true);
    setError(null);
    setNotice("");
    try {
      const data = await work(controller.signal);
      if (alive.current && !controller.signal.aborted) done?.(data);
    } catch (error) {
      if (alive.current && !controller.signal.aborted && !wasAborted(error))
        setError(error instanceof ApiError ? error : new ApiError(0));
    } finally {
      if (alive.current) setBusy(false);
      ref.current = null;
    }
  }
  return { busy, error, notice, setNotice, act };
}
