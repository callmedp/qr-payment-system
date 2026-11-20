import { useMemo } from 'react';
import qrcode from 'qrcode-generator';

// M recovers ~15% damaged or smudged modules (a scuffed sticker, a glare spot on a till screen) without
// growing the code much; a 2-module quiet zone is enough when the page background around it is plain.
const ERROR_CORRECTION = 'M';
const MARGIN_MODULES = 2;

/**
 * Renders a signed QR payload as SVG. The server mints and signs the payload; drawing it is pure
 * presentation, so it happens here - crisp at any size, and no image bytes on the wire.
 */
export function QrImage({ payload, alt, className }: { payload: string; alt: string; className?: string }) {
  const { size, path } = useMemo(() => {
    const qr = qrcode(0, ERROR_CORRECTION);
    qr.addData(payload, 'Byte');
    qr.make();
    const count = qr.getModuleCount();
    let d = '';
    for (let row = 0; row < count; row++) {
      for (let col = 0; col < count; col++) {
        if (qr.isDark(row, col)) d += `M${col + MARGIN_MODULES} ${row + MARGIN_MODULES}h1v1h-1z`;
      }
    }
    return { size: count + 2 * MARGIN_MODULES, path: d };
  }, [payload]);

  return (
    <svg className={className} viewBox={`0 0 ${size} ${size}`} role="img" aria-label={alt} shapeRendering="crispEdges">
      {/* the quiet zone must stay light even in dark mode, or scanners cannot find the code */}
      <rect width={size} height={size} fill="#fff" />
      <path d={path} fill="#000" />
    </svg>
  );
}
