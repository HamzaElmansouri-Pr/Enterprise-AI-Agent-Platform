export function safeFailure(code: string | null) {
  const allowed = [
    "MODEL_UNAVAILABLE",
    "PROVIDER_TIMEOUT",
    "RUNTIME_UNAVAILABLE",
    "INVALID_MODEL_OUTPUT",
    "INSUFFICIENT_KNOWLEDGE",
    "EXECUTION_FAILED",
    "EXECUTION_INTERRUPTED",
    "TOOL_EXECUTION_FAILED",
    "INVALID_PDF",
    "ENCRYPTED_PDF",
    "PAGE_LIMIT",
    "TEXT_LIMIT",
    "NO_EXTRACTABLE_TEXT",
    "CHUNK_LIMIT",
    "EMBEDDING_UNAVAILABLE",
    "PROCESSING_FAILED",
  ];
  return code && allowed.includes(code) ? code : "Unavailable";
}
