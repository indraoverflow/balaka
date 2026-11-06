package com.artivisi.accountingfinance.enums;

import lombok.Getter;

/**
 * Legal-borrower / beneficial-debtor attribution structure per FAS-97 §13 / FAS-124.
 *
 * <p>Structures:
 * <ul>
 *   <li><b>A</b>: Company is beneficial debtor; Iga Mentari is legal borrower only (default accounting mapping).</li>
 *   <li><b>B</b>: Iga on-lends proceeds to company; company owes Iga (Utang Pihak Berelasi 2.1.32).</li>
 *   <li><b>C</b>: Proceeds are founder capital contribution (Modal Disetor 3.1.01).</li>
 *   <li><b>D</b>: MOU is unsigned or silent (No GL posting; tracker only). DEFAULT OFF.</li>
 * </ul>
 */
@Getter
public enum KurLegalBorrowerStructure {
    STRUCTURE_A("A", "Company is beneficial debtor; Iga Mentari legal borrower only"),
    STRUCTURE_B("B", "Iga on-lends proceeds to company; company owes Iga (2.1.32)"),
    STRUCTURE_C("C", "Proceeds are founder capital contribution (3.1.01)"),
    STRUCTURE_D("D", "MOU unsigned or silent (no GL posting; tracker only)");

    private final String code;
    private final String description;

    KurLegalBorrowerStructure(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public boolean isPostingAllowed() {
        return this == STRUCTURE_A;
    }
}
