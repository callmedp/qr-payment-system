import { useEffect, useRef, useState } from 'react';
import { tokenStore } from '../api/client';
import type { PaymentNotification } from '../api/types';

export type StreamStatus = 'connecting' | 'live' | 'offline';

/**
 * Subscribes to GET /api/v1/notifications/stream (Server-Sent Events).
 *
 * Uses fetch() + ReadableStream instead of EventSource so the JWT goes in the Authorization header rather
 * than the URL. Reconnects with exponential backoff and de-duplicates on eventId, because the backend
 * delivers at-least-once.
 */
export function usePaymentStream(onPayment: (n: PaymentNotification) => void): StreamStatus {
  const [status, setStatus] = useState<StreamStatus>('connecting');
  const handler = useRef(onPayment);

  useEffect(() => {
    handler.current = onPayment;
  }, [onPayment]);

  useEffect(() => {
    const token = tokenStore.get();
    if (!token) return;
    const controller = new AbortController();
    const seen = new Set<string>();
    let stopped = false;
    let backoffMs = 1000;

    const run = async () => {
      while (!stopped) {
        try {
          setStatus('connecting');
          const res = await fetch('/api/v1/notifications/stream', {
            headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
            signal: controller.signal,
          });
          if (res.status === 401) {
            setStatus('offline');
            return;
          }
          if (!res.ok || !res.body) throw new Error(`stream HTTP ${res.status}`);

          const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
          let buffer = '';
          for (;;) {
            const { value, done } = await reader.read();
            if (done) break;
            buffer += value.replace(/\r\n/g, '\n');
            let boundary: number;
            while ((boundary = buffer.indexOf('\n\n')) >= 0) {
              const frame = parseFrame(buffer.slice(0, boundary));
              buffer = buffer.slice(boundary + 2);
              if (frame.event === 'connected') {
                setStatus('live');
                backoffMs = 1000;
              } else if (frame.event === 'payment' && frame.data) {
                const n = JSON.parse(frame.data) as PaymentNotification;
                if (!seen.has(n.eventId)) {
                  seen.add(n.eventId);
                  handler.current(n);
                }
              }
            }
          }
        } catch {
          if (controller.signal.aborted) return;
        }
        if (stopped) return;
        setStatus('offline');
        await new Promise((r) => setTimeout(r, backoffMs));
        backoffMs = Math.min(backoffMs * 2, 15_000);
      }
    };
    void run();

    return () => {
      stopped = true;
      controller.abort();
    };
  }, []);

  return status;
}

function parseFrame(raw: string): { event: string; data: string } {
  let event = 'message';
  const data: string[] = [];
  for (const line of raw.split('\n')) {
    if (!line || line.startsWith(':')) continue; // comment / heartbeat
    const colon = line.indexOf(':');
    const field = colon < 0 ? line : line.slice(0, colon);
    const value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '');
    if (field === 'event') event = value;
    else if (field === 'data') data.push(value);
  }
  return { event, data: data.join('\n') };
}
