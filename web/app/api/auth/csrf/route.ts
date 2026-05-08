import { NextRequest, NextResponse } from "next/server";
import { COOKIE_NAMES, csrfCookieOptions } from "@/lib/auth/cookies";
import { generateCsrfToken } from "@/lib/auth/csrf";

export const runtime = "nodejs";

/**
 * Issues a fresh CSRF token cookie. Useful before a mutation when the page
 * was rendered before the user had a session, or when the cookie has been
 * cleared. The double-submit pattern needs a JS-readable token regardless.
 */
export async function GET(req: NextRequest) {
  const existing = req.cookies.get(COOKIE_NAMES.csrf)?.value;
  if (existing) {
    return NextResponse.json({ token: existing });
  }
  const token = generateCsrfToken();
  const res = NextResponse.json({ token });
  res.cookies.set(COOKIE_NAMES.csrf, token, csrfCookieOptions());
  return res;
}
