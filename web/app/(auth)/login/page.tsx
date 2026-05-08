"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";

export default function LoginPage() {
  const router = useRouter();
  const search = useSearchParams();
  const next = search.get("next") || "/";

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);

    try {
      const res = await fetch("/api/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email, password }),
      });

      if (!res.ok) {
        const body = (await res.json().catch(() => null)) as { code?: string } | null;
        setError(translate(body?.code) ?? "Couldn't sign you in. Check your email and password.");
        return;
      }

      router.replace(next);
      router.refresh();
    } catch {
      setError("Network error. Try again.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div>
      <h1 className="text-[28px] font-semibold tracking-tight">Sign in</h1>
      <p className="mt-2 text-[14px] text-text-secondary">
        Welcome back. Use the same email you signed up with on mobile.
      </p>

      <form onSubmit={onSubmit} className="mt-8 flex flex-col gap-4">
        <Input
          type="email"
          name="email"
          label="Email"
          autoComplete="email"
          required
          value={email}
          onChange={(e) => setEmail(e.target.value)}
        />
        <Input
          type="password"
          name="password"
          label="Password"
          autoComplete="current-password"
          required
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        {error ? <p className="text-[13px] text-danger">{error}</p> : null}
        <Button type="submit" disabled={submitting}>
          {submitting ? "Signing in…" : "Sign in"}
        </Button>
      </form>

      <div className="mt-6 flex items-center justify-between text-[13px] text-text-secondary">
        <Link href="/forgot" className="hover:text-text-primary transition-colors">
          Forgot password
        </Link>
        <Link href="/signup" className="hover:text-text-primary transition-colors">
          Create an account
        </Link>
      </div>
    </div>
  );
}

function translate(code: string | undefined): string | null {
  switch (code) {
    case "INVALID_CREDENTIALS":
      return "That email and password don't match.";
    case "ACCOUNT_NOT_ACTIVE":
      return "This account is not active. Reach out to support.";
    case "INVALID_EMAIL":
      return "That doesn't look like a valid email.";
    default:
      return null;
  }
}
