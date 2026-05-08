"use client";

import { useEffect, useState } from "react";

const COOKIE_NAME = "pyn_age_ack";

function readCookie(name: string): string | null {
  if (typeof document === "undefined") return null;
  const match = document.cookie.match(new RegExp("(?:^|; )" + name + "=([^;]*)"));
  return match ? decodeURIComponent(match[1]) : null;
}

function writeCookie(name: string, value: string, days: number) {
  const max = days * 24 * 60 * 60;
  const secure = location.protocol === "https:" ? "; Secure" : "";
  document.cookie = `${name}=${encodeURIComponent(value)}; Max-Age=${max}; Path=/; SameSite=Lax${secure}`;
}

export function AgeGate() {
  const [ack, setAck] = useState<boolean | null>(null);

  useEffect(() => {
    setAck(readCookie(COOKIE_NAME) === "1");
  }, []);

  if (ack === null || ack === true) return null;

  function confirm() {
    writeCookie(COOKIE_NAME, "1", 180);
    setAck(true);
  }

  function leave() {
    window.location.href = "https://www.google.com";
  }

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="age-gate-title"
      className="fixed inset-0 z-[60] bg-black/80 backdrop-blur-sm flex items-end sm:items-center justify-center p-0 sm:p-6"
    >
      <div className="w-full sm:max-w-md bg-surface border border-border-default rounded-t-[16px] sm:rounded-[16px] p-6 sm:p-8">
        <h2 id="age-gate-title" className="text-[22px] font-semibold tracking-tight">
          Adults only
        </h2>
        <p className="mt-3 text-[14px] text-text-secondary leading-relaxed">
          Pynapli is for people 18 and older. By continuing, you confirm you are
          at least 18 and agree to view content intended for adults.
        </p>
        <div className="mt-6 flex flex-col sm:flex-row gap-3">
          <button
            type="button"
            onClick={confirm}
            className="h-12 px-5 rounded-[8px] bg-accent text-text-inverse font-medium hover:bg-[#FFD24A] transition-colors flex-1"
          >
            I'm 18 or older — continue
          </button>
          <button
            type="button"
            onClick={leave}
            className="h-12 px-5 rounded-[8px] bg-surface-raised border border-border-default text-text-primary font-medium hover:bg-surface-pressed transition-colors"
          >
            Leave
          </button>
        </div>
      </div>
    </div>
  );
}
