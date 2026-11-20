import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { errorMessage, newIdempotencyKey } from '../../api/client';
import { Api } from '../../api/endpoints';
import type { LedgerEntryView, PaymentNotification, PaymentView, WalletView } from '../../api/types';
import { Card, Empty, ErrorBanner, StatusBadge, StreamPill } from '../../components/ui';
import { usePaymentStream } from '../../hooks/usePaymentStream';
import { formatDateTime, formatMoney } from '../../utils/money';

const TOP_UPS = [50_000, 100_000, 200_000];

export default function CustomerHome() {
  const [wallet, setWallet] = useState<WalletView | null>(null);
  const [payments, setPayments] = useState<PaymentView[]>([]);
  const [ledger, setLedger] = useState<LedgerEntryView[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      const [w, p, l] = await Promise.all([Api.wallet(), Api.payments(0, 8), Api.ledger(0, 8)]);
      setWallet(w);
      setPayments(p.items);
      setLedger(l.items);
    } catch (e) {
      setError(errorMessage(e));
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const stream = usePaymentStream(useCallback((_n: PaymentNotification) => void load(), [load]));

  const topUp = async (amount: number) => {
    setBusy(true);
    setError(null);
    try {
      await Api.topUp(amount, newIdempotencyKey());
      await load();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page">
      <ErrorBanner message={error} onClose={() => setError(null)} />
      <div className="grid-2">
        <Card className="balance-card">
          <div className="balance-label">
            Wallet balance <StreamPill status={stream} />
          </div>
          <div className="balance">{wallet ? formatMoney(wallet.balance, wallet.currency) : '—'}</div>
          <Link to="/customer/pay" className="btn btn-lg btn-block">
            ▦ Scan &amp; Pay
          </Link>
          <div className="topups">
            <span className="hint">Add money (demo):</span>
            {TOP_UPS.map((a) => (
              <button key={a} className="btn btn-ghost btn-sm" disabled={busy} onClick={() => void topUp(a)}>
                +{formatMoney(a)}
              </button>
            ))}
          </div>
        </Card>

        <Card title="Recent payments">
          {payments.length === 0 ? (
            <Empty>No payments yet. Scan a merchant QR to make your first one.</Empty>
          ) : (
            <ul className="list">
              {payments.map((p) => (
                <li key={p.id} className="list-row">
                  <div>
                    <strong>{p.merchantName}</strong>
                    <span className="hint">
                      {formatDateTime(p.createdAt)}
                      {p.failureReason && ` · ${p.failureReason}`}
                    </span>
                  </div>
                  <div className="row-end">
                    <span className="amount">{formatMoney(p.amount, p.currency)}</span>
                    <StatusBadge status={p.status} />
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>

      <Card title="Statement" actions={<span className="hint">double-entry ledger</span>}>
        {ledger.length === 0 ? (
          <Empty>No ledger entries.</Empty>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>When</th>
                  <th>Description</th>
                  <th className="num">Amount</th>
                  <th className="num">Balance</th>
                </tr>
              </thead>
              <tbody>
                {ledger.map((e) => (
                  <tr key={e.id}>
                    <td>{formatDateTime(e.createdAt)}</td>
                    <td>{e.description}</td>
                    <td className={`num ${e.entryType === 'CREDIT' ? 'credit' : 'debit'}`}>
                      {e.entryType === 'CREDIT' ? '+' : '−'}
                      {formatMoney(e.amount)}
                    </td>
                    <td className="num">{formatMoney(e.balanceAfter)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  );
}
