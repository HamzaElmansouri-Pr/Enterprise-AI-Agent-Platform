import { ApiClient } from "./client";
import {
  agentContract,
  documentContract,
  pageContract,
  proposalContract,
  runContract,
  searchContract,
  taskContract,
  uuid,
} from "./contracts";
import type { RequestOptions } from "./client";
export type AgentInput = {
  name: string;
  description: string;
  systemInstructions: string;
};
export type ManualInput = {
  toolName: "admissions.create_followup_task";
  arguments: {
    title: string;
    description: string;
    priority: "LOW" | "NORMAL" | "HIGH";
  };
  rationale: string;
};
export class TenantApi {
  readonly client: ApiClient;
  readonly base: string;
  constructor(client: ApiClient, tenant: string) {
    this.client = client;
    this.base = `/api/v1/tenants/${uuid(tenant)}`;
  }
  agents(page = 0, status = "", signal?: AbortSignal) {
    return this.client.request(
      `${this.base}/agents?page=${page}&size=20${status ? `&status=${status}` : ""}`,
      pageContract(agentContract),
      { signal },
    );
  }
  agent(id: string, options?: RequestOptions) {
    return this.client.request(
      `${this.base}/agents/${uuid(id)}`,
      agentContract,
      options,
    );
  }
  createAgent(input: AgentInput, signal?: AbortSignal) {
    return this.client.request(`${this.base}/agents`, agentContract, {
      method: "POST",
      json: input,
      signal,
    });
  }
  archive(id: string, signal?: AbortSignal) {
    return this.client.request(
      `${this.base}/agents/${uuid(id)}/archive`,
      agentContract,
      { method: "POST", signal },
    );
  }
  documents(page = 0, signal?: AbortSignal) {
    return this.client.request(
      `${this.base}/knowledge/documents?page=${page}&size=20`,
      pageContract(documentContract),
      { signal },
    );
  }
  document(id: string, signal?: AbortSignal) {
    return this.client.request(
      `${this.base}/knowledge/documents/${uuid(id)}`,
      documentContract,
      { signal },
    );
  }
  upload(file: File, agent: string, signal?: AbortSignal) {
    const form = new FormData();
    form.append("file", file);
    form.append("agentId", uuid(agent));
    return this.client.request(
      `${this.base}/knowledge/documents`,
      documentContract,
      { method: "POST", form, signal },
    );
  }
  retry(id: string, signal?: AbortSignal) {
    return this.client.request(
      `${this.base}/knowledge/documents/${uuid(id)}/retry`,
      documentContract,
      { method: "POST", signal },
    );
  }
  association(
    document: string,
    agent: string,
    remove: boolean,
    signal?: AbortSignal,
  ) {
    return this.client.request(
      `${this.base}/knowledge/documents/${uuid(document)}/agents/${uuid(agent)}`,
      documentContract,
      { method: remove ? "DELETE" : "POST", signal },
    );
  }
  search(agent: string, query: string, signal?: AbortSignal) {
    return this.client.request(
      `${this.base}/agents/${uuid(agent)}/knowledge/search`,
      searchContract,
      { method: "POST", json: { query, topK: 5 }, signal },
    );
  }
  run(agent: string, message: string, signal?: AbortSignal) {
    return this.client.request(
      `${this.base}/agents/${uuid(agent)}/runs`,
      runContract,
      { method: "POST", json: { message, topK: 5 }, signal },
    );
  }
  runStatus(agent: string, run: string, signal?: AbortSignal) {
    return this.client.request(
      `${this.base}/agents/${uuid(agent)}/runs/${uuid(run)}`,
      runContract,
      { signal },
    );
  }
  proposals(page = 0, signal?: AbortSignal) {
    return this.client.request(
      `${this.base}/tool-proposals?page=${page}&size=20`,
      pageContract(proposalContract),
      { signal },
    );
  }
  proposal(id: string, signal?: AbortSignal) {
    return this.client.request(
      `${this.base}/tool-proposals/${uuid(id)}`,
      proposalContract,
      { signal },
    );
  }
  submit(agent: string, input: ManualInput, signal?: AbortSignal) {
    return this.client.request(
      `${this.base}/agents/${uuid(agent)}/tool-proposals`,
      proposalContract,
      { method: "POST", json: input, signal },
    );
  }
  generate(agent: string, intent: string, signal?: AbortSignal) {
    return this.client.request(
      `${this.base}/agents/${uuid(agent)}/tool-proposals/generate`,
      proposalContract,
      { method: "POST", json: { intent }, signal },
    );
  }
  decide(
    id: string,
    digest: string,
    reason: string | null,
    signal?: AbortSignal,
  ) {
    return this.client.request(
      `${this.base}/tool-proposals/${uuid(id)}/${reason === null ? "approve" : "reject"}`,
      proposalContract,
      {
        method: "POST",
        json: {
          expectedPayloadDigest: digest,
          ...(reason === null ? {} : { reason }),
        },
        signal,
      },
    );
  }
  execute(id: string, signal?: AbortSignal) {
    return this.client.request(
      `${this.base}/tool-proposals/${uuid(id)}/execute`,
      proposalContract,
      { method: "POST", signal },
    );
  }
  tasks(page = 0, signal?: AbortSignal) {
    return this.client.request(
      `${this.base}/admissions/followup-tasks?page=${page}&size=20`,
      pageContract(taskContract),
      { signal },
    );
  }
}
