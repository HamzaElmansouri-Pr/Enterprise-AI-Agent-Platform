import type {
  Agent,
  KnowledgeDocument,
  Proposal,
  Run,
  TenantContext,
} from "../api/contracts";
export const tenant = "10000000-0000-4000-8000-000000000008",
  user = "20000000-0000-4000-8000-000000000008",
  reviewer = "20000000-0000-4000-8000-000000000009",
  agentId = "30000000-0000-4000-8000-000000000008",
  documentId = "40000000-0000-4000-8000-000000000008",
  proposalId = "50000000-0000-4000-8000-000000000008",
  taskId = "60000000-0000-4000-8000-000000000008",
  runId = "70000000-0000-4000-8000-000000000008";
export const timestamp = "2026-11-22T03:00:00Z";
export const context: TenantContext = {
  tenantId: tenant,
  userId: user,
  role: "OWNER",
};
export const agent: Agent = {
  id: agentId,
  tenantId: tenant,
  name: "Admissions Assistant",
  description: "Admissions support",
  systemInstructions: "Answer using knowledge.",
  status: "DRAFT",
  createdAt: timestamp,
  updatedAt: timestamp,
};
export const document: KnowledgeDocument = {
  id: documentId,
  tenantId: tenant,
  filename: "admissions.txt",
  contentType: "text/plain",
  byteSize: 64,
  sha256: "a".repeat(64),
  status: "READY",
  embeddingModel: "test-model",
  chunkCount: 1,
  failureCode: null,
  createdAt: timestamp,
  updatedAt: timestamp,
  agentIds: [agentId],
  hasMoreAgentIds: false,
};
export const proposal: Proposal = {
  id: proposalId,
  tenantId: tenant,
  agentId,
  toolName: "admissions.create_followup_task",
  toolVersion: 1,
  arguments: {
    title: "Review admission documents",
    description: "Check the passport and transcript.",
    priority: "NORMAL",
  },
  rationale: "Human review required.",
  payloadDigest: "a".repeat(64),
  status: "PENDING_APPROVAL",
  createdBy: user,
  createdAt: timestamp,
  decision: null,
  executedBy: null,
  taskId: null,
  failureCode: null,
  startedAt: null,
  completedAt: null,
};
export const run: Run = {
  runId,
  agentId,
  status: "SUCCEEDED",
  answer: "Applicants need a passport and academic transcripts.",
  citations: [
    {
      documentId,
      chunkId: documentId,
      filename: "admissions.txt",
      sourcePage: null,
    },
  ],
  usage: { inputTokens: 30, outputTokens: 10 },
  durationMs: 123,
  modelId: "test-model",
  failureCode: null,
};
export function page<T>(items: T[], hasNext = false, index = 0) {
  return { items, page: index, size: 20, hasNext };
}
export function response(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      "Content-Type": "application/json",
      "X-Request-ID": "90000000-0000-4000-8000-000000000008",
      "Cache-Control": "no-store",
    },
  });
}
