import { NextRequest, NextResponse } from "next/server";
import { COOKIE_NAMES } from "@/lib/auth/cookies";

const APP_HOSTS = new Set([
  "web.pynapli.com",
  "web.localhost:3000",
]);

function isAppHost(host: string | null): boolean {
  if (!host) return false;
  if (APP_HOSTS.has(host)) return true;
  // Vercel preview deploys sometimes use a single host; allow ?surface=app override
  return false;
}

const APP_PUBLIC_PATHS = new Set<string>([
  "/login",
  "/signup",
  "/verify",
  "/forgot",
]);

export function middleware(req: NextRequest) {
  const url = req.nextUrl;
  const host = req.headers.get("host");
  const onAppHost = isAppHost(host);

  // 1. Block indexing of the app surface entirely.
  if (onAppHost) {
    if (url.pathname === "/robots.txt") {
      return new NextResponse("User-agent: *\nDisallow: /\n", {
        status: 200,
        headers: { "content-type": "text/plain; charset=utf-8" },
      });
    }
  }

  // 2. Rewrite app-host paths into the (app)/(auth) groups.
  // The (marketing) and (app) route groups already share the same URLs, so
  // we only need to gate auth on the app surface, not rewrite paths.

  // 3. Auth gate for app-host routes.
  if (onAppHost) {
    // Bare host (`/`) on the app surface redirects into the app shell.
    if (url.pathname === "/") {
      const target = url.clone();
      target.pathname = req.cookies.has(COOKIE_NAMES.accessToken)
        ? "/messages"
        : "/login";
      return NextResponse.redirect(target);
    }

    const isPublic =
      APP_PUBLIC_PATHS.has(url.pathname) ||
      url.pathname.startsWith("/api/") ||
      url.pathname.startsWith("/_next/") ||
      url.pathname === "/favicon.ico" ||
      url.pathname.startsWith("/icons/");

    if (!isPublic) {
      const hasSession = req.cookies.has(COOKIE_NAMES.accessToken);
      if (!hasSession) {
        const redirect = url.clone();
        redirect.pathname = "/login";
        redirect.searchParams.set("next", url.pathname + url.search);
        return NextResponse.redirect(redirect);
      }
    }

    const res = NextResponse.next();
    res.headers.set("X-Robots-Tag", "noindex, nofollow");
    return res;
  }

  // 4. On the marketing host, leave everything alone.
  return NextResponse.next();
}

export const config = {
  matcher: [
    "/((?!_next/static|_next/image|favicon.ico).*)",
  ],
};
