import Link from "next/link";

export default function AppLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="min-h-dvh flex flex-col">
      <header className="border-b border-border-default sticky top-0 bg-black/80 backdrop-blur-md z-30">
        <div className="mx-auto max-w-6xl px-6 h-16 flex items-center justify-between">
          <Link
            href="/messages"
            className="text-[18px] font-semibold tracking-tight text-text-primary"
          >
            Pynapli
          </Link>
          <nav className="flex items-center gap-1 text-[14px]">
            <NavItem href="/messages" label="Messages" />
            <NavItem href="/profile" label="Profile" />
            <form action="/api/auth/logout" method="post" className="ml-2">
              <button
                type="submit"
                className="h-9 px-3 rounded-[8px] text-text-secondary hover:bg-surface-raised hover:text-text-primary transition-colors"
              >
                Sign out
              </button>
            </form>
          </nav>
        </div>
      </header>
      <main className="flex-1">{children}</main>
    </div>
  );
}

function NavItem({ href, label }: { href: string; label: string }) {
  return (
    <Link
      href={href}
      className="h-9 px-3 inline-flex items-center rounded-[8px] text-text-secondary hover:bg-surface-raised hover:text-text-primary transition-colors"
    >
      {label}
    </Link>
  );
}
