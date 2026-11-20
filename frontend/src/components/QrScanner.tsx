import { Html5Qrcode } from 'html5-qrcode';
import { useEffect, useId, useRef, useState } from 'react';

type Mode = 'camera' | 'upload' | 'paste';

/**
 * Three ways to get a QR payload: live camera, an uploaded screenshot (handy when the merchant QR is on the
 * same machine), or pasting the payload string. All decoding happens in the browser; only the decoded
 * text is sent to the server.
 */
export function QrScanner({ onDecoded }: { onDecoded: (text: string) => void }) {
  const [mode, setMode] = useState<Mode>('upload');
  return (
    <div className="scanner">
      <div className="segmented">
        {(['camera', 'upload', 'paste'] as Mode[]).map((m) => (
          <button key={m} className={mode === m ? 'active' : ''} onClick={() => setMode(m)}>
            {m === 'camera' ? '📷 Camera' : m === 'upload' ? '🖼️ Upload image' : '📋 Paste code'}
          </button>
        ))}
      </div>
      {mode === 'camera' && <CameraScanner onDecoded={onDecoded} />}
      {mode === 'upload' && <UploadScanner onDecoded={onDecoded} />}
      {mode === 'paste' && <PasteInput onDecoded={onDecoded} />}
    </div>
  );
}

function CameraScanner({ onDecoded }: { onDecoded: (text: string) => void }) {
  const elementId = 'cam-' + useId().replace(/[^a-zA-Z0-9]/g, '');
  const [error, setError] = useState<string | null>(null);
  const callback = useRef(onDecoded);
  callback.current = onDecoded;

  useEffect(() => {
    const scanner = new Html5Qrcode(elementId, { verbose: false });
    let done = false;
    const started = scanner
      .start(
        { facingMode: 'environment' },
        { fps: 10, qrbox: { width: 240, height: 240 } },
        (text) => {
          if (done) return;
          done = true;
          scanner.stop().catch(() => undefined).finally(() => callback.current(text));
        },
        () => undefined,
      )
      .catch((e: unknown) => setError(`Camera unavailable: ${String(e)}`));
    return () => {
      done = true;
      started.then(() => (scanner.isScanning ? scanner.stop() : undefined)).catch(() => undefined);
    };
  }, [elementId]);

  return (
    <div>
      <div id={elementId} className="camera-view" />
      {error && <p className="hint error-text">{error}. Try “Upload image” instead.</p>}
    </div>
  );
}

function UploadScanner({ onDecoded }: { onDecoded: (text: string) => void }) {
  const elementId = 'file-' + useId().replace(/[^a-zA-Z0-9]/g, '');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const handleFile = async (file: File | undefined) => {
    if (!file) return;
    setBusy(true);
    setError(null);
    const scanner = new Html5Qrcode(elementId, { verbose: false });
    try {
      onDecoded(await scanner.scanFile(file, false));
    } catch {
      setError('No QR code found in that image.');
    } finally {
      scanner.clear();
      setBusy(false);
    }
  };

  return (
    <label className="dropzone">
      <input type="file" accept="image/*" onChange={(e) => void handleFile(e.target.files?.[0])} hidden />
      <div id={elementId} hidden />
      <span className="dropzone-icon">▦</span>
      <strong>{busy ? 'Reading…' : 'Choose a QR image'}</strong>
      <span className="hint">Screenshot the merchant’s QR and upload it here</span>
      {error && <span className="error-text">{error}</span>}
    </label>
  );
}

function PasteInput({ onDecoded }: { onDecoded: (text: string) => void }) {
  const [text, setText] = useState('');
  return (
    <form
      className="stack"
      onSubmit={(e) => {
        e.preventDefault();
        if (text.trim()) onDecoded(text.trim());
      }}
    >
      <textarea
        rows={4}
        placeholder="qrpay://pay?v=2&qid=…&sig=…"
        value={text}
        onChange={(e) => setText(e.target.value)}
        spellCheck={false}
      />
      <button className="btn" type="submit" disabled={!text.trim()}>
        Continue
      </button>
    </form>
  );
}
