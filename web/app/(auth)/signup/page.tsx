"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";

export default function SignupPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [ageConfirmed, setAgeConfirmed] = useState(false);
  const [termsAccepted, setTermsAccepted] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);

    if (!ageConfirmed) {
      setError("You must confirm you're 18 or older to continue.");
      return;
    }
    if (!termsAccepted) {
      setError("Please accept the terms and privacy policy.");
      return;
    }
    if (password.length < 10) {
      setError("Use a password that's at least 10 characters.");
      return;
    }

    setSubmitting(true);
    try {
      const res = await fetch("/api/auth/signup", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email, password, ageConfirmed, termsAccepted }),
      });

      if (!res.ok) {
        const body = (await res.json().catch(() => null)) as { code?: string } | null;
        setError(translate(body?.code) ?? "Couldn't create your account. Try again.");
        return;
      }

      router.replace("/");
      router.refresh();
    } catch {
      setError("Network error. Try again.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div>
      <h1 className="text-[28px] font-semibold tracking-tight">Create your account</h1>
      <p className="mt-2 text-[14px] text-text-secondary">
        Pynapli is for adults 18+. You'll finish your profile after signing up.
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
          autoComplete="new-password"
          required
          minLength={10}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />

        <label className="flex items-start gap-3 mt-2 cursor-pointer">
          <input
            type="checkbox"
            checked={ageConfirmed}
            onChange={(e) => setAgeConfirmed(e.target.checked)}
            className="mt-0.5 w-4 h-4 accent-accent"
          />
          <span className="text-[13px] text-text-secondary leading-relaxed">
            I confirm I am at least 18 years old.
          </span>
        </label>

        <label className="flex items-start gap-3 cursor-pointer">
          <input
            type="checkbox"
            checked={termsAccepted}
            onChange={(e) => setTermsAccepted(e.target.checked)}
            className="mt-0.5 w-4 h-4 accent-accent"
          />
          <span className="text-[13px] text-text-secondary leading-relaxed">
            I agree to the{" "}
            <Link href="/terms" className="text-text-primary hover:underline">
              Terms
            </Link>
            ,{" "}
            <Link href="/privacy" className="text-text-primary hover:underline">
              Privacy Policy
            </Link>
            , and{" "}
            <Link href="/community" className="text-text-primary hover:underline">
              Community Guidelines
            </Link>
            .
          </span>
        </label>

        {error ? <p className="text-[13px] text-danger">{error}</p> : null}

        <Button type="submit" disabled={submitting}>
          {submitting ? "Creating account…" : "Create account"}
        </Button>
      </form>

      <div className="mt-6 text-center text-[13px] text-text-secondary">
        Already have an account?{" "}
        <Link href="/login" className="text-text-primary hover:underline">
          Sign in
        </Link>
      </div>
    </div>
  );
}

function translate(code: string | undefined): string | null {
  switch (code) {
    case "EMAIL_ALREADY_EXISTS":
      return "If that email already has an account, check your inbox to sign in.";
    case "INVALID_EMAIL":
      return "That doesn't look like a valid email.";
    case "PASSWORD_TOO_SHORT":
      return "Use a password that's at least 10 characters.";
    case "AGE_AND_TERMS_REQUIRED":
      return "Please confirm your age and accept the terms.";
    default:
      return null;
  }
}
