/**
 * Cookie names + configuration for the BFF auth pattern.
 *
 * pyn_at   — opaque access token. HttpOnly. ~15 minute lifetime.
 * pyn_rt   — opaque refresh token. HttpOnly. ~30 days. Path-scoped to /api/auth.
 * pyn_csrf — CSRF token. JS-readable. Double-submit pattern; the server-side
 *            BFF compares this cookie value against the X-CSRF-Token header
 *            on every non-GET. SameSite=Lax already blocks most cross-site
 *            POSTs; the double-submit covers the rest.
 */

import type { ResponseCookie } from "next/dist/compiled/@edge-runtime/cookies";

export const COOKIE_NAMES = {
  accessToken: "pyn_at",
  refreshToken: "pyn_rt",
  csrf: "pyn_csrf",
} as const;

const ACCESS_TOKEN_MAX_AGE_SECONDS = 15 * 60;
const REFRESH_TOKEN_MAX_AGE_SECONDS = 30 * 24 * 60 * 60;
const CSRF_MAX_AGE_SECONDS = REFRESH_TOKEN_MAX_AGE_SECONDS;

const isProd = process.env.NODE_ENV === "production";
const cookieDomain = process.env.PYNAPLI_COOKIE_DOMAIN || undefined;

export function accessCookieOptions(): Partial<ResponseCookie> {
  return {
    httpOnly: true,
    secure: isProd,
    sameSite: "lax",
    path: "/",
    domain: cookieDomain,
    maxAge: ACCESS_TOKEN_MAX_AGE_SECONDS,
  };
}

export function refreshCookieOptions(): Partial<ResponseCookie> {
  return {
    httpOnly: true,
    secure: isProd,
    sameSite: "lax",
    path: "/api/auth",
    domain: cookieDomain,
    maxAge: REFRESH_TOKEN_MAX_AGE_SECONDS,
  };
}

export function csrfCookieOptions(): Partial<ResponseCookie> {
  return {
    httpOnly: false,
    secure: isProd,
    sameSite: "lax",
    path: "/",
    domain: cookieDomain,
    maxAge: CSRF_MAX_AGE_SECONDS,
  };
}

export function clearAccessCookieOptions(): Partial<ResponseCookie> {
  return { ...accessCookieOptions(), maxAge: 0 };
}

export function clearRefreshCookieOptions(): Partial<ResponseCookie> {
  return { ...refreshCookieOptions(), maxAge: 0 };
}

export function clearCsrfCookieOptions(): Partial<ResponseCookie> {
  return { ...csrfCookieOptions(), maxAge: 0 };
}
