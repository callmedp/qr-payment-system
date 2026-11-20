import type { ReactNode } from 'react';
import type { PaymentStatus, QrStatus } from '../api/types';
import type { StreamStatus } from '../hooks/usePaymentStream';
import { useNow } from '../hooks/useNow';

export function Card({ title, actions, children, className = '' }: {
  title?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={`card ${className}`}>
      {(title || actions) && (
        <header className="card-head">
          {title && <h2>{title}</h2>}
          {actions && <div className="card-actions">{actions}</div>}
        </header>
      )}
      {children}
    </section>
  );
}

const STATUS_TONE: Record<PaymentStatus | QrStatus, string> = {
  CREATED: 'warn',
  ACTIVE: 'info',
  SUCCESS: 'ok',
  PAID: 'ok',
  FAILED: 'bad',
  EXPIRED: 'muted',
  CANCELLED: 'muted',
  DISABLED: 'muted',
};

export function StatusBadge({ status }: { status: PaymentStatus | QrStatus }) {
  return <span className={`badge badge-${STATUS_TONE[status]}`}>{status}</span>;
}

export function StreamPill({ status }: { status: StreamStatus }) {
  const label = status === 'live' ? 'Live' : status === 'connecting' ? 'Connecting…' : 'Offline (polling)';
  return (
    <span className={`pill pill-${status}`} title="Real-time payment stream (SSE)">
      <span className="dot" /> {label}
    </span>
  );
}

export function ErrorBanner({ message, onClose }: { message: string | null; onClose?: () => void }) {
  if (!message) return null;
  return (
    <div className="banner banner-error" role="alert">
      <span>{message}</span>
      {onClose && (
        <button className="link" onClick={onClose} aria-label="Dismiss">
          ✕
        </button>
      )}
    </div>
  );
}

export function Countdown({ until }: { until: string }) {
  const now = useNow(500);
  const ms = new Date(until).getTime() - now;
  if (ms <= 0) {
    return <span className="countdown expired">expired</span>;
  }
  const total = Math.ceil(ms / 1000);
  const m = Math.floor(total / 60);
  const s = String(total % 60).padStart(2, '0');
  return <span className={`countdown ${total <= 30 ? 'urgent' : ''}`}>{m}:{s}</span>;
}

export function Empty({ children }: { children: ReactNode }) {
  return <p className="empty">{children}</p>;
}

export function Spinner() {
  return <span className="spinner" aria-label="Loading" />;
}
