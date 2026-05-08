export const metadata = {
  title: "Messages",
  robots: { index: false, follow: false },
};

export default function MessagesPage() {
  return (
    <div className="mx-auto max-w-3xl px-6 py-16">
      <h1 className="text-[28px] font-semibold tracking-tight">Messages</h1>
      <p className="mt-3 text-[15px] text-text-secondary leading-relaxed">
        Your inbox lives here. Conversations sync with the mobile app — start a
        chat from Grid on your phone and pick it up at your desk.
      </p>
      <div className="mt-10 rounded-[8px] border border-border-default bg-surface p-8 text-center">
        <p className="text-[14px] text-text-secondary">
          Inbox is wiring up next. For now, your account is signed in.
        </p>
      </div>
    </div>
  );
}
