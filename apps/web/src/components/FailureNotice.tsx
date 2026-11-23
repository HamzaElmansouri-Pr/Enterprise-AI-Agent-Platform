import { safeFailure } from "../api/failures";
const guidance: Record<string, string> = {
  INVALID_PDF:
    "This PDF could not be read. Export a new, valid PDF and upload it again. Retrying the same damaged file will not repair it.",
  ENCRYPTED_PDF: "Upload an unencrypted copy that you are authorized to use.",
  NO_EXTRACTABLE_TEXT:
    "No readable text was found. Upload a text-based PDF or a UTF-8 TXT file; scanned images are not supported.",
  PAGE_LIMIT: "Reduce the PDF to at most 25 pages and upload it again.",
  TEXT_LIMIT:
    "Split this document into smaller supported files and upload them again.",
  CHUNK_LIMIT:
    "Split this document into smaller supported files and upload them again.",
  EMBEDDING_UNAVAILABLE:
    "The document could not be indexed. Ask an operator to check the knowledge service, then use Retry processing.",
  PROCESSING_FAILED:
    "Processing did not finish. Ask an operator to check the service before using Retry processing.",
  MODEL_UNAVAILABLE:
    "No answer was produced because the model is unavailable. Ask an operator to check provider configuration.",
  RUNTIME_FAILURE:
    "The agent response could not be completed or validated. No successful answer was produced. Ask an operator to review the saved outcome before a new attempt.",
  RUNTIME_UNAVAILABLE:
    "No successful answer is available. Ask an operator to check the agent service. Retrieve the saved outcome before starting another attempt.",
  PROVIDER_TIMEOUT:
    "The model did not respond in time. A provider charge may still have occurred. Retrieve the saved outcome; do not resend automatically.",
  INVALID_MODEL_OUTPUT:
    "The model response or its citations could not be validated. It is not shown as a successful answer. Review the evidence before considering a new attempt.",
  INSUFFICIENT_KNOWLEDGE:
    "No usable supporting evidence was available. Check the agent’s READY documents and associations before asking again.",
  EXECUTION_INTERRUPTED:
    "This attempt was interrupted. Inspect its saved outcome before considering a new attempt; no automatic retry will occur.",
};
export function FailureNotice({ code }: { code: string }) {
  const safe = safeFailure(code);
  return (
    <div className="alert error" role="alert">
      <p>
        {guidance[safe] ??
          "The operation did not complete successfully. Ask an operator to review the saved outcome before trying again."}
      </p>
      <small>Failure: {safe}</small>
    </div>
  );
}
