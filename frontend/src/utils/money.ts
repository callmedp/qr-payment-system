const formatters = new Map<string, Intl.NumberFormat>();

/** 25000 (paise) -> "₹250.00" */
export function formatMoney(minor: number, currency = 'INR'): string {
  let f = formatters.get(currency);
  if (!f) {
    f = new Intl.NumberFormat('en-IN', { style: 'currency', currency });
    formatters.set(currency, f);
  }
  return f.format(minor / 100);
}

/**
 * "250.5" -> 25050. Parses the decimal string directly - never via floating point, where
 * 0.1 + 0.2 !== 0.3 would eventually cost somebody a paisa.
 */
export function toMinor(input: string): number | null {
  const value = input.trim().replace(/,/g, '');
  if (!/^\d{1,9}(\.\d{1,2})?$/.test(value)) return null;
  const [rupees, paise = ''] = value.split('.');
  const minor = Number(rupees) * 100 + Number(paise.padEnd(2, '0'));
  return minor > 0 ? minor : null;
}

export function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' });
}

export function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}
