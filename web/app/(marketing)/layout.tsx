import { Nav } from "@/components/marketing/Nav";
import { Footer } from "@/components/marketing/Footer";
import { AgeGate } from "@/components/marketing/AgeGate";

export default function MarketingLayout({ children }: { children: React.ReactNode }) {
  return (
    <>
      <Nav />
      <main className="min-h-[calc(100dvh-64px)]">{children}</main>
      <Footer />
      <AgeGate />
    </>
  );
}
