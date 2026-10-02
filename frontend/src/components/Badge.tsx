import type { ReactNode } from 'react';

interface BadgeProps {
  children: ReactNode;
  tone?: 'accent' | 'ink' | 'faint';
}

const tones: Record<string, string> = {
  accent: 'bg-accent text-white',
  ink: 'bg-ink text-paper',
  faint: 'border border-edge text-muted',
};

export default function Badge({ children, tone = 'accent' }: BadgeProps) {
  return (
    <span className={`font-mono-label inline-block px-2 py-1 text-[10px] ${tones[tone]}`}>
      {children}
    </span>
  );
}
