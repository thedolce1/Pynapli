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

interface SignupPayload {
  email: string;
  password: string;
  ageConfirmed: boolean;
  termsAccepted: boolean;
}

function isSignupPayload(value: unknown): value is SignupPayload {
  if (typeof value !== "object" || value === null) return false;
  const v = value as Record<string, unknown>;
  return (
    typeof v.email === "string" &&
    typeof v.password === "string" &&
    v.ageConfirmed === true &&
    v.termsAccepted === true
  );
}

export async function POST(req: NextRequest) {
  let payload: unknown;
  try {
    payload = await req.json();
  } catch {
    return NextResponse.json({ error: "INVALID_JSON" }, { status: 400 });
  }

  if (!isSignupPayload(payload)) {
    return NextResponse.json(
      { error: "AGE_AND_TERMS_REQUIRED" },
      { status: 400 }
    );
  }

  const upstream = await callUpstream({
    method: "POST",
    path: "/v1/auth/email/signup",
    body: {
      email: payload.email,
      password: payload.password,
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

  const res = NextResponse.json({ user: session.user }, { status: 201 });
  res.cookies.set(COOKIE_NAMES.accessToken, session.accessToken, accessCookieOptions());
  res.cookies.set(COOKIE_NAMES.refreshToken, session.refreshToken, refreshCookieOptions());
  res.cookies.set(COOKIE_NAMES.csrf, csrf, csrfCookieOptions());
  return res;
}
