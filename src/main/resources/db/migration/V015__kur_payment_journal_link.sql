-- ============================================================================
-- V015: Add journal_transaction_id to kur_payments for paired reversal and
-- legal_borrower_structure to kur_loans (FAS-139 / FAS-97 / FAS-124)
-- ============================================================================

ALTER TABLE kur_payments
    ADD COLUMN journal_transaction_id UUID REFERENCES transactions(id);

CREATE INDEX idx_kur_payments_journal_tx ON kur_payments(journal_transaction_id);

ALTER TABLE kur_loans
    ADD COLUMN legal_borrower_structure VARCHAR(30) DEFAULT 'STRUCTURE_D';
