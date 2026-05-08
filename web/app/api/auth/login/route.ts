import { NextRequest, NextResponse } from "next/server";
import {
  COOKIE_NAMES,
  accessCookieOptions,
  csrfCookieOptions,
  refreshCookieOptions,
} from "@/lib/auth/cookies";
import { generateCsrfToken } from "@/lib/auth/csrf";
import { callUpstream, isAuthSession } from "@/lib/api/upstream";

export const runtime = "nodejs";

export async function POST(req: NextRequest) {
  let payload: unknown;
  try {
    payload = await req.json();
  } catch {
    return NextResponse.json({ error: "INVALID_JSON" }, { status: 400 });
  }

  if (
    typeof payload !== "object" ||
    payload === null ||
    typeof (payload as Record<string, unknown>).email !== "string" ||
    typeof (payload as Record<string, unknown>).password !== "string"
  ) {
    return NextResponse.json({ error: "INVALID_PAYLOAD" }, { status: 400 });
  }

  const upstream = await callUpstream({
    method: "POST",
    path: "/v1/auth/email/login",
    body: {
      ...(payload as Record<string, unknown>),
      platform: "web",
    },
    forwardedFor: req.headers.get("x-forwarded-for"),
    userAgent: req.headers.get("user-agent"),
  });

  if (upstream.status >= 400) {
    return NextResponse.json(upstream.body ?? { error: "UPSTREAM_ERROR" }, {
      status: upstream.status,
    });
  }

  if (!isAuthSession(upstream.body)) {
    return NextResponse.json({ error: "INVALID_UPSTREAM_RESPONSE" }, { status: 502 });
  }

  const session = upstream.body;
  const csrf = generateCsrfToken();

  const res = NextResponse.json({ user: session.user });
  res.cookies.set(COOKIE_NAMES.accessToken, session.accessToken, accessCookieOptions());
  res.cookies.set(COOKIE_NAMES.refreshToken, session.refreshToken, refreshCookieOptions());
  res.cookies.set(COOKIE_NAMES.csrf, csrf, csrfCookieOptions());
  return res;
}
