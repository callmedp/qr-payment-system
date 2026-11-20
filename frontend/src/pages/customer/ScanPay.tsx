import { lazy, Suspense, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError, errorMessage, newIdempotencyKey } from '../../api/client';
import { Api } from '../../api/endpoints';
import type { PaymentView, ResolvedQr } from '../../api/types';
import { Card, Countdown, ErrorBanner, Spinner } from '../../components/ui';
import { formatDateTime, formatMoney, toMinor } from '../../utils/money';

// html5-qrcode is ~370 kB; load it only when the customer actually opens the scanner
const QrScanner = lazy(() => import('../../components/QrScanner').then((m) => ({ default: m.QrScanner })));

type Step =
  | { kind: 'scan' }
  | { kind: 'review'; qr: ResolvedQr }
  | { kind: 'authorize'; qr: ResolvedQr; payment: PaymentView }
  | { kind: 'result'; payment: PaymentView };

const STEPS = ['Scan', 'Review', 'Authorize', 'Done'];
const STEP_INDEX: Record<Step['kind'], number> = { scan: 0, review: 1, authorize: 2, result: 3 };

const FAILURE_TEXT: Record<string, string> = {
  INSUFFICIENT_BALANCE: 'Not enough balance in your wallet.',
  QR_ALREADY_PAID: 'Someone else already paid this QR code.',
  QR_EXPIRED: 'The QR code expired before the payment went through.',
  QR_INACTIVE: 'This merchant is not accepting payments right now.',
  PAYMENT_EXPIRED: 'The payment session timed out.',
};

export default function ScanPay() {
  const [step, setStep] = useState<Step>({ kind: 'scan' });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [amountText, setAmountText] = useState('');
  const [note, setNote] = useState('');
  const [pin, setPin] = useState('');
  // One key per logical action: a retry after a network blip re-sends the SAME key, so the server
  // replays the first result instead of creating a second payment.
  const initiateKey = useRef(newIdempotencyKey());
  const confirmKey = useRef(newIdempotencyKey());

  const run = async (action: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await action();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const onDecoded = (payload: string) =>
    run(async () => {
      const qr = await Api.resolveQr(payload);
      initiateKey.current = newIdempotencyKey();
      setAmountText('');
      setNote('');
      setStep({ kind: 'review', qr });
    });

  const proceed = (qr: ResolvedQr) =>
    run(async () => {
      let amount: number | undefined;
      if (qr.type === 'STATIC') {
        const minor = toMinor(amountText);
        if (!minor) throw new Error('Enter a valid amount, e.g. 120 or 99.50');
        amount = minor;
      }
      const payment = await Api.initiatePayment({ qrId: qr.qrId, amount, note: note.trim() || undefined }, initiateKey.current);
      confirmKey.current = newIdempotencyKey();
      setPin('');
      setStep({ kind: 'authorize', qr, payment });
    });

  const confirm = (payment: PaymentView) =>
    run(async () => {
      try {
        const result = await Api.confirmPayment(payment.id, pin, confirmKey.current);
        setStep({ kind: 'result', payment: result });
      } catch (e) {
        setPin('');
        if (e instanceof ApiError && ['PAYMENT_INVALID_STATE', 'PAYMENT_EXPIRED'].includes(e.code)) {
          setStep({ kind: 'result', payment: await Api.payment(payment.id) });
          return;
        }
        throw e;
      }
    });

  const cancel = (payment: PaymentView) =>
    run(async () => {
      await Api.cancelPayment(payment.id);
      setStep({ kind: 'scan' });
    });

  return (
    <div className="page narrow">
      <ol className="stepper">
        {STEPS.map((label, i) => (
          <li key={label} className={i < STEP_INDEX[step.kind] ? 'done' : i === STEP_INDEX[step.kind] ? 'current' : ''}>
            <span>{i + 1}</span> {label}
          </li>
        ))}
      </ol>
      <ErrorBanner message={error} onClose={() => setError(null)} />

      {step.kind === 'scan' && (
        <Card title="Scan a merchant QR">
          {busy ? (
            <p className="center">
              <Spinner /> Verifying QR…
            </p>
          ) : (
            <Suspense fallback={<p className="center"><Spinner /> Loading scanner…</p>}>
              <QrScanner onDecoded={(t) => void onDecoded(t)} />
            </Suspense>
          )}
        </Card>
      )}

      {step.kind === 'review' && (
        <Card title="Review payment">
          <Payee qr={step.qr} />
          {step.qr.type === 'DYNAMIC' ? (
            <div className="big-amount">{formatMoney(step.qr.amount ?? 0, step.qr.currency)}</div>
          ) : (
            <label>
              Amount (₹)
              <input
                autoFocus
                inputMode="decimal"
                placeholder="0.00"
                value={amountText}
                onChange={(e) => setAmountText(e.target.value)}
                className="amount-input"
              />
            </label>
          )}
          {step.qr.expiresAt && (
            <p className="hint center">
              QR expires in <Countdown until={step.qr.expiresAt} />
            </p>
          )}
          <label>
            Note <span className="hint">(optional)</span>
            <input maxLength={140} value={note} onChange={(e) => setNote(e.target.value)} />
          </label>
          <div className="row">
            <button className="btn btn-ghost" onClick={() => setStep({ kind: 'scan' })} disabled={busy}>
              Back
            </button>
            <button className="btn grow" onClick={() => void proceed(step.qr)} disabled={busy}>
              {busy ? 'Creating…' : 'Proceed to pay'}
            </button>
          </div>
        </Card>
      )}

      {step.kind === 'authorize' && (
        <Card title="Enter wallet PIN">
          <Payee qr={step.qr} />
          <div className="big-amount">{formatMoney(step.payment.amount, step.payment.currency)}</div>
          <p className="hint center">
            Session expires in <Countdown until={step.payment.expiresAt} />
          </p>
          <form
            className="stack"
            onSubmit={(e) => {
              e.preventDefault();
              void confirm(step.payment);
            }}
          >
            <input
              autoFocus
              className="pin-input"
              type="password"
              inputMode="numeric"
              pattern="\d{4,6}"
              maxLength={6}
              placeholder="••••"
              value={pin}
              onChange={(e) => setPin(e.target.value.replace(/\D/g, ''))}
              aria-label="PIN"
            />
            <div className="row">
              <button type="button" className="btn btn-ghost" onClick={() => void cancel(step.payment)} disabled={busy}>
                Cancel
              </button>
              <button type="submit" className="btn grow" disabled={busy || pin.length < 4}>
                {busy ? 'Paying…' : `Pay ${formatMoney(step.payment.amount, step.payment.currency)}`}
              </button>
            </div>
          </form>
        </Card>
      )}

      {step.kind === 'result' && <Result payment={step.payment} onAgain={() => setStep({ kind: 'scan' })} />}
    </div>
  );
}

function Payee({ qr }: { qr: ResolvedQr }) {
  return (
    <div className="payee">
      <div className="avatar">{qr.merchantName.slice(0, 1).toUpperCase()}</div>
      <div>
        <strong>{qr.merchantName}</strong>
        <span className="hint">
          {qr.merchantVpa} <span className="verified" title="QR signature verified">✔ verified</span>
        </span>
        {qr.orderRef && <span className="hint">Order {qr.orderRef}</span>}
      </div>
    </div>
  );
}

function Result({ payment, onAgain }: { payment: PaymentView; onAgain: () => void }) {
  const ok = payment.status === 'SUCCESS';
  return (
    <Card className={`result ${ok ? 'result-ok' : 'result-bad'}`}>
      <div className="result-icon">{ok ? '✓' : '✕'}</div>
      <h2>{ok ? 'Payment successful' : `Payment ${payment.status.toLowerCase()}`}</h2>
      <div className="big-amount">{formatMoney(payment.amount, payment.currency)}</div>
      <p>
        {ok ? 'Paid to' : 'To'} <strong>{payment.merchantName}</strong>
      </p>
      {!ok && <p className="error-text">{FAILURE_TEXT[payment.failureReason ?? ''] ?? payment.failureReason}</p>}
      <dl className="receipt">
        <dt>Payment ID</dt>
        <dd className="mono">{payment.id}</dd>
        <dt>Time</dt>
        <dd>{formatDateTime(payment.completedAt ?? payment.createdAt)}</dd>
        {payment.orderRef && (
          <>
            <dt>Order</dt>
            <dd>{payment.orderRef}</dd>
          </>
        )}
      </dl>
      <div className="row">
        <Link className="btn btn-ghost grow" to="/customer">
          Done
        </Link>
        <button className="btn grow" onClick={onAgain}>
          Pay again
        </button>
      </div>
    </Card>
  );
}
