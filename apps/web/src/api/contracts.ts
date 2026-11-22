// Runtime decoding mirrors the accepted Java DTOs; malformed responses fail closed.
export type Decoder<T> = (value: unknown) => T;
function invalid(): never {
  throw new Error("Invalid API contract");
}
export const text: Decoder<string> = (v) =>
  typeof v === "string" ? v : invalid();
export const number: Decoder<number> = (v) =>
  typeof v === "number" && Number.isFinite(v) ? v : invalid();
const integer: Decoder<number> = (v) =>
  Number.isSafeInteger(v) && number(v) >= 0 ? number(v) : invalid();
const bool: Decoder<boolean> = (v) => (typeof v === "boolean" ? v : invalid());
export const uuid: Decoder<string> = (v) =>
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
    text(v),
  )
    ? text(v)
    : invalid();
export const isUuid = (v: string) => {
  try {
    uuid(v);
    return true;
  } catch {
    return false;
  }
};
const date: Decoder<string> = (v) =>
  Number.isFinite(Date.parse(text(v))) ? text(v) : invalid();
export function nullable<T>(decode: Decoder<T>): Decoder<T | null> {
  return (v) => (v === null ? null : decode(v));
}
export function array<T>(decode: Decoder<T>, max = 100): Decoder<T[]> {
  return (v) =>
    Array.isArray(v) && v.length <= max ? v.map(decode) : invalid();
}
export function choice<const T extends readonly string[]>(
  ...values: T
): Decoder<T[number]> {
  return (v) => (values.includes(text(v)) ? (text(v) as T[number]) : invalid());
}
export function object<S extends Record<string, Decoder<unknown>>>(
  schema: S,
): Decoder<{ [K in keyof S]: ReturnType<S[K]> }> {
  return (v) => {
    if (!v || typeof v !== "object" || Array.isArray(v)) invalid();
    const input = v as Record<string, unknown>;
    return Object.fromEntries(
      Object.entries(schema).map(([key, decode]) => [key, decode(input[key])]),
    ) as { [K in keyof S]: ReturnType<S[K]> };
  };
}
export const tokenContract = object({
  accessToken: text,
  tokenType: choice("Bearer"),
  expiresIn: integer,
});
export const contextContract = object({
  tenantId: uuid,
  userId: uuid,
  role: choice("OWNER", "ADMIN", "MEMBER"),
});
export type TenantContext = ReturnType<typeof contextContract>;
export const agentContract = object({
  id: uuid,
  tenantId: uuid,
  name: text,
  description: nullable(text),
  systemInstructions: text,
  status: choice("DRAFT", "ARCHIVED"),
  createdAt: date,
  updatedAt: date,
});
export type Agent = ReturnType<typeof agentContract>;
export const documentContract = object({
  id: uuid,
  tenantId: uuid,
  filename: text,
  contentType: text,
  byteSize: integer,
  sha256: text,
  status: choice("PENDING", "PROCESSING", "READY", "FAILED"),
  embeddingModel: text,
  chunkCount: integer,
  failureCode: nullable(text),
  createdAt: date,
  updatedAt: date,
  agentIds: array(uuid),
  hasMoreAgentIds: bool,
});
export type KnowledgeDocument = ReturnType<typeof documentContract>;
export const matchContract = object({
  documentId: uuid,
  filename: text,
  chunkId: uuid,
  text,
  score: number,
  sourcePage: nullable(integer),
});
export const searchContract = object({ results: array(matchContract, 10) });
export type Match = ReturnType<typeof matchContract>;
const citationContract = object({
  documentId: uuid,
  chunkId: uuid,
  filename: text,
  sourcePage: nullable(integer),
});
export const runContract = object({
  runId: uuid,
  agentId: uuid,
  status: choice("RUNNING", "SUCCEEDED", "FAILED"),
  answer: nullable(text),
  citations: array(citationContract, 6),
  usage: object({
    inputTokens: nullable(integer),
    outputTokens: nullable(integer),
  }),
  durationMs: nullable(integer),
  failureCode: nullable(text),
  modelId: nullable(text),
});
export type Run = ReturnType<typeof runContract>;
export const argumentsContract = object({
  title: text,
  description: text,
  priority: choice("LOW", "NORMAL", "HIGH"),
});
const decisionContract = object({
  id: uuid,
  approverId: uuid,
  payloadDigest: text,
  decision: choice("APPROVED", "REJECTED"),
  reason: nullable(text),
  decidedAt: date,
});
export const proposalContract = object({
  id: uuid,
  tenantId: uuid,
  agentId: uuid,
  toolName: choice("admissions.create_followup_task"),
  toolVersion: integer,
  arguments: argumentsContract,
  rationale: text,
  payloadDigest: text,
  status: choice(
    "PENDING_APPROVAL",
    "APPROVED",
    "REJECTED",
    "EXECUTING",
    "SUCCEEDED",
    "FAILED",
  ),
  createdBy: uuid,
  createdAt: date,
  decision: nullable(decisionContract),
  executedBy: nullable(uuid),
  taskId: nullable(uuid),
  failureCode: nullable(text),
  startedAt: nullable(date),
  completedAt: nullable(date),
});
export type Proposal = ReturnType<typeof proposalContract>;
export const taskContract = object({
  id: uuid,
  tenantId: uuid,
  sourceProposalId: uuid,
  createdBy: uuid,
  title: text,
  description: text,
  priority: choice("LOW", "NORMAL", "HIGH"),
  status: choice("OPEN"),
  createdAt: date,
});
export type Task = ReturnType<typeof taskContract>;
export function pageContract<T>(decode: Decoder<T>) {
  return object({
    items: array(decode),
    page: integer,
    size: integer,
    hasNext: bool,
  });
}
export type Page<T> = {
  items: T[];
  page: number;
  size: number;
  hasNext: boolean;
};

export const memberContract = object({
  userId: uuid,
  email: nullable(text),
  role: choice("OWNER", "ADMIN", "MEMBER"),
  createdAt: date,
});
export type Member = ReturnType<typeof memberContract>;
export const invitationContract = object({
  id: uuid,
  tenantId: uuid,
  email: text,
  role: choice("ADMIN", "MEMBER"),
  invitedBy: uuid,
  status: choice("PENDING", "ACCEPTED", "CANCELLED"),
  createdAt: date,
  expiresAt: date,
  expired: bool,
  acceptedAt: nullable(date),
  acceptedBy: nullable(uuid),
  cancelledAt: nullable(date),
  cancelledBy: nullable(uuid),
});
export type Invitation = ReturnType<typeof invitationContract>;
export const issuedContract = object({
  invitation: invitationContract,
  token: text,
});
export type IssuedInvitation = ReturnType<typeof issuedContract>;
export const acceptedContract = object({
  invitationId: uuid,
  tenantId: uuid,
  userId: uuid,
  role: choice("ADMIN", "MEMBER"),
});
export type AcceptedInvitation = ReturnType<typeof acceptedContract>;
export const completedContract = object({ completed: bool });
