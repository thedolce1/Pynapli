export const metadata = {
  title: "Profile",
  robots: { index: false, follow: false },
};

export default function ProfilePage() {
  return (
    <div className="mx-auto max-w-3xl px-6 py-16">
      <h1 className="text-[28px] font-semibold tracking-tight">Your profile</h1>
      <p className="mt-3 text-[15px] text-text-secondary leading-relaxed">
        Profile editing on web ships in the next phase. Use the mobile app to
        update photos, prompts, and tags for now — changes sync back here.
      </p>
    </div>
  );
}
