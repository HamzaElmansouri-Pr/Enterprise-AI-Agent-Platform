export function validateFile(file: File | null) {
  if (!file) return "Select a PDF or TXT document.";
  if (file.size === 0 || file.size > 2 * 1024 * 1024)
    return "Choose a nonempty file at most 2 MiB.";
  if (!(
    (file.name.toLowerCase().endsWith(".pdf") &&
      file.type === "application/pdf") ||
    (file.name.toLowerCase().endsWith(".txt") && file.type === "text/plain")
  ))
    return "Only PDF (application/pdf) and TXT (text/plain) files are supported.";
  return null;
}
