import Link from "next/link";

const APP_URL = process.env.NEXT_PUBLIC_APP_URL ?? "https://web.pynapli.com";

export default function HomePage() {
  return (
    <>
      <section className="mx-auto max-w-6xl px-6 pt-24 pb-32">
        <div className="max-w-3xl">
          <span className="inline-flex items-center h-6 px-2 rounded-[4px] border border-border-default text-[12px] text-text-tertiary uppercase tracking-wider">
            Now in Toronto & GTA
          </span>
          <h1 className="mt-6 text-[clamp(40px,7vw,72px)] font-semibold tracking-tight leading-[1.05]">
            A social app built for adults who want autonomy.
          </h1>
          <p className="mt-6 text-[18px] text-text-secondary leading-relaxed max-w-2xl">
            For singles, couples, and partnered people. Solo, together, or
            socially — with control on both sides. Two accounts, one link, zero
            merging.
          </p>
          <div className="mt-10 flex flex-col sm:flex-row gap-3">
            <Link
              href={`${APP_URL}/signup`}
              className="inline-flex items-center justify-center h-12 px-6 rounded-[8px] bg-accent text-text-inverse font-medium hover:bg-[#FFD24A] transition-colors"
            >
              Create your account
            </Link>
            <Link
              href="#how"
              className="inline-flex items-center justify-center h-12 px-6 rounded-[8px] bg-surface-raised border border-border-default text-text-primary hover:bg-surface-pressed transition-colors"
            >
              See how it works
            </Link>
          </div>
        </div>
      </section>

      <section
        id="how"
        className="mx-auto max-w-6xl px-6 py-24 border-t border-border-default"
      >
        <h2 className="text-[clamp(28px,4vw,40px)] font-semibold tracking-tight">
          Three modes. One account.
        </h2>
        <p className="mt-4 text-[16px] text-text-secondary max-w-2xl">
          Pynapli works the way real lifestyle communities already work — every
          adult has their own voice, even when partnered.
        </p>
        <div className="mt-12 grid grid-cols-1 md:grid-cols-3 gap-4">
          <FeatureCard
            kicker="Grid"
            title="For now."
            body="See who's nearby and relevant. Fast, dense, low-friction. Message without matching, on your terms."
          />
          <FeatureCard
            kicker="Swipe"
            title="For intent."
            body="Curated discovery for when you want to be deliberate. Mutual matches unlock chat — fewer signals, sharper ones."
          />
          <FeatureCard
            kicker="Links"
            title="For trust."
            body="Mutual, revocable connections between two autonomous accounts. No couple cards. No silent additions to chats."
          />
        </div>
      </section>

      <section
        id="partners"
        className="mx-auto max-w-6xl px-6 py-24 border-t border-border-default"
      >
        <div className="grid md:grid-cols-2 gap-12 items-start">
          <div>
            <h2 className="text-[clamp(28px,4vw,40px)] font-semibold tracking-tight">
              Two accounts. One link. Zero merging.
            </h2>
            <p className="mt-4 text-[16px] text-text-secondary leading-relaxed">
              Partner linking is a small visible edge between two real people —
              not a shared profile. Each of you keeps your own chats, photos,
              filters, blocks, and identity. Linking is mutual and revocable.
            </p>
          </div>
          <ul className="flex flex-col gap-3">
            <Bullet>Each account stays autonomous — no merged inbox, no shared photos.</Bullet>
            <Bullet>Your linked partner appears as a small icon on your card.</Bullet>
            <Bullet>One paid subscription extends the same tier to your linked partner.</Bullet>
            <Bullet>Unlinking is instant and quiet.</Bullet>
          </ul>
        </div>
      </section>

      <section className="mx-auto max-w-6xl px-6 py-24 border-t border-border-default">
        <h2 className="text-[clamp(28px,4vw,40px)] font-semibold tracking-tight">
          Privacy you can audit.
        </h2>
        <div className="mt-10 grid grid-cols-1 md:grid-cols-2 gap-4">
          <PrivacyCard title="Your location is yours.">
            Stored server-side, never echoed back to clients. Distance is what
            others see — never coordinates.
          </PrivacyCard>
          <PrivacyCard title="Albums are per-grant.">
            Private albums are locked by default. You grant access per person or
            per chat, with optional expiration, revocable any time.
          </PrivacyCard>
          <PrivacyCard title="No silent partner adds.">
            Linked partners never get added to a chat without explicit consent
            from everyone in it.
          </PrivacyCard>
          <PrivacyCard title="Sessions you control.">
            Active sessions list. Remote logout per device. Opaque tokens —
            nothing useful sits in your browser storage.
          </PrivacyCard>
        </div>
      </section>

      <section
        id="faq"
        className="mx-auto max-w-6xl px-6 py-24 border-t border-border-default"
      >
        <h2 className="text-[clamp(28px,4vw,40px)] font-semibold tracking-tight">
          Frequently asked.
        </h2>
        <div className="mt-10 grid grid-cols-1 md:grid-cols-2 gap-x-12 gap-y-10">
          <Faq q="Who is Pynapli for?">
            Open-minded adults — singles and partnered people who want autonomy
            in how they meet, date, and socialize. 18+ only.
          </Faq>
          <Faq q="Do my partner and I share an account?">
            No. You each keep your own account. Partner linking is a connection
            between two autonomous accounts, not a merged profile.
          </Faq>
          <Faq q="What does it cost?">
            Pynapli is free to use. Social ($4.99/mo) and Select ($9.99/mo) add
            more discovery, more privacy controls, and faster reach. One paid
            subscription covers your linked partner.
          </Faq>
          <Faq q="Do you sell my data?">
            No. We make money from subscriptions, not your data. Read the
            privacy policy for the full breakdown.
          </Faq>
          <Faq q="Where can I use Pynapli?">
            We're starting in Toronto and the GTA, and rolling out city by city.
            Add your city to the waitlist and we'll let you know.
          </Faq>
          <Faq q="Can I use Pynapli on my laptop?">
            Yes. Sign in at web.pynapli.com — your account works the same on
            mobile and on web.
          </Faq>
        </div>
      </section>

      <section className="mx-auto max-w-6xl px-6 py-24 border-t border-border-default">
        <div className="flex flex-col items-start gap-6">
          <h2 className="text-[clamp(28px,4vw,40px)] font-semibold tracking-tight max-w-2xl">
            Ready when you are.
          </h2>
          <Link
            href={`${APP_URL}/signup`}
            className="inline-flex items-center justify-center h-12 px-6 rounded-[8px] bg-accent text-text-inverse font-medium hover:bg-[#FFD24A] transition-colors"
          >
            Create your account
          </Link>
        </div>
      </section>
    </>
  );
}

function FeatureCard({
  kicker,
  title,
  body,
}: {
  kicker: string;
  title: string;
  body: string;
}) {
  return (
    <div className="rounded-[8px] bg-surface border border-border-default p-6">
      <div className="text-[12px] uppercase tracking-wider text-accent">{kicker}</div>
      <div className="mt-2 text-[22px] font-semibold tracking-tight">{title}</div>
      <p className="mt-3 text-[14px] text-text-secondary leading-relaxed">{body}</p>
    </div>
  );
}

function PrivacyCard({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="rounded-[8px] bg-surface border border-border-default p-6">
      <h3 className="text-[18px] font-semibold tracking-tight">{title}</h3>
      <p className="mt-2 text-[14px] text-text-secondary leading-relaxed">{children}</p>
    </div>
  );
}

function Faq({ q, children }: { q: string; children: React.ReactNode }) {
  return (
    <div>
      <h3 className="text-[16px] font-semibold text-text-primary">{q}</h3>
      <p className="mt-2 text-[14px] text-text-secondary leading-relaxed">{children}</p>
    </div>
  );
}

function Bullet({ children }: { children: React.ReactNode }) {
  return (
    <li className="flex items-start gap-3 text-[14px] text-text-secondary">
      <span
        aria-hidden
        className="mt-2 inline-block w-1.5 h-1.5 rounded-full bg-accent flex-shrink-0"
      />
      <span>{children}</span>
    </li>
  );
}
