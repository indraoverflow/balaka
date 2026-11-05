-- ============================================================================
-- V014: Staged Journal Templates untuk Fasilitas KUR BRI (FAS-139 / FAS-97 / FAS-124)
--
-- Keputusan Finance Controller FAS-97 (K-1 s.d. K-4):
-- 1. K-1: Pencairan KUR BRI (Disbursement) - DETAILED, active = FALSE (staged)
-- 2. K-2: Angsuran KUR BRI (Installment) - DETAILED, active = FALSE (staged)
-- 3. K-2 §6.7: Akrual Bunga KUR Akhir Bulan - DETAILED, active = FALSE (staged)
-- 4. K-3: Reklasifikasi Pokok KUR Lancar - SIMPLE, active = FALSE (staged)
-- 5. K-4: Biaya dan Denda KUR - SIMPLE, active = FALSE (staged)
-- ============================================================================

DO $$
DECLARE
    v_acc_1_1_10 UUID; -- Bank BRI — Rekening KUR
    v_acc_1_1_11 UUID; -- Angsuran Dibayar Dimuka
    v_acc_2_1_30 UUID; -- Hutang Bank Jangka Pendek — KUR
    v_acc_2_1_31 UUID; -- Hutang Bunga & Biaya Pinjaman
    v_acc_2_2_01 UUID; -- Hutang Bank Jangka Panjang — KUR
    v_acc_5_2_03 UUID; -- Beban Bunga Pinjaman
    v_acc_5_2_04 UUID; -- Beban Provisi, Administrasi & Denda Pinjaman

    v_tmpl_k1_id UUID;
    v_tmpl_k2_id UUID;
    v_tmpl_k2_accrual_id UUID;
    v_tmpl_k3_id UUID;
    v_tmpl_k4_id UUID;
BEGIN
    -- 1. Resolve Account UUIDs from chart_of_accounts
    SELECT id INTO v_acc_1_1_10 FROM chart_of_accounts WHERE account_code = '1.1.10';
    SELECT id INTO v_acc_1_1_11 FROM chart_of_accounts WHERE account_code = '1.1.11';
    SELECT id INTO v_acc_2_1_30 FROM chart_of_accounts WHERE account_code = '2.1.30';
    SELECT id INTO v_acc_2_1_31 FROM chart_of_accounts WHERE account_code = '2.1.31';
    SELECT id INTO v_acc_2_2_01 FROM chart_of_accounts WHERE account_code = '2.2.01';
    SELECT id INTO v_acc_5_2_03 FROM chart_of_accounts WHERE account_code = '5.2.03';
    SELECT id INTO v_acc_5_2_04 FROM chart_of_accounts WHERE account_code = '5.2.04';

    -- ------------------------------------------------------------------------
    -- Template 1: Pencairan KUR BRI (K-1)
    -- ------------------------------------------------------------------------
    IF NOT EXISTS (SELECT 1 FROM journal_templates WHERE template_name = 'Pencairan KUR BRI (K-1)') THEN
        v_tmpl_k1_id := gen_random_uuid();
        INSERT INTO journal_templates (
            id, row_version, template_name, category, cash_flow_category,
            template_type, description, is_system, active, version,
            is_current_version, usage_count, semantic_description, keywords
        ) VALUES (
            v_tmpl_k1_id, 0, 'Pencairan KUR BRI (K-1)', 'RECEIPT', 'FINANCING',
            'DETAILED', 'Pencairan pinjaman KUR BRI (K-1). Liabilitas diakui sebesar pokok bruto, kas neto dicatat ke 1.1.10. Staged/Inactive per FAS-97.',
            FALSE, FALSE, 1,
            TRUE, 0, 'Template pencairan pinjaman KUR dengan pemisahan pokok jangka pendek dan panjang serta potongan provisi',
            ARRAY['kur', 'bri', 'pencairan', 'disbursement', 'pinjaman', 'loan']
        );

        -- Lines
        INSERT INTO journal_template_lines (id, id_journal_template, id_account, position, formula, line_order, description, account_hint)
        VALUES (gen_random_uuid(), v_tmpl_k1_id, v_acc_1_1_10, 'DEBIT', 'gross_principal - fee_withheld', 1, 'Penerimaan kas neto pencairan KUR', '1.1.10 - Bank BRI — Rekening KUR');

        INSERT INTO journal_template_lines (id, id_journal_template, id_account, position, formula, line_order, description, account_hint)
        VALUES (gen_random_uuid(), v_tmpl_k1_id, v_acc_5_2_04, 'DEBIT', 'fee_withheld', 2, 'Biaya provisi / administrasi dipotong di muka', '5.2.04 - Beban Provisi, Administrasi & Denda Pinjaman');

        INSERT INTO journal_template_lines (id, id_journal_template, id_account, position, formula, line_order, description, account_hint)
        VALUES (gen_random_uuid(), v_tmpl_k1_id, v_acc_2_1_30, 'CREDIT', 'current_portion', 3, 'Porsi pokok KUR jangka pendek (<= 12 bulan)', '2.1.30 - Hutang Bank Jangka Pendek — KUR');

        INSERT INTO journal_template_lines (id, id_journal_template, id_account, position, formula, line_order, description, account_hint)
        VALUES (gen_random_uuid(), v_tmpl_k1_id, v_acc_2_2_01, 'CREDIT', 'non_current_portion', 4, 'Porsi pokok KUR jangka panjang (> 12 bulan)', '2.2.01 - Hutang Bank Jangka Panjang — KUR');
    END IF;

    -- ------------------------------------------------------------------------
    -- Template 2: Angsuran KUR BRI (K-2)
    -- ------------------------------------------------------------------------
    IF NOT EXISTS (SELECT 1 FROM journal_templates WHERE template_name = 'Angsuran KUR BRI (K-2)') THEN
        v_tmpl_k2_id := gen_random_uuid();
        INSERT INTO journal_templates (
            id, row_version, template_name, category, cash_flow_category,
            template_type, description, is_system, active, version,
            is_current_version, usage_count, semantic_description, keywords
        ) VALUES (
            v_tmpl_k2_id, 0, 'Angsuran KUR BRI (K-2)', 'EXPENSE', 'FINANCING',
            'DETAILED', 'Angsuran pinjaman KUR BRI bulanan (K-2) dengan pemisahan pokok, bunga, dan biaya/denda. Staged/Inactive per FAS-97.',
            FALSE, FALSE, 1,
            TRUE, 0, 'Template angsuran bulanan KUR dengan pemecahan pokok, beban bunga, dan biaya administrasi',
            ARRAY['kur', 'bri', 'angsuran', 'installment', 'cicilan', 'pokok', 'bunga']
        );

        -- Lines
        INSERT INTO journal_template_lines (id, id_journal_template, id_account, position, formula, line_order, description, account_hint)
        VALUES (gen_random_uuid(), v_tmpl_k2_id, v_acc_2_1_30, 'DEBIT', 'principal', 1, 'Pelunasan pokok angsuran KUR', '2.1.30 - Hutang Bank Jangka Pendek — KUR');

        INSERT INTO journal_template_lines (id, id_journal_template, id_account, position, formula, line_order, description, account_hint)
        VALUES (gen_random_uuid(), v_tmpl_k2_id, v_acc_5_2_03, 'DEBIT', 'interest', 2, 'Beban bunga pembiayaan KUR', '5.2.03 - Beban Bunga Pinjaman');

        INSERT INTO journal_template_lines (id, id_journal_template, id_account, position, formula, line_order, description, account_hint)
        VALUES (gen_random_uuid(), v_tmpl_k2_id, v_acc_5_2_04, 'DEBIT', 'fee', 3, 'Biaya admin / denda angsuran (jika ada)', '5.2.04 - Beban Provisi, Administrasi & Denda Pinjaman');

        INSERT INTO journal_template_lines (id, id_journal_template, id_account, position, formula, line_order, description, account_hint)
        VALUES (gen_random_uuid(), v_tmpl_k2_id, v_acc_1_1_10, 'CREDIT', 'cash_paid', 4, 'Kas keluar pembayaran angsuran KUR', '1.1.10 - Bank BRI — Rekening KUR');
    END IF;

    -- ------------------------------------------------------------------------
    -- Template 3: Akrual Bunga KUR Akhir Bulan (K-2)
    -- ------------------------------------------------------------------------
    IF NOT EXISTS (SELECT 1 FROM journal_templates WHERE template_name = 'Akrual Bunga KUR Akhir Bulan (K-2)') THEN
        v_tmpl_k2_accrual_id := gen_random_uuid();
        INSERT INTO journal_templates (
            id, row_version, template_name, category, cash_flow_category,
            template_type, description, is_system, active, version,
            is_current_version, usage_count, semantic_description, keywords
        ) VALUES (
            v_tmpl_k2_accrual_id, 0, 'Akrual Bunga KUR Akhir Bulan (K-2)', 'EXPENSE', 'OPERATING',
            'DETAILED', 'Akrual beban bunga dan biaya KUR akhir bulan jika angsuran belum terbayar (K-2 §6.7). Staged/Inactive per FAS-97.',
            FALSE, FALSE, 1,
            TRUE, 0, 'Template akrual bunga dan denda KUR pada akhir bulan berjalan saat angsuran belum terbayar',
            ARRAY['kur', 'akrual', 'accrual', 'bunga', 'akhir bulan', 'closing']
        );

        -- Lines
        INSERT INTO journal_template_lines (id, id_journal_template, id_account, position, formula, line_order, description, account_hint)
        VALUES (gen_random_uuid(), v_tmpl_k2_accrual_id, v_acc_5_2_03, 'DEBIT', 'interest', 1, 'Pengakuan beban bunga berjalan', '5.2.03 - Beban Bunga Pinjaman');

        INSERT INTO journal_template_lines (id, id_journal_template, id_account, position, formula, line_order, description, account_hint)
        VALUES (gen_random_uuid(), v_tmpl_k2_accrual_id, v_acc_5_2_04, 'DEBIT', 'fee', 2, 'Pengakuan beban denda/biaya tertunggak', '5.2.04 - Beban Provisi, Administrasi & Denda Pinjaman');

        INSERT INTO journal_template_lines (id, id_journal_template, id_account, position, formula, line_order, description, account_hint)
        VALUES (gen_random_uuid(), v_tmpl_k2_accrual_id, v_acc_2_1_31, 'CREDIT', 'accrued_amount', 3, 'Hutang bunga & biaya pinjaman akrual', '2.1.31 - Hutang Bunga & Biaya Pinjaman');
    END IF;

    -- ------------------------------------------------------------------------
    -- Template 4: Reklasifikasi Pokok KUR Lancar (K-3)
    -- ------------------------------------------------------------------------
    IF NOT EXISTS (SELECT 1 FROM journal_templates WHERE template_name = 'Reklasifikasi Pokok KUR Lancar (K-3)') THEN
        v_tmpl_k3_id := gen_random_uuid();
        INSERT INTO journal_templates (
            id, row_version, template_name, category, cash_flow_category,
            template_type, description, is_system, active, version,
            is_current_version, usage_count, semantic_description, keywords
        ) VALUES (
            v_tmpl_k3_id, 0, 'Reklasifikasi Pokok KUR Lancar (K-3)', 'TRANSFER', 'OPERATING',
            'SIMPLE', 'Reklasifikasi bulanan pokok KUR dari jangka panjang ke jangka pendek (K-3). Staged/Inactive per FAS-97.',
            FALSE, FALSE, 1,
            TRUE, 0, 'Template reklasifikasi bulanan pokok pinjaman jangka panjang ke jangka pendek',
            ARRAY['kur', 'reklasifikasi', 'reclass', 'jangka panjang', 'jangka pendek']
        );

        -- Lines
        INSERT INTO journal_template_lines (id, id_journal_template, id_account, position, formula, line_order, description, account_hint)
        VALUES (gen_random_uuid(), v_tmpl_k3_id, v_acc_2_2_01, 'DEBIT', 'amount', 1, 'Pengurangan pokok KUR jangka panjang', '2.2.01 - Hutang Bank Jangka Panjang — KUR');

        INSERT INTO journal_template_lines (id, id_journal_template, id_account, position, formula, line_order, description, account_hint)
        VALUES (gen_random_uuid(), v_tmpl_k3_id, v_acc_2_1_30, 'CREDIT', 'amount', 2, 'Penambahan pokok KUR jangka pendek', '2.1.30 - Hutang Bank Jangka Pendek — KUR');
    END IF;

    -- ------------------------------------------------------------------------
    -- Template 5: Biaya dan Denda KUR (K-4)
    -- ------------------------------------------------------------------------
    IF NOT EXISTS (SELECT 1 FROM journal_templates WHERE template_name = 'Biaya dan Denda KUR (K-4)') THEN
        v_tmpl_k4_id := gen_random_uuid();
        INSERT INTO journal_templates (
            id, row_version, template_name, category, cash_flow_category,
            template_type, description, is_system, active, version,
            is_current_version, usage_count, semantic_description, keywords
        ) VALUES (
            v_tmpl_k4_id, 0, 'Biaya dan Denda KUR (K-4)', 'EXPENSE', 'OPERATING',
            'SIMPLE', 'Biaya administrasi, provisi, atau denda pinjaman KUR terpisah (K-4). Staged/Inactive per FAS-97.',
            FALSE, FALSE, 1,
            TRUE, 0, 'Template biaya administrasi, provisi, dan denda pinjaman KUR',
            ARRAY['kur', 'denda', 'biaya', 'provisi', 'administrasi', 'penalty']
        );

        -- Lines
        INSERT INTO journal_template_lines (id, id_journal_template, id_account, position, formula, line_order, description, account_hint)
        VALUES (gen_random_uuid(), v_tmpl_k4_id, v_acc_5_2_04, 'DEBIT', 'amount', 1, 'Beban biaya provisi / denda KUR', '5.2.04 - Beban Provisi, Administrasi & Denda Pinjaman');

        INSERT INTO journal_template_lines (id, id_journal_template, id_account, position, formula, line_order, description, account_hint)
        VALUES (gen_random_uuid(), v_tmpl_k4_id, v_acc_1_1_10, 'CREDIT', 'amount', 2, 'Kas keluar biaya / denda KUR', '1.1.10 - Bank BRI — Rekening KUR');
    END IF;

END $$;
