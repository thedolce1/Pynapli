/**
 * Generic authed BFF proxy.
 *
 * Browser → /api/<anything> → this handler reads the access cookie, attaches
 * Authorization: Bearer, and forwards to api.pynapli.com/v1/<anything>.
 * On 401 with WWW-Authenticate=expired, the client should call
 * /api/auth/refresh and retry.
 *
 * CSRF: enforced for non-GET via double-submit cookie.
 */
import { NextRequest, NextResponse } from "next/server";
import { COOKIE_NAMES } from "@/lib/auth/cookies";
import { csrfTokensMatch } from "@/lib/auth/csrf";
import { callUpstream } from "@/lib/api/upstream";

export const runtime = "nodejs";

const FORWARDED_HEADERS = ["accept", "accept-language", "x-app-version", "idempotency-key"];

async function handle(req: NextRequest, { params }: { params: Promise<{ proxy: string[] }> }) {
  const { proxy } = await params;
  const subPath = proxy?.length ? proxy.join("/") : "";
  const search = req.nextUrl.search;
  const upstreamPath = `/v1/${subPath}${search}`;

  if (req.method !== "GET" && req.method !== "HEAD" && req.method !== "OPTIONS") {
    const cookieToken = req.cookies.get(COOKIE_NAMES.csrf)?.value;
    const headerToken = req.headers.get("x-csrf-token") ?? undefined;
    if (!csrfTokensMatch(cookieToken, headerToken)) {
      return NextResponse.json({ error: "CSRF_MISMATCH" }, { status: 403 });
    }
  }

  const access = req.cookies.get(COOKIE_NAMES.accessToken)?.value ?? null;

  const forwarded: Record<string, string> = {};
  for (const name of FORWARDED_HEADERS) {
    const value = req.headers.get(name);
    if (value) forwarded[name] = value;
  }

  let body: unknown;
  if (req.method !== "GET" && req.method !== "HEAD") {
    const text = await req.text();
    body = text.length > 0 ? safeParseJson(text) : undefined;
  }

  const upstream = await callUpstream({
    method: req.method,
    path: upstreamPath,
    bearer: access,
    body,
    headers: forwarded,
    forwardedFor: req.headers.get("x-forwarded-for"),
    userAgent: req.headers.get("user-agent"),
  });

  const res = NextResponse.json(upstream.body ?? null, { status: upstream.status });
  const requestId = upstream.headers.get("x-request-id");
  if (requestId) res.headers.set("x-request-id", requestId);
  return res;
}

function safeParseJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

export const GET = handle;
export const POST = handle;
export const PATCH = handle;
export const DELETE = handle;
