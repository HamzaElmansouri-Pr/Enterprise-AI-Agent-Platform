import { TableScroll } from "../../components/ui";
import { useCallback, useState } from "react";
import type { TenantApi } from "../../api/tenant";
import { useRemote } from "../../components/hooks";
import {
  Empty,
  ErrorNotice,
  Heading,
  Id,
  Loading,
  Pager,
  Status,
  When,
} from "../../components/ui";
export function Tasks({ api }: { api: TenantApi }) {
  const [page, setPage] = useState(0);
  const remote = useRemote(
    useCallback((signal: AbortSignal) => api.tasks(page, signal), [api, page]),
  );
  return (
    <>
      <Heading
        title="Admissions follow-up tasks"
        description="Internal follow-up work created through approved execution. Creating a task does not send an email or message."
        action={<button onClick={remote.refresh}>Refresh tasks</button>}
      />
      <ErrorNotice error={remote.error} />
      {remote.loading && <Loading />}
      {remote.data && (
        <>
          <TableScroll>
            <table>
              <thead>
                <tr>
                  <th>Task</th>
                  <th>Priority / status</th>
                  <th>Created</th>
                  <th>Audit references</th>
                </tr>
              </thead>
              <tbody>
                {remote.data.items.map((t) => (
                  <tr key={t.id}>
                    <td>
                      <strong>{t.title}</strong>
                      <p className="preserve">{t.description}</p>
                      <Id value={t.id} />
                    </td>
                    <td>
                      {t.priority}
                      <br />
                      <Status value={t.status} />
                    </td>
                    <td>
                      <When value={t.createdAt} />
                    </td>
                    <td>
                      <small>
                        Creator <Id value={t.createdBy} />
                      </small>
                      <small>
                        Source proposal <Id value={t.sourceProposalId} />
                      </small>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </TableScroll>
          {!remote.data.items.length && (
            <Empty>
              No follow-up tasks on this page. Creating or approving a proposal
              does not create a task.
            </Empty>
          )}
          <Pager page={page} hasNext={remote.data.hasNext} onChange={setPage} />
        </>
      )}
    </>
  );
}
