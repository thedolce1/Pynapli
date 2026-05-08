import * as React from "react";

type Variant = "primary" | "secondary" | "ghost";
type Size = "md" | "sm";

interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
}

const base =
  "inline-flex items-center justify-center font-medium rounded-[8px] transition-colors duration-150 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent focus-visible:ring-offset-2 focus-visible:ring-offset-black disabled:opacity-50 disabled:cursor-not-allowed";

const variants: Record<Variant, string> = {
  primary: "bg-accent text-text-inverse hover:bg-[#FFD24A] active:bg-accent-dark",
  secondary:
    "bg-surface-raised text-text-primary border border-border-default hover:bg-surface-pressed",
  ghost: "bg-transparent text-text-primary hover:bg-surface-raised",
};

const sizes: Record<Size, string> = {
  md: "h-12 px-5 text-[15px]",
  sm: "h-10 px-4 text-[14px]",
};

export const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  function Button({ variant = "primary", size = "md", className = "", ...props }, ref) {
    return (
      <button
        ref={ref}
        className={`${base} ${variants[variant]} ${sizes[size]} ${className}`}
        {...props}
      />
    );
  }
);
