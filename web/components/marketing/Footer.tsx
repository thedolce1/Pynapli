import Link from "next/link";

const APP_URL = process.env.NEXT_PUBLIC_APP_URL ?? "https://web.pynapli.com";

interface Column {
  title: string;
  links: { label: string; href: string; external?: boolean }[];
}

const columns: Column[] = [
  {
    title: "Product",
    links: [
      { label: "How it works", href: "/#how" },
      { label: "Pricing", href: "/pricing" },
      { label: "Cities", href: "/cities/toronto" },
      { label: "For partners", href: "/#partners" },
    ],
  },
  {
    title: "Company",
    links: [
      { label: "About", href: "/about" },
      { label: "Careers", href: "/careers" },
      { label: "Press", href: "/press" },
      { label: "Contact", href: "/contact" },
    ],
  },
  {
    title: "Resources",
    links: [
      { label: "Community guidelines", href: "/community" },
      { label: "Safety center", href: "/community#safety" },
      { label: "Help / FAQ", href: "/#faq" },
      { label: "Status", href: "https://status.pynapli.com", external: true },
    ],
  },
  {
    title: "Legal",
    links: [
      { label: "Privacy policy", href: "/privacy" },
      { label: "Terms", href: "/terms" },
      { label: "Cookies", href: "/privacy#cookies" },
      { label: "Acceptable use", href: "/community" },
    ],
  },
  {
    title: "Get the app",
    links: [
      { label: "iOS (App Store)", href: "#", external: true },
      { label: "Android (Play)", href: "#", external: true },
      { label: "Open web app", href: APP_URL, external: true },
    ],
  },
];

export function Footer() {
  const year = new Date().getFullYear();
  return (
    <footer className="border-t border-border-default bg-black mt-24">
      <div className="mx-auto max-w-6xl px-6 py-16">
        <div className="grid grid-cols-2 md:grid-cols-5 gap-8">
          {columns.map((col) => (
            <div key={col.title} className="flex flex-col gap-3">
              <h4 className="text-[13px] font-semibold uppercase tracking-wider text-text-tertiary">
                {col.title}
              </h4>
              <ul className="flex flex-col gap-2">
                {col.links.map((link) => (
                  <li key={link.label}>
                    {link.external ? (
                      <a
                        href={link.href}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="text-[14px] text-text-secondary hover:text-text-primary transition-colors"
                      >
                        {link.label}
                      </a>
                    ) : (
                      <Link
                        href={link.href}
                        className="text-[14px] text-text-secondary hover:text-text-primary transition-colors"
                      >
                        {link.label}
                      </Link>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>

        <div className="mt-16 pt-8 border-t border-border-default flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
          <div className="flex items-center gap-4 text-[13px] text-text-tertiary">
            <span>© {year} Pynapli</span>
            <span className="hidden sm:inline">·</span>
            <span className="hidden sm:inline">Made in Toronto</span>
          </div>
          <div className="flex items-center gap-3 text-[12px] text-text-tertiary">
            <span className="inline-flex items-center h-6 px-2 rounded-[4px] border border-border-default text-text-secondary">
              18+
            </span>
            <span>Adults only</span>
          </div>
        </div>
      </div>
    </footer>
  );
}
