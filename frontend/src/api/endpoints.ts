import { api } from './client';
import type {
  AuthResponse,
  LedgerEntryView,
  MerchantView,
  Page,
  PaymentView,
  QrView,
  ResolvedQr,
  UserView,
  WalletView,
  WebhookDelivery,
  WebhookSecretView,
} from './types';

export const Api = {
  // auth
  login: (email: string, password: string) =>
    api<AuthResponse>('/api/v1/auth/login', { method: 'POST', body: { email, password } }),
  registerCustomer: (body: { fullName: string; email: string; password: string; pin: string }) =>
    api<AuthResponse>('/api/v1/auth/register/customer', { method: 'POST', body }),
  registerMerchant: (body: {
    fullName: string;
    email: string;
    password: string;
    businessName: string;
    categoryCode: string;
  }) => api<AuthResponse>('/api/v1/auth/register/merchant', { method: 'POST', body }),
  me: () => api<UserView>('/api/v1/auth/me'),

  // wallet
  wallet: () => api<WalletView>('/api/v1/wallets/me'),
  ledger: (page = 0, size = 10) => api<Page<LedgerEntryView>>(`/api/v1/wallets/me/ledger?page=${page}&size=${size}`),
  topUp: (amount: number, idempotencyKey: string) =>
    api<WalletView>('/api/v1/wallets/me/top-up', { method: 'POST', body: { amount }, idempotencyKey }),

  // payments
  payments: (page = 0, size = 10) => api<Page<PaymentView>>(`/api/v1/payments?page=${page}&size=${size}`),
  payment: (id: string) => api<PaymentView>(`/api/v1/payments/${id}`),
  initiatePayment: (body: { qrId: string; amount?: number; note?: string }, idempotencyKey: string) =>
    api<PaymentView>('/api/v1/payments', { method: 'POST', body, idempotencyKey }),
  confirmPayment: (id: string, pin: string, idempotencyKey: string) =>
    api<PaymentView>(`/api/v1/payments/${id}/confirm`, { method: 'POST', body: { pin }, idempotencyKey }),
  cancelPayment: (id: string) => api<PaymentView>(`/api/v1/payments/${id}/cancel`, { method: 'POST' }),

  // QR
  resolveQr: (payload: string) => api<ResolvedQr>('/api/v1/qr-codes/resolve', { method: 'POST', body: { payload } }),
  createDynamicQr: (body: { amount: number; orderRef?: string; expiresInSeconds?: number }) =>
    api<QrView>('/api/v1/qr-codes/dynamic', { method: 'POST', body }),
  staticQr: () => api<QrView>('/api/v1/qr-codes/static'),
  qr: (id: string) => api<QrView>(`/api/v1/qr-codes/${id}`),

  // merchant
  merchant: () => api<MerchantView>('/api/v1/merchants/me'),
  updateWebhook: (url: string | null) => api<MerchantView>('/api/v1/merchants/me/webhook', { method: 'PUT', body: { url } }),
  webhookSecret: () => api<WebhookSecretView>('/api/v1/merchants/me/webhook/secret'),
  rotateWebhookSecret: () => api<WebhookSecretView>('/api/v1/merchants/me/webhook/secret', { method: 'POST' }),
  devWebhookDeliveries: () => api<WebhookDelivery[]>('/api/v1/dev/webhook-sink'),
};

export const DEV_WEBHOOK_SINK_URL = 'http://localhost:8080/api/v1/dev/webhook-sink';
