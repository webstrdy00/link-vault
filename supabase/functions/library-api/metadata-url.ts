const NON_PUBLIC_SUFFIXES = [
  "localhost",
  "local",
  "localdomain",
  "internal",
  "home",
  "home.arpa",
  "lan",
  "test",
  "invalid",
  "example",
  "onion",
];

// Eligibility is syntactic only. Fetching must also validate and pin every DNS
// answer, including at each redirect hop.
export function isMetadataUrlEligible(rawUrl: string): boolean {
  return [...rawUrl].length <= 4096 && parseMetadataUrl(rawUrl) !== null;
}

export function hasInvalidMetadataUrlCharacters(value: string): boolean {
  if (/\s/u.test(value)) return true;
  for (let index = 0; index < value.length; index++) {
    const code = value.charCodeAt(index);
    if (code <= 0x1f || code === 0x7f || code === 0x5c) return true;
  }
  return false;
}

// Raw URL syntax and endpoint policy, without the caller's input-length budget.
// Redirects budget the original Location, not a prefixed or encoded URL.
export function parseMetadataUrl(rawUrl: string): URL | null {
  const authority = /^https?:\/\/([^/?#]+)/iu.exec(rawUrl)?.[1];
  if (
    rawUrl !== rawUrl.trim() || !/^https?:\/\//iu.test(rawUrl) ||
    hasInvalidMetadataUrlCharacters(rawUrl) ||
    authority === undefined ||
    !/^[A-Za-z0-9.-]+(?::(?:80|443))?$/u.test(authority)
  ) {
    return null;
  }
  let parsed: URL;
  try {
    parsed = new URL(rawUrl);
  } catch {
    return null;
  }
  return isMetadataParsedUrlEligible(parsed) ? parsed : null;
}

// URL serialization can expand Unicode paths. A pinned transport must validate
// the endpoint without reapplying a raw-input length or lexical-authority check.
export function isMetadataParsedUrlEligible(parsed: URL): boolean {
  const hostname = parsed.hostname;
  if (
    (parsed.protocol !== "http:" && parsed.protocol !== "https:") ||
    parsed.port !== "" || parsed.username !== "" || parsed.password !== "" ||
    hostname.length > 253 || !hostname.includes(".") ||
    /^\d+(?:\.\d+){3}$/u.test(hostname) ||
    !hostname.split(".").every((label) =>
      /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/u.test(label)
    )
  ) {
    return false;
  }
  return !NON_PUBLIC_SUFFIXES.some((suffix) =>
    hostname === suffix || hostname.endsWith(`.${suffix}`)
  );
}
