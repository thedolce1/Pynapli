import type { Metadata, Viewport } from "next";
import "./globals.css";

const SITE_URL = process.env.NEXT_PUBLIC_SITE_URL ?? "https://pynapli.com";

export const metadata: Metadata = {
  metadataBase: new URL(SITE_URL),
  title: {
    default: "Pynapli — A social app built for adults who want autonomy",
    template: "%s · Pynapli",
  },
  description:
    "A social app for open-minded adults — singles, couples, and partnered people. Solo, together, or socially, with control on both sides.",
  applicationName: "Pynapli",
  authors: [{ name: "Pynapli" }],
  openGraph: {
    type: "website",
    siteName: "Pynapli",
    url: SITE_URL,
    title: "Pynapli",
    description:
      "A social app for open-minded adults — singles, couples, and partnered people.",
  },
  twitter: {
    card: "summary_large_image",
    title: "Pynapli",
    description:
      "A social app for open-minded adults — singles, couples, and partnered people.",
  },
  robots: {
    index: true,
    follow: true,
  },
};

export const viewport: Viewport = {
  themeColor: "#050505",
  colorScheme: "dark",
  width: "device-width",
  initialScale: 1,
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className="dark">
      <body className="bg-black text-text-primary min-h-dvh">{children}</body>
    </html>
  );
}
