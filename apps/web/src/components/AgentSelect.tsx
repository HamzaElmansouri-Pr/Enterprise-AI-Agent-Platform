import { useCallback, useState } from "react";
import type { TenantApi } from "../api/tenant";
import { useRemote } from "./hooks";
import { ErrorNotice, Field, Pager } from "./ui";
export function AgentSelect({
  api,
  value,
  onChange,
  includeArchived = false,
}: {
  includeArchived?: boolean;
  api: TenantApi;
  value: string;
  onChange: (id: string) => void;
}) {
  const [page, setPage] = useState(0);
  const remote = useRemote(
    useCallback(
      (signal: AbortSignal) =>
        api.agents(page, includeArchived ? "" : "DRAFT", signal),
      [api, page, includeArchived],
    ),
  );
  return (
    <div>
      <Field
        label={includeArchived ? "Conversation agent" : "DRAFT agent"}
        hint={
          includeArchived
            ? "Archived agents allow reading existing conversations only."
            : "Only accessible DRAFT configurations can be selected."
        }
      >
        <select
          required
          value={value}
          onChange={(e) => onChange(e.target.value)}
        >
          <option value="">Select an agent</option>
          {value && !remote.data?.items.some((a) => a.id === value) && (
            <option value={value}>{value}</option>
          )}
          {remote.data?.items.map((a) => (
            <option key={a.id} value={a.id}>
              {a.name}
            </option>
          ))}
        </select>
      </Field>
      <ErrorNotice error={remote.error} />
      {remote.data && (page > 0 || remote.data.hasNext) && (
        <Pager page={page} hasNext={remote.data.hasNext} onChange={setPage} />
      )}
    </div>
  );
}
