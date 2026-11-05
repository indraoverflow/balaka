-- ============================================================================
-- V013: Provisioning Chart of Accounts untuk Fasilitas KUR BRI (FAS-124 / FAS-97)
--
-- Prasyarat Tata Kelola:
-- Gerbang Gate 3 (Otorisasi Founder) wajib terpenuhi sebelum aktivasi posting.
-- Skrip ini memprovisi 9 akun akuntansi sesuai spesifikasi FAS-97 §4.
-- Penandaan is_cash_bank pada akun 1.1.10 mengikuti standar FAS-87 (EXPLICIT).
-- ============================================================================
-- Renumbered from research doc V011 to V013: V011/V012 already used by
-- mfa_support and bank_account_number_length migrations respectively.

DO $$
DECLARE
    v_parent_asset_lancar UUID;
    v_parent_liabilitas UUID;
    v_parent_liabilitas_pendek UUID;
    v_parent_liabilitas_panjang UUID;
    v_parent_beban_lain UUID;
BEGIN
    -- 1. Temukan ID induk berdasarkan hierarki akun standar Balaka
    SELECT id INTO v_parent_asset_lancar FROM chart_of_accounts WHERE account_code = '1.1';
    SELECT id INTO v_parent_liabilitas FROM chart_of_accounts WHERE account_code = '2';
    SELECT id INTO v_parent_liabilitas_pendek FROM chart_of_accounts WHERE account_code = '2.1';
    SELECT id INTO v_parent_beban_lain FROM chart_of_accounts WHERE account_code = '5.2';

    -- 2. Buat Header Liabilitas Jangka Panjang (2.2) jika belum ada
    INSERT INTO chart_of_accounts (
        id, account_code, account_name, account_type, normal_balance,
        id_parent, level, is_header, is_permanent, is_cash_bank, cash_bank_source, description
    ) VALUES (
        gen_random_uuid(), '2.2', 'Liabilitas Jangka Panjang', 'LIABILITY', 'CREDIT',
        v_parent_liabilitas, 2, TRUE, TRUE, FALSE, NULL, 'Header Liabilitas Jangka Panjang (> 12 Bulan)'
    ) ON CONFLICT (account_code) DO NOTHING;

    -- Ambil ID header 2.2 yang baru dibuat atau sudah ada
    SELECT id INTO v_parent_liabilitas_panjang FROM chart_of_accounts WHERE account_code = '2.2';

    -- 3. Akun Kas & Bank KUR (1.1.10) - Wajib is_cash_bank = TRUE, source = EXPLICIT (FAS-87)
    INSERT INTO chart_of_accounts (
        id, account_code, account_name, account_type, normal_balance,
        id_parent, level, is_header, is_permanent, is_cash_bank, cash_bank_source, description
    ) VALUES (
        gen_random_uuid(), '1.1.10', 'Bank BRI — Rekening KUR', 'ASSET', 'DEBIT',
        v_parent_asset_lancar, 3, FALSE, TRUE, TRUE, 'EXPLICIT', 'Dedicated KUR bank account (Iga Mentari, beneficial)'
    ) ON CONFLICT (account_code) DO NOTHING;

    -- 4. Akun Titipan Uang Muka Angsuran (1.1.11)
    INSERT INTO chart_of_accounts (
        id, account_code, account_name, account_type, normal_balance,
        id_parent, level, is_header, is_permanent, is_cash_bank, cash_bank_source, description
    ) VALUES (
        gen_random_uuid(), '1.1.11', 'Angsuran Dibayar Dimuka', 'ASSET', 'DEBIT',
        v_parent_asset_lancar, 3, FALSE, TRUE, FALSE, NULL, 'Overpayment / prepaid installment held by lender'
    ) ON CONFLICT (account_code) DO NOTHING;

    -- 5. Hutang Pokok KUR Jangka Pendek (2.1.30)
    INSERT INTO chart_of_accounts (
        id, account_code, account_name, account_type, normal_balance,
        id_parent, level, is_header, is_permanent, is_cash_bank, cash_bank_source, description
    ) VALUES (
        gen_random_uuid(), '2.1.30', 'Hutang Bank Jangka Pendek — KUR', 'LIABILITY', 'CREDIT',
        v_parent_liabilitas_pendek, 3, FALSE, TRUE, FALSE, NULL, 'Current portion of KUR principal (due <= 12 months)'
    ) ON CONFLICT (account_code) DO NOTHING;

    -- 6. Akrual Bunga & Biaya Pinjaman (2.1.31)
    INSERT INTO chart_of_accounts (
        id, account_code, account_name, account_type, normal_balance,
        id_parent, level, is_header, is_permanent, is_cash_bank, cash_bank_source, description
    ) VALUES (
        gen_random_uuid(), '2.1.31', 'Hutang Bunga & Biaya Pinjaman', 'LIABILITY', 'CREDIT',
        v_parent_liabilitas_pendek, 3, FALSE, TRUE, FALSE, NULL, 'Accrued interest and loan fees due but unpaid'
    ) ON CONFLICT (account_code) DO NOTHING;

    -- 7. Hutang Pihak Berelasi - Iga Mentari (2.1.32)
    INSERT INTO chart_of_accounts (
        id, account_code, account_name, account_type, normal_balance,
        id_parent, level, is_header, is_permanent, is_cash_bank, cash_bank_source, description
    ) VALUES (
        gen_random_uuid(), '2.1.32', 'Utang Pihak Berelasi — Iga Mentari (KUR)', 'LIABILITY', 'CREDIT',
        v_parent_liabilitas_pendek, 3, FALSE, TRUE, FALSE, NULL, 'Related-party payable for personal borrower reimbursement'
    ) ON CONFLICT (account_code) DO NOTHING;

    -- 8. Hutang Pokok KUR Jangka Panjang (2.2.01)
    INSERT INTO chart_of_accounts (
        id, account_code, account_name, account_type, normal_balance,
        id_parent, level, is_header, is_permanent, is_cash_bank, cash_bank_source, description
    ) VALUES (
        gen_random_uuid(), '2.2.01', 'Hutang Bank Jangka Panjang — KUR', 'LIABILITY', 'CREDIT',
        v_parent_liabilitas_panjang, 3, FALSE, TRUE, FALSE, NULL, 'Non-current portion of KUR principal (due > 12 months)'
    ) ON CONFLICT (account_code) DO NOTHING;

    -- 9. Beban Bunga Pinjaman (5.2.03)
    INSERT INTO chart_of_accounts (
        id, account_code, account_name, account_type, normal_balance,
        id_parent, level, is_header, is_permanent, is_cash_bank, cash_bank_source, description
    ) VALUES (
        gen_random_uuid(), '5.2.03', 'Beban Bunga Pinjaman', 'EXPENSE', 'DEBIT',
        v_parent_beban_lain, 3, FALSE, TRUE, FALSE, NULL, 'Financing cost / interest expense on KUR facility'
    ) ON CONFLICT (account_code) DO NOTHING;

    -- 10. Beban Provisi, Administrasi & Denda (5.2.04)
    INSERT INTO chart_of_accounts (
        id, account_code, account_name, account_type, normal_balance,
        id_parent, level, is_header, is_permanent, is_cash_bank, cash_bank_source, description
    ) VALUES (
        gen_random_uuid(), '5.2.04', 'Beban Provisi, Administrasi & Denda Pinjaman', 'EXPENSE', 'DEBIT',
        v_parent_beban_lain, 3, FALSE, TRUE, FALSE, NULL, 'Loan origination, provision, admin fees and late penalties'
    ) ON CONFLICT (account_code) DO NOTHING;

END $$;
