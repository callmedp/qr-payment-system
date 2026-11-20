// Mirrors the backend DTOs. All amounts are integers in minor units (paise).

export type Role = 'CUSTOMER' | 'MERCHANT';

export interface UserView {
  id: string;
  email: string;
  fullName: string;
  role: Role;
  merchantId: string | null;
}

export interface AuthResponse {
  accessToken: string;
  tokenType: string;
  expiresAt: string;
  user: UserView;
}

export type QrType = 'STATIC' | 'DYNAMIC';
export type QrStatus = 'ACTIVE' | 'PAID' | 'EXPIRED' | 'DISABLED';

export interface QrView {
  id: string;
  type: QrType;
  status: QrStatus;
  amount: number | null;
  currency: string;
  orderRef: string | null;
  payload: string;
  expiresAt: string | null;
  paidPaymentId: string | null;
  createdAt: string;
}

export interface ResolvedQr {
  qrId: string;
  type: QrType;
  merchantName: string;
  merchantVpa: string;
  amount: number | null;
  currency: string;
  orderRef: string | null;
  expiresAt: string | null;
}

export type PaymentStatus = 'CREATED' | 'SUCCESS' | 'FAILED' | 'EXPIRED' | 'CANCELLED';

export interface PaymentView {
  id: string;
  qrId: string;
  qrType: QrType;
  merchantId: string;
  merchantName: string;
  payerName: string;
  amount: number;
  currency: string;
  status: PaymentStatus;
  failureReason: string | null;
  orderRef: string | null;
  note: string | null;
  createdAt: string;
  expiresAt: string;
  completedAt: string | null;
}

export interface WalletView {
  id: string;
  ownerType: 'USER' | 'MERCHANT' | 'SYSTEM';
  balance: number;
  currency: string;
  updatedAt: string;
}

export interface LedgerEntryView {
  id: string;
  entryType: 'DEBIT' | 'CREDIT';
  amount: number;
  balanceAfter: number;
  description: string | null;
  paymentId: string | null;
  createdAt: string;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface MerchantView {
  id: string;
  businessName: string;
  vpa: string;
  categoryCode: string;
  status: 'ACTIVE' | 'SUSPENDED';
  webhookUrl: string | null;
  createdAt: string;
}

/** Fetched on demand; never part of the merchant profile payload. */
export interface WebhookSecretView {
  webhookSecret: string;
  /** When the rotated-out secret stops verifying, or null if no grace window is open. */
  previousSecretExpiresAt: string | null;
}

/** Pushed over Server-Sent Events. */
export interface PaymentNotification {
  eventId: string;
  eventType: 'PAYMENT_SUCCEEDED' | 'PAYMENT_FAILED';
  paymentId: string;
  status: PaymentStatus;
  failureReason: string | null;
  qrId: string;
  orderRef: string | null;
  amount: number;
  currency: string;
  merchantName: string;
  payerName: string;
  occurredAt: string;
}

export interface WebhookDelivery {
  receivedAt: string;
  eventId: string;
  eventType: string;
  merchantId: string;
  signatureValid: boolean;
  body: string;
}
