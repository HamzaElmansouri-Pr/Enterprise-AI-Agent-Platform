import { useEffect, useId, useRef } from "react";
import type { ReactNode } from "react";
import { ApiError } from "../api/client";
export function ErrorNotice({ error }: { error: unknown }) {
  if (!error) return null;
  const safe = error instanceof ApiError ? error : new ApiError(0);
  return (
    <div className="alert error" role="alert">
      {safe.message}
      {safe.requestId && <small>Request ID: {safe.requestId}</small>}
    </div>
  );
}
export function Status({ value }: { value: string }) {
  return (
    <span role="status" className={`status status-${value.toLowerCase()}`}>
      {value.replaceAll("_", " ")}
    </span>
  );
}
export function Id({ value }: { value: string }) {
  return <code className="identifier">{value}</code>;
}
export function When({ value }: { value: string | null }) {
  return <>{value ? new Date(value).toLocaleString() : "Unavailable"}</>;
}
export function Heading({
  title,
  description,
  action,
}: {
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <header className="page-heading">
      <div>
        <h1>{title}</h1>
        <p>{description}</p>
      </div>
      {action}
    </header>
  );
}
export function Empty({ children }: { children: ReactNode }) {
  return <p className="empty">{children}</p>;
}
export function Pager({
  page,
  hasNext,
  onChange,
}: {
  page: number;
  hasNext: boolean;
  onChange: (page: number) => void;
}) {
  return (
    <nav className="pager" aria-label="Pagination">
      <button disabled={page === 0} onClick={() => onChange(page - 1)}>
        Previous
      </button>
      <span>Page {page + 1}</span>
      <button
        disabled={!hasNext || (page + 1) * 20 > 10000}
        onClick={() => onChange(page + 1)}
      >
        Next
      </button>
    </nav>
  );
}
export function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: ReactNode;
}) {
  return (
    <label className="field">
      <span>{label}</span>
      {children}
      {hint && <small>{hint}</small>}
    </label>
  );
}
export function Loading() {
  return (
    <p role="status" className="loading">
      Loading records…
    </p>
  );
}
export function TableScroll({ children }: { children: ReactNode }) {
  const hint = useId();
  return (
    <>
      <p id={hint} className="table-hint">
        On a narrow screen, scroll this table sideways to reach all columns and
        actions. Keyboard: focus the table area and use the arrow keys.
      </p>
      <div
        className="table-wrap"
        role="region"
        aria-label="Scrollable records"
        aria-describedby={hint}
        tabIndex={0}
      >
        {children}
      </div>
    </>
  );
}
export function Confirm({
  title,
  children,
  busy,
  onCancel,
  onConfirm,
  confirm = "Confirm",
  disabled = false,
}: {
  title: string;
  children: ReactNode;
  busy: boolean;
  onCancel: () => void;
  onConfirm: () => void;
  confirm?: string;
  disabled?: boolean;
}) {
  const ref = useRef<HTMLDialogElement>(null),
    id = useId();
  useEffect(() => {
    const dialog = ref.current;
    const previous = document.activeElement;
    dialog?.showModal();
    return () => {
      dialog?.close();
      if (previous instanceof HTMLElement) previous.focus();
    };
  }, []);
  return (
    <dialog
      ref={ref}
      aria-labelledby={id}
      onCancel={(e) => {
        e.preventDefault();
        if (!busy) onCancel();
      }}
    >
      <h2 id={id}>{title}</h2>
      {children}
      <div className="actions">
        <button autoFocus disabled={busy} onClick={onCancel}>
          Cancel
        </button>
        <button
          className="primary"
          disabled={busy || disabled}
          onClick={onConfirm}
        >
          {busy ? "Working…" : confirm}
        </button>
      </div>
    </dialog>
  );
}
export function CostNotice() {
  return (
    <p className="alert">
      This may invoke a paid hosted model and consumes the shared daily
      execution budget. Provider configuration is required. A timeout may still
      incur charges. Requests are never retried automatically.
    </p>
  );
}
