import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { errorMessage } from '../../api/client';
import { Api, DEV_WEBHOOK_SINK_URL } from '../../api/endpoints';
import type {
  MerchantView, PaymentNotification, PaymentView, QrView, WalletView, WebhookDelivery, WebhookSecretView,
} from '../../api/types';
import { QrImage } from '../../components/QrImage';
import { Card, Countdown, Empty, ErrorBanner, StatusBadge, StreamPill } from '../../components/ui';
import { useNow } from '../../hooks/useNow';
import { usePaymentStream } from '../../hooks/usePaymentStream';
import { formatDateTime, formatMoney, formatTime, toMinor } from '../../utils/money';

const EXPIRY_OPTIONS = [
  { seconds: 120, label: '2 min' },
  { seconds: 300, label: '5 min' },
  { seconds: 600, label: '10 min' },
  { seconds: 1800, label: '30 min' },
];

export default function MerchantDashboard() {
  const [merchant, setMerchant] = useState<MerchantView | null>(null);
  const [wallet, setWallet] = useState<WalletView | null>(null);
  const [payments, setPayments] = useState<PaymentView[]>([]);
  const [feed, setFeed] = useState<PaymentNotification[]>([]);
  const [activeQr, setActiveQr] = useState<QrView | null>(null);
  const [paidBy, setPaidBy] = useState<PaymentNotification | null>(null);
  const [error, setError] = useState<string | null>(null);
  const activeQrId = useRef<string | null>(null);

  useEffect(() => {
    activeQrId.current = activeQr?.id ?? null;
  }, [activeQr]);

  const refresh = useCallback(async () => {
    try {
      const [w, p] = await Promise.all([Api.wallet(), Api.payments(0, 10)]);
      setWallet(w);
      setPayments(p.items);
    } catch (e) {
      setError(errorMessage(e));
    }
  }, []);

  useEffect(() => {
    Api.merchant().then(setMerchant).catch((e) => setError(errorMessage(e)));
    void refresh();
  }, [refresh]);

  const stream = usePaymentStream(
    useCallback(
      (n: PaymentNotification) => {
        setFeed((f) => [n, ...f].slice(0, 20));
        if (n.status === 'SUCCESS' && n.qrId === activeQrId.current) {
          setActiveQr((q) => (q ? { ...q, status: 'PAID', paidPaymentId: n.paymentId } : q));
          setPaidBy(n);
        }
        void refresh();
      },
      [refresh],
    ),
  );

  // Fallback: if the SSE stream is down, poll the live QR's status so the till never gets stuck.
  useEffect(() => {
    if (!activeQr || activeQr.status !== 'ACTIVE' || stream === 'live') return;
    const id = setInterval(async () => {
      try {
        const q = await Api.qr(activeQr.id);
        if (q.status !== 'ACTIVE') {
          setActiveQr(q);
          void refresh();
        }
      } catch {
        /* keep polling */
      }
    }, 4000);
    return () => clearInterval(id);
  }, [activeQr, stream, refresh]);

  const received = payments.filter((p) => p.status === 'SUCCESS');

  return (
    <div className="page">
      <ErrorBanner message={error} onClose={() => setError(null)} />
      <div className="stats">
        <div className="stat">
          <span className="stat-label">Settlement balance</span>
          <span className="stat-value">{wallet ? formatMoney(wallet.balance, wallet.currency) : '—'}</span>
        </div>
        <div className="stat">
          <span className="stat-label">Recent successful payments</span>
          <span className="stat-value">{received.length}</span>
        </div>
        <div className="stat">
          <span className="stat-label">Recent volume</span>
          <span className="stat-value">{formatMoney(received.reduce((s, p) => s + p.amount, 0))}</span>
        </div>
        <div className="stat">
          <span className="stat-label">{merchant?.businessName ?? 'Merchant'}</span>
          <span className="stat-value small mono">{merchant?.vpa ?? '…'}</span>
          <StreamPill status={stream} />
        </div>
      </div>

      <div className="grid-2">
        <CollectPanel
          activeQr={activeQr}
          paidBy={paidBy}
          onCreated={(q) => {
            setActiveQr(q);
            setPaidBy(null);
          }}
          onError={setError}
        />

        <div className="stack">
          <Card title="Live feed" actions={<span className="hint">Kafka → Redis Pub/Sub → SSE</span>}>
            {feed.length === 0 ? (
              <Empty>Waiting for payments… events appear here the moment a customer pays.</Empty>
            ) : (
              <ul className="list feed">
                {feed.map((n) => (
                  <li key={n.eventId} className={`list-row feed-item ${n.status === 'SUCCESS' ? 'ok' : 'bad'}`}>
                    <div>
                      <strong>
                        {n.status === 'SUCCESS' ? 'Received' : 'Failed'} {formatMoney(n.amount, n.currency)}
                      </strong>
                      <span className="hint">
                        from {n.payerName} · {formatTime(n.occurredAt)}
                        {n.failureReason && ` · ${n.failureReason}`}
                      </span>
                    </div>
                    <StatusBadge status={n.status} />
                  </li>
                ))}
              </ul>
            )}
          </Card>

          <Card title="Recent payments">
            {payments.length === 0 ? (
              <Empty>No payments yet.</Empty>
            ) : (
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>When</th>
                      <th>Customer</th>
                      <th>Order</th>
                      <th className="num">Amount</th>
                      <th>Status</th>
                    </tr>
                  </thead>
                  <tbody>
                    {payments.map((p) => (
                      <tr key={p.id}>
                        <td>{formatDateTime(p.createdAt)}</td>
                        <td>{p.payerName}</td>
                        <td className="mono">{p.orderRef ?? (p.qrType === 'STATIC' ? 'static' : '—')}</td>
                        <td className="num">{formatMoney(p.amount, p.currency)}</td>
                        <td>
                          <StatusBadge status={p.status} />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>
        </div>
      </div>

      {merchant && <WebhookSettings merchant={merchant} onSaved={setMerchant} onError={setError} />}
    </div>
  );
}

function CollectPanel({ activeQr, paidBy, onCreated, onError }: {
  activeQr: QrView | null;
  paidBy: PaymentNotification | null;
  onCreated: (q: QrView) => void;
  onError: (m: string) => void;
}) {
  const [tab, setTab] = useState<'dynamic' | 'static'>('dynamic');
  const [amountText, setAmountText] = useState('');
  const [orderRef, setOrderRef] = useState('');
  const [expiry, setExpiry] = useState(300);
  const [busy, setBusy] = useState(false);
  const [staticQr, setStaticQr] = useState<QrView | null>(null);

  useEffect(() => {
    if (tab === 'static' && !staticQr) Api.staticQr().then(setStaticQr).catch((e) => onError(errorMessage(e)));
  }, [tab, staticQr, onError]);

  const create = async (e: FormEvent) => {
    e.preventDefault();
    const amount = toMinor(amountText);
    if (!amount) {
      onError('Enter a valid amount, e.g. 250 or 99.50');
      return;
    }
    setBusy(true);
    try {
      const ref = orderRef.trim() || `ORD-${Date.now().toString(36).toUpperCase()}`;
      onCreated(await Api.createDynamicQr({ amount, orderRef: ref, expiresInSeconds: expiry }));
      setOrderRef('');
    } catch (err) {
      onError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card
      title="Collect payment"
      actions={
        <div className="segmented small">
          <button className={tab === 'dynamic' ? 'active' : ''} onClick={() => setTab('dynamic')}>
            Dynamic QR
          </button>
          <button className={tab === 'static' ? 'active' : ''} onClick={() => setTab('static')}>
            Static QR
          </button>
        </div>
      }
    >
      {tab === 'dynamic' ? (
        <>
          <form className="collect-form" onSubmit={create}>
            <label>
              Amount (₹)
              <input inputMode="decimal" placeholder="250.00" value={amountText} onChange={(e) => setAmountText(e.target.value)} required />
            </label>
            <label>
              Order ref
              <input placeholder="auto" value={orderRef} maxLength={64} onChange={(e) => setOrderRef(e.target.value)} />
            </label>
            <label>
              Expires in
              <select value={expiry} onChange={(e) => setExpiry(Number(e.target.value))}>
                {EXPIRY_OPTIONS.map((o) => (
                  <option key={o.seconds} value={o.seconds}>
                    {o.label}
                  </option>
                ))}
              </select>
            </label>
            <button className="btn" disabled={busy}>
              {busy ? 'Generating…' : 'Generate QR'}
            </button>
          </form>
          {activeQr ? (
            <QrDisplay qr={activeQr} paidBy={paidBy} />
          ) : (
            <Empty>Generate a single-use QR for the exact bill amount. It flips to PAID the instant the customer pays.</Empty>
          )}
        </>
      ) : staticQr ? (
        <div className="qr-stage">
          <QrImage className="qr-img" payload={staticQr.payload} alt="Static QR code" />
          <p className="hint center">Print once, use forever. The customer enters the amount.</p>
          <CopyPayload payload={staticQr.payload} />
        </div>
      ) : (
        <Empty>Loading…</Empty>
      )}
    </Card>
  );
}

function QrDisplay({ qr, paidBy }: { qr: QrView; paidBy: PaymentNotification | null }) {
  const now = useNow(1000);
  const expired = qr.status === 'EXPIRED' || (qr.status === 'ACTIVE' && !!qr.expiresAt && now >= new Date(qr.expiresAt).getTime());
  return (
    <div className="qr-stage">
      <div className={`qr-frame ${qr.status === 'PAID' ? 'paid' : expired ? 'expired' : ''}`}>
        <QrImage className="qr-img" payload={qr.payload} alt="Payment QR code" />
        {qr.status === 'PAID' && (
          <div className="qr-overlay ok">
            <span className="result-icon">✓</span>
            <strong>Paid</strong>
            {paidBy && <span>by {paidBy.payerName}</span>}
          </div>
        )}
        {qr.status !== 'PAID' && expired && (
          <div className="qr-overlay muted">
            <strong>Expired</strong>
            <span>Generate a new QR</span>
          </div>
        )}
      </div>
      <div className="big-amount">{formatMoney(qr.amount ?? 0, qr.currency)}</div>
      <div className="row center-row">
        <StatusBadge status={expired && qr.status === 'ACTIVE' ? 'EXPIRED' : qr.status} />
        {qr.orderRef && <span className="mono hint">{qr.orderRef}</span>}
        {qr.status === 'ACTIVE' && qr.expiresAt && !expired && <Countdown until={qr.expiresAt} />}
      </div>
      <CopyPayload payload={qr.payload} />
    </div>
  );
}

function CopyPayload({ payload }: { payload: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <details className="payload">
      <summary>QR payload</summary>
      <code className="mono">{payload}</code>
      <button
        className="btn btn-ghost btn-sm"
        onClick={() =>
          void navigator.clipboard.writeText(payload).then(() => {
            setCopied(true);
            setTimeout(() => setCopied(false), 1500);
          })
        }
      >
        {copied ? 'Copied ✓' : 'Copy'}
      </button>
    </details>
  );
}

function WebhookSettings({ merchant, onSaved, onError }: {
  merchant: MerchantView;
  onSaved: (m: MerchantView) => void;
  onError: (m: string) => void;
}) {
  const [url, setUrl] = useState(merchant.webhookUrl ?? '');
  const [secret, setSecret] = useState<WebhookSecretView | null>(null);
  const [rotating, setRotating] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [deliveries, setDeliveries] = useState<WebhookDelivery[]>([]);
  const usingSink = merchant.webhookUrl === DEV_WEBHOOK_SINK_URL;

  const save = async (value: string | null) => {
    try {
      const m = await Api.updateWebhook(value);
      onSaved(m);
      setUrl(m.webhookUrl ?? '');
    } catch (e) {
      onError(errorMessage(e));
    }
  };

  // The secret is no longer part of the merchant payload - it is fetched only when asked for.
  const toggleReveal = async () => {
    if (secret) {
      setSecret(null);
      return;
    }
    try {
      setSecret(await Api.webhookSecret());
    } catch (e) {
      onError(errorMessage(e));
    }
  };

  // Two-step confirm rather than window.confirm: rotation is destructive-ish and a native
  // modal would block the page.
  const rotate = async () => {
    if (!confirming) {
      setConfirming(true);
      return;
    }
    setRotating(true);
    try {
      setSecret(await Api.rotateWebhookSecret());
      setConfirming(false);
    } catch (e) {
      onError(errorMessage(e));
    } finally {
      setRotating(false);
    }
  };

  useEffect(() => {
    if (!usingSink) return;
    const load = () =>
      Api.devWebhookDeliveries()
        .then((all) => setDeliveries(all.filter((d) => d.merchantId === merchant.id)))
        .catch(() => undefined);
    void load();
    const id = setInterval(load, 3000);
    return () => clearInterval(id);
  }, [usingSink, merchant.id]);

  return (
    <Card title="Webhooks" actions={<span className="hint">signed with HMAC-SHA256 · retried via Kafka retry topics</span>}>
      <form
        className="webhook-form"
        onSubmit={(e) => {
          e.preventDefault();
          void save(url.trim() || null);
        }}
      >
        <input placeholder="https://your-server.example/webhooks/qrpay" value={url} onChange={(e) => setUrl(e.target.value)} />
        <button className="btn">Save</button>
        <button type="button" className="btn btn-ghost" onClick={() => void save(DEV_WEBHOOK_SINK_URL)}>
          Use local dev sink
        </button>
        {merchant.webhookUrl && (
          <button type="button" className="btn btn-ghost" onClick={() => void save(null)}>
            Remove
          </button>
        )}
      </form>
      <p className="hint">
        Signing secret:{' '}
        <code className="mono">{secret ? secret.webhookSecret : '•'.repeat(24)}</code>{' '}
        <button className="link" onClick={() => void toggleReveal()}>
          {secret ? 'hide' : 'reveal'}
        </button>{' '}
        <button className="link" onClick={() => void rotate()} disabled={rotating}>
          {rotating ? 'rotating…' : confirming ? 'confirm rotate?' : 'rotate'}
        </button>
        {confirming && !rotating && (
          <>
            {' '}
            <button className="link" onClick={() => setConfirming(false)}>
              cancel
            </button>
          </>
        )}
      </p>
      {secret?.previousSecretExpiresAt && (
        <p className="hint">
          Previous secret still accepted until <strong>{formatDateTime(secret.previousSecretExpiresAt)}</strong> — until
          then every delivery is signed with both, so update your receiver before the window closes.
        </p>
      )}
      {usingSink &&
        (deliveries.length === 0 ? (
          <Empty>No deliveries yet - take a payment and the webhook shows up here within a second or two.</Empty>
        ) : (
          <ul className="list">
            {deliveries.map((d) => (
              <li key={d.eventId + d.receivedAt} className="list-row">
                <div>
                  <strong>{d.eventType}</strong>
                  <span className="hint mono">{d.eventId}</span>
                </div>
                <div className="row-end">
                  <span className="hint">{formatTime(d.receivedAt)}</span>
                  <span className={`badge ${d.signatureValid ? 'badge-ok' : 'badge-bad'}`}>
                    {d.signatureValid ? 'signature ✓' : 'bad signature'}
                  </span>
                </div>
              </li>
            ))}
          </ul>
        ))}
    </Card>
  );
}
