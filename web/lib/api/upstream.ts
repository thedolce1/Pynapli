/**
 * Server-only helpers for talking to the Ktor :server. Never import from
 * client components — these run in route handlers and middleware only.
 */

const API_BASE_URL =
  process.env.PYNAPLI_API_BASE_URL ?? "http://localhost:8080";

export interface UpstreamCallOptions {
  method: string;
  path: string;
  bearer?: string | null;
  body?: unknown;
  headers?: Record<string, string>;
  forwardedFor?: string | null;
  userAgent?: string | null;
}

export interface UpstreamResponse {
  status: number;
  body: unknown;
  headers: Headers;
}

export async function callUpstream(opts: UpstreamCallOptions): Promise<UpstreamResponse> {
  const url = new URL(opts.path.startsWith("/") ? opts.path : `/${opts.path}`, API_BASE_URL);

  const headers: Record<string, string> = {
    Accept: "application/json",
    "X-Platform": "web",
    ...opts.headers,
  };
  if (opts.body !== undefined && headers["Content-Type"] === undefined) {
    headers["Content-Type"] = "application/json";
  }
  if (opts.bearer) {
    headers["Authorization"] = `Bearer ${opts.bearer}`;
  }
  if (opts.forwardedFor) {
    headers["X-Forwarded-For"] = opts.forwardedFor;
  }
  if (opts.userAgent) {
    headers["User-Agent"] = opts.userAgent;
  }

  const res = await fetch(url, {
    method: opts.method,
    headers,
    body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
    cache: "no-store",
  });

  let body: unknown = null;
  const contentType = res.headers.get("content-type") ?? "";
  if (contentType.includes("application/json")) {
    body = await res.json().catch(() => null);
  } else {
    const text = await res.text().catch(() => "");
    body = text || null;
  }

  return { status: res.status, body, headers: res.headers };
}

export interface AuthSessionResponse {
  accessToken: string;
  refreshToken: string;
  user: {
    userId: string;
    status: string;
  };
}

export function isAuthSession(value: unknown): value is AuthSessionResponse {
  if (typeof value !== "object" || value === null) return false;
  const v = value as Record<string, unknown>;
  return (
    typeof v.accessToken === "string" &&
    typeof v.refreshToken === "string" &&
    typeof v.user === "object" &&
    v.user !== null
  );
}
