import { NextRequest, NextResponse } from "next/server";
import {
  COOKIE_NAMES,
  clearAccessCookieOptions,
  clearCsrfCookieOptions,
  clearRefreshCookieOptions,
} from "@/lib/auth/cookies";
import { callUpstream } from "@/lib/api/upstream";

export const runtime = "nodejs";

export async function POST(req: NextRequest) {
  const access = req.cookies.get(COOKIE_NAMES.accessToken)?.value;

  if (access) {
    await callUpstream({
      method: "POST",
      path: "/v1/auth/logout",
      bearer: access,
      forwardedFor: req.headers.get("x-forwarded-for"),
      userAgent: req.headers.get("user-agent"),
    }).catch(() => null);
  }

  const res = NextResponse.json({ loggedOut: true });
  res.cookies.set(COOKIE_NAMES.accessToken, "", clearAccessCookieOptions());
  res.cookies.set(COOKIE_NAMES.refreshToken, "", clearRefreshCookieOptions());
  res.cookies.set(COOKIE_NAMES.csrf, "", clearCsrfCookieOptions());
  return res;
}
