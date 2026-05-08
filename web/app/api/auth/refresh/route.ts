import { NextRequest, NextResponse } from "next/server";
import {
  COOKIE_NAMES,
  accessCookieOptions,
  refreshCookieOptions,
} from "@/lib/auth/cookies";
import { callUpstream, isAuthSession } from "@/lib/api/upstream";

export const runtime = "nodejs";

export async function POST(req: NextRequest) {
  const refresh = req.cookies.get(COOKIE_NAMES.refreshToken)?.value;
  if (!refresh) {
    return NextResponse.json({ error: "NO_REFRESH_TOKEN" }, { status: 401 });
  }

  const upstream = await callUpstream({
    method: "POST",
    path: "/v1/auth/refresh",
    body: { refreshToken: refresh },
    forwardedFor: req.headers.get("x-forwarded-for"),
    userAgent: req.headers.get("user-agent"),
  });

  if (upstream.status >= 400 || !isAuthSession(upstream.body)) {
    const fail = NextResponse.json(upstream.body ?? { error: "REFRESH_FAILED" }, {
      status: upstream.status >= 400 ? upstream.status : 502,
    });
    fail.cookies.set(COOKIE_NAMES.accessToken, "", { ...accessCookieOptions(), maxAge: 0 });
    fail.cookies.set(COOKIE_NAMES.refreshToken, "", { ...refreshCookieOptions(), maxAge: 0 });
    return fail;
  }

  const session = upstream.body;
  const res = NextResponse.json({ user: session.user });
  res.cookies.set(COOKIE_NAMES.accessToken, session.accessToken, accessCookieOptions());
  res.cookies.set(COOKIE_NAMES.refreshToken, session.refreshToken, refreshCookieOptions());
  return res;
}
