import Link from "next/link";

export default function AuthLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="min-h-dvh flex flex-col">
      <header className="border-b border-border-default">
        <div className="mx-auto max-w-6xl px-6 h-16 flex items-center">
          <Link
            href="/"
            className="text-[18px] font-semibold tracking-tight text-text-primary"
          >
            Pynapli
          </Link>
        </div>
      </header>
      <main className="flex-1 flex items-start sm:items-center justify-center px-6 py-12">
        <div className="w-full max-w-[420px]">{children}</div>
      </main>
    </div>
  );
}
