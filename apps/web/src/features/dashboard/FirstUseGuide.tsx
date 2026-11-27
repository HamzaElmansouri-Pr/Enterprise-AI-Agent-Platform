import type {
  Agent,
  KnowledgeDocument,
  TenantContext,
} from "../../api/contracts";

export function FirstUseGuide({
  context,
  agents,
  documents,
  invitationFound,
  navigate,
}: {
  context: TenantContext;
  agents: Agent[];
  documents: KnowledgeDocument[];
  invitationFound: boolean | null;
  navigate: (page: string) => void;
}) {
  const drafts = agents.filter((agent) => agent.status === "DRAFT");
  const ready = documents.some(
    (document) =>
      document.status === "READY" &&
      document.chunkCount > 0 &&
      document.agentIds.some((id) => drafts.some((agent) => agent.id === id)),
  );
  const manager = context.role !== "MEMBER";
  const steps = [
    {
      label: manager ? "Configure your assistant" : "Review your assistants",
      found: drafts.length > 0,
      page: "agents",
      description:
        "An agent configuration defines a purpose and instructions. DRAFT assistants can already be used; there is no publishing step.",
    },
    {
      label: manager
        ? "Upload and associate a document"
        : "Review uploaded documents",
      found: documents.length > 0,
      page: "knowledge",
      description:
        "Choose an assistant when uploading a supported PDF or TXT. Upload acceptance is not readiness; wait for processing.",
    },
    {
      label: "Check ready knowledge",
      found: ready,
      page: "knowledge",
      description:
        "READY documents with chunks and an association to a usable assistant can supply evidence. Failed files need repair or an operator check.",
    },
    {
      label: "Find information in your documents",
      found: null,
      page: "search",
      description:
        "Semantic search returns source passages without a hosted generative call. Read the source; similarity is not certainty. Search completion is not tracked.",
    },
    {
      label: "Understand AI answers and resume conversations",
      found: null,
      page: "conversations",
      description:
        "AI responses may be wrong and require a configured hosted provider. Availability is not checked here. Starting or sending is a separate, deliberate action that may incur a charge. Resume your own saved conversations; retrieve a lost outcome before sending again.",
    },
    ...(manager
      ? [
          {
            label: "Invite a colleague",
            found: invitationFound,
            page: "team",
            description:
              "Create a scoped invitation and share its token securely. No email is sent. Refresh access when your colleague has accepted.",
          },
        ]
      : []),
    {
      label: "Review before creating internal work",
      found: null,
      page: "approvals",
      description:
        "An unapproved proposal does nothing. A different authorized manager must approve it; approval still creates no task. Explicit execution creates one internal task, not a message delivered to a customer.",
    },
  ];
  return (
    <section className="card" aria-label="Getting started">
      <h2>Getting started in your organization</h2>
      <p>
        Your verified role is <strong>{context.role}</strong>.{" "}
        {manager
          ? "You can configure assistants and knowledge. Only an OWNER can invite an ADMIN."
          : "You can read authorized knowledge and ask questions. Ask a manager to change configurations."}
      </p>
      <p>
        Indicators reflect the current bounded pages, not all historical
        activity. Refresh overview to check again. No model or business action
        runs from this guide.
      </p>
      <ol className="onboarding-steps">
        {steps.map((step) => (
          <li key={step.label}>
            <h3>{step.label}</h3>
            <p>{step.description}</p>
            {step.found !== null && (
              <p>
                <strong>
                  {step.found
                    ? "Found in current records"
                    : "Not observed on these pages"}
                </strong>
              </p>
            )}
            <button onClick={() => navigate(step.page)}>
              Go to {step.label.toLowerCase()}
            </button>
          </li>
        ))}
      </ol>
    </section>
  );
}
