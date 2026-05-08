import * as React from "react";

interface InputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  label?: string;
  error?: string;
}

export const Input = React.forwardRef<HTMLInputElement, InputProps>(
  function Input({ label, error, id, className = "", ...props }, ref) {
    const inputId = id ?? React.useId();
    return (
      <div className="flex flex-col gap-1.5">
        {label ? (
          <label
            htmlFor={inputId}
            className="text-[13px] font-medium text-text-secondary"
          >
            {label}
          </label>
        ) : null}
        <input
          ref={ref}
          id={inputId}
          className={`h-12 px-4 rounded-[8px] bg-surface-raised border border-border-default text-text-primary placeholder:text-text-tertiary focus:outline-none focus:border-accent transition-colors duration-150 ${className}`}
          aria-invalid={error ? "true" : undefined}
          {...props}
        />
        {error ? <p className="text-[13px] text-danger">{error}</p> : null}
      </div>
    );
  }
);
