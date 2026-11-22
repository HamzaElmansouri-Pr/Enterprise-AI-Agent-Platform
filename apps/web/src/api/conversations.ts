import type { TenantApi } from "./tenant";
import {
  object,
  uuid,
  text,
  choice,
  nullable,
  array,
  number,
  runContract,
  pageContract,
} from "./contracts";
import type { Decoder } from "./contracts";
const timestamp: Decoder<string> = (v) => {
  const s = text(v);
  if (!Number.isFinite(Date.parse(s))) throw new Error("Invalid date");
  return s;
};
export const conversationContract = object({
  id: uuid,
  tenantId: uuid,
  agentId: uuid,
  createdBy: uuid,
  title: text,
  status: choice("OPEN", "CLOSED"),
  createdAt: timestamp,
  updatedAt: timestamp,
});
export type Conversation = ReturnType<typeof conversationContract>;
const ordinal: Decoder<number> = (value) => {
  const n = number(value);
  if (!Number.isSafeInteger(n) || n < 1 || n > 200)
    throw new Error("Invalid ordinal");
  return n;
};
const citationContract = object({
  documentId: uuid,
  chunkId: uuid,
  filename: text,
  sourcePage: nullable(number),
});
export const messageContract = object({
  id: uuid,
  requestId: uuid,
  ordinal,
  role: choice("USER", "ASSISTANT"),
  content: nullable(text),
  status: choice("RUNNING", "SUCCEEDED", "FAILED"),
  citations: array(citationContract, 6),
  sourceRunId: uuid,
  createdAt: timestamp,
});
export type Message = ReturnType<typeof messageContract>;
export const turnContract = object({
  requestId: uuid,
  runId: uuid,
  status: choice("RUNNING", "SUCCEEDED", "FAILED"),
  result: runContract,
});
export class ConversationApi {
  readonly base: string;
  readonly tenant: TenantApi;
  constructor(tenant: TenantApi, agent: string) {
    this.tenant = tenant;
    this.base = `${tenant.base}/agents/${uuid(agent)}/conversations`;
  }
  list(page: number, signal: AbortSignal) {
    return this.tenant.client.request(
      `${this.base}?page=${page}&size=20`,
      pageContract(conversationContract),
      { signal },
    );
  }
  create(title: string, signal: AbortSignal) {
    return this.tenant.client.request(this.base, conversationContract, {
      method: "POST",
      json: { title },
      signal,
    });
  }
  get(id: string, signal: AbortSignal) {
    return this.tenant.client.request(
      `${this.base}/${uuid(id)}`,
      conversationContract,
      { signal },
    );
  }
  messages(id: string, page: number, signal: AbortSignal) {
    return this.tenant.client.request(
      `${this.base}/${uuid(id)}/messages?page=${page}&size=20`,
      pageContract(messageContract),
      { signal },
    );
  }
  rename(id: string, title: string, signal: AbortSignal) {
    return this.tenant.client.request(
      `${this.base}/${uuid(id)}`,
      conversationContract,
      { method: "PUT", json: { title }, signal },
    );
  }
  close(id: string, signal: AbortSignal) {
    return this.tenant.client.request(
      `${this.base}/${uuid(id)}/close`,
      conversationContract,
      { method: "POST", signal },
    );
  }
  send(id: string, requestId: string, message: string, signal: AbortSignal) {
    return this.tenant.client.request(
      `${this.base}/${uuid(id)}/messages`,
      turnContract,
      { method: "POST", json: { requestId, message }, signal },
    );
  }
  outcome(id: string, requestId: string, signal: AbortSignal) {
    return this.tenant.client.request(
      `${this.base}/${uuid(id)}/requests/${uuid(requestId)}`,
      turnContract,
      { signal },
    );
  }
}
