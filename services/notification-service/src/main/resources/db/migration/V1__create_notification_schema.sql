-- ═══════════════════════════════════════════════════════════════
-- V1__create_notification_schema.sql
-- Notification Service — requests, dispatches, templates
-- ═══════════════════════════════════════════════════════════════

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ─── Notification Requests ───
CREATE TABLE notification_requests (
    notification_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    customer_id         VARCHAR(100),
    loan_id             VARCHAR(100),
    source_event_id     VARCHAR(100) NOT NULL UNIQUE,   -- idempotency key
    source_event_type   VARCHAR(100) NOT NULL,
    trigger_type        VARCHAR(60) NOT NULL,
    status              VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    recipient_phone     VARCHAR(20),
    recipient_email     VARCHAR(255),
    recipient_name      VARCHAR(200),
    preferred_language  VARCHAR(5) NOT NULL DEFAULT 'en',
    retry_count         INTEGER NOT NULL DEFAULT 0,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notif_req_tenant       ON notification_requests(tenant_id);
CREATE INDEX idx_notif_req_source_event ON notification_requests(source_event_id);
CREATE INDEX idx_notif_req_status       ON notification_requests(status, retry_count)
    WHERE status = 'FAILED';
CREATE INDEX idx_notif_req_loan         ON notification_requests(tenant_id, loan_id)
    WHERE loan_id IS NOT NULL;

-- ─── Channel Dispatches ───
CREATE TABLE channel_dispatches (
    dispatch_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_id      UUID NOT NULL REFERENCES notification_requests(notification_id) ON DELETE CASCADE,
    channel              VARCHAR(20) NOT NULL,    -- SMS, EMAIL, WHATSAPP, PUSH, IN_APP
    status               VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    provider_reference   VARCHAR(200),            -- MSG91 requestId, SES messageId, etc.
    failure_reason       TEXT,
    attempt_count        INTEGER NOT NULL DEFAULT 0,
    sent_at              TIMESTAMP WITH TIME ZONE,
    delivered_at         TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_dispatch_notification ON channel_dispatches(notification_id);
CREATE INDEX idx_dispatch_status       ON channel_dispatches(status) WHERE status = 'FAILED';

-- ─── Notification Templates ───
CREATE TABLE notification_templates (
    template_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    trigger_type    VARCHAR(60) NOT NULL,
    channel         VARCHAR(20) NOT NULL,
    language        VARCHAR(5) NOT NULL DEFAULT 'en',
    subject         VARCHAR(500),                     -- Email subject line
    body            TEXT NOT NULL,                    -- SMS/WhatsApp text or HTML email body
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE(tenant_id, trigger_type, channel, language)
);

CREATE INDEX idx_template_lookup ON notification_templates(tenant_id, trigger_type, channel, language)
    WHERE active = TRUE;

-- ─── Seed default English templates for RBI-mandated events ───
-- Using a sentinel tenant_id (all zeros) as the "default" template
-- Services can override per tenant by inserting tenant-specific rows.

INSERT INTO notification_templates (tenant_id, trigger_type, channel, language, subject, body) VALUES
-- Application Submitted
('00000000-0000-0000-0000-000000000001','APPLICATION_SUBMITTED','SMS','en', NULL,
 'Dear {{recipient_name}}, your loan application {{application_id}} has been received. We will update you within 24 hours. - Originex'),
('00000000-0000-0000-0000-000000000001','APPLICATION_SUBMITTED','EMAIL','en',
 'Loan Application Received - {{application_id}}',
 '<p>Dear {{recipient_name}},</p><p>Your loan application <b>{{application_id}}</b> has been successfully received. We will review and update you within 24 hours.</p><p>Regards,<br>Originex Team</p>'),

-- Application Approved / Sanction Letter
('00000000-0000-0000-0000-000000000001','APPLICATION_APPROVED','SMS','en', NULL,
 'Congratulations {{recipient_name}}! Your loan of INR {{sanctioned_amount}} is approved. Check your email for the sanction letter. - Originex'),
('00000000-0000-0000-0000-000000000001','APPLICATION_APPROVED','EMAIL','en',
 'Loan Approved — Sanction Letter for {{application_id}}',
 '<p>Dear {{recipient_name}},</p><p>We are pleased to inform you that your loan application <b>{{application_id}}</b> has been approved for <b>INR {{sanctioned_amount}}</b>.</p><p>Please review and accept the offer to proceed with disbursement.</p><p>Regards,<br>Originex Team</p>'),

-- Application Rejected
('00000000-0000-0000-0000-000000000001','APPLICATION_REJECTED','SMS','en', NULL,
 'Dear {{recipient_name}}, we regret that your loan application {{application_id}} could not be approved at this time. For queries, contact support. - Originex'),

-- Loan Disbursed (RBI-mandated: must include UTR)
('00000000-0000-0000-0000-000000000001','LOAN_DISBURSED','SMS','en', NULL,
 'Dear {{recipient_name}}, INR {{amount}} has been disbursed to your account. UTR: {{utr}}. Loan ID: {{loan_id}}. - Originex'),
('00000000-0000-0000-0000-000000000001','LOAN_DISBURSED','EMAIL','en',
 'Loan Disbursement Confirmation — UTR {{utr}}',
 '<p>Dear {{recipient_name}},</p><p>Your loan amount of <b>INR {{amount}}</b> has been successfully disbursed.</p><ul><li>Loan ID: {{loan_id}}</li><li>UTR: <b>{{utr}}</b></li></ul><p>Please keep this for your records.</p>'),

-- Repayment Received (RBI-mandated receipt)
('00000000-0000-0000-0000-000000000001','REPAYMENT_RECEIVED','SMS','en', NULL,
 'Dear {{recipient_name}}, we have received your EMI payment of INR {{amount}} for loan {{loan_id}}. Thank you. - Originex'),

-- Payment Failed
('00000000-0000-0000-0000-000000000001','PAYMENT_FAILED','SMS','en', NULL,
 'Dear {{recipient_name}}, your EMI payment for loan {{loan_id}} could not be processed. Reason: {{failure_reason}}. Please pay via app to avoid late charges. - Originex'),

-- KYC Completed
('00000000-0000-0000-0000-000000000001','KYC_COMPLETED','SMS','en', NULL,
 'Dear {{recipient_name}}, your KYC verification is complete. You can now apply for a loan. - Originex'),

-- NACH Mandate Registered
('00000000-0000-0000-0000-000000000001','NACH_MANDATE_REGISTERED','SMS','en', NULL,
 'Dear {{recipient_name}}, your NACH auto-debit mandate for loan {{loan_id}} has been registered. Your EMI will be auto-debited on the due date. - Originex'),

-- Disbursement Completed (with UTR)
('00000000-0000-0000-0000-000000000001','DISBURSEMENT_COMPLETED','SMS','en', NULL,
 'Dear {{recipient_name}}, INR {{amount}} disbursed to your bank account. UTR: {{utr}}. Loan: {{loan_id}}. - Originex')

ON CONFLICT (tenant_id, trigger_type, channel, language) DO NOTHING;

-- ─── Row-Level Security ───
ALTER TABLE notification_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_notif ON notification_requests
    USING (tenant_id = current_setting('app.tenant_id')::uuid);

ALTER TABLE notification_templates ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_templates FORCE ROW LEVEL SECURITY;
-- Allow both tenant-specific templates AND the default sentinel tenant
CREATE POLICY tenant_isolation_templates ON notification_templates
    USING (tenant_id = current_setting('app.tenant_id')::uuid
        OR tenant_id = '00000000-0000-0000-0000-000000000001');
