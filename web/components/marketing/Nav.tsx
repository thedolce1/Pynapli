import Link from "next/link";

const APP_URL = process.env.NEXT_PUBLIC_APP_URL ?? "https://web.pynapli.com";

export function Nav() {
  return (
    <header className="sticky top-0 z-40 w-full backdrop-blur-md bg-black/70 border-b border-border-default">
      <nav className="mx-auto max-w-6xl px-6 h-16 flex items-center justify-between">
        <Link
          href="/"
          className="text-[18px] font-semibold tracking-tight text-text-primary"
        >
          Pynapli
        </Link>
        <div className="hidden md:flex items-center gap-8 text-[14px] text-text-secondary">
          <Link href="/#how" className="hover:text-text-primary transition-colors">
            How it works
          </Link>
          <Link href="/pricing" className="hover:text-text-primary transition-colors">
            Pricing
          </Link>
          <Link href="/community" className="hover:text-text-primary transition-colors">
            Community
          </Link>
          <Link href="/about" className="hover:text-text-primary transition-colors">
            About
          </Link>
        </div>
        <div className="flex items-center gap-2">
          <Link
            href={`${APP_URL}/login`}
            className="hidden sm:inline-flex items-center h-10 px-4 text-[14px] text-text-secondary hover:text-text-primary transition-colors"
          >
            Sign in
          </Link>
          <Link
            href={`${APP_URL}/signup`}
            className="inline-flex items-center h-10 px-4 rounded-[8px] bg-accent text-text-inverse text-[14px] font-medium hover:bg-[#FFD24A] transition-colors"
          >
            Create account
          </Link>
        </div>
      </nav>
    </header>
  );
}
