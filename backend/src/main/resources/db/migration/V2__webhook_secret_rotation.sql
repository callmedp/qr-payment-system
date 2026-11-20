-- Webhook secret rotation with a grace period.
--
-- When a merchant rotates their signing secret, the old one stays valid until
-- previous_secret_expires_at. During that window every delivery is signed with BOTH secrets
-- (repeated v1 parts in X-QrPay-Signature), so a receiver still holding the old secret keeps
-- verifying successfully and no payment notification is lost.
--
-- Both columns are NULL for a merchant that has never rotated.
ALTER TABLE merchants
    ADD COLUMN previous_webhook_secret    VARCHAR(64),
    ADD COLUMN previous_secret_expires_at TIMESTAMPTZ;
