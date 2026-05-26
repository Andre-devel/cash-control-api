package com.cashcontrol.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InstallmentAmountSplitTest {

    @ParameterizedTest(name = "total={0}, n={1}")
    @CsvSource({
            "100.00, 3",
            "100.00, 12",
            "99.99, 3",
            "1.00, 3",
            "999.99, 7",
            "100.00, 2",
            "0.10, 3",
            "1234.56, 11",
            "500.00, 4",
            "333.33, 3"
    })
    void amountsSumToExactTotal(String totalStr, int n) {
        BigDecimal total = new BigDecimal(totalStr);

        BigDecimal base = total.divide(BigDecimal.valueOf(n), 2, RoundingMode.DOWN);
        BigDecimal last = total.subtract(base.multiply(BigDecimal.valueOf(n - 1)));

        List<BigDecimal> amounts = new ArrayList<>();
        for (int i = 0; i < n - 1; i++) amounts.add(base);
        amounts.add(last);

        BigDecimal sum = amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(sum).isEqualByComparingTo(total);
    }

    @Test
    void remainder_goesToLastInstallment() {
        BigDecimal total = new BigDecimal("100.00");
        int n = 3;

        BigDecimal base = total.divide(BigDecimal.valueOf(n), 2, RoundingMode.DOWN);
        BigDecimal last = total.subtract(base.multiply(BigDecimal.valueOf(n - 1)));

        // 100 / 3 = 33.33 base, last = 100.00 - 33.33 * 2 = 33.34
        assertThat(base).isEqualByComparingTo(new BigDecimal("33.33"));
        assertThat(last).isEqualByComparingTo(new BigDecimal("33.34"));
        assertThat(last.compareTo(base)).isGreaterThanOrEqualTo(0);
    }

    @Test
    void evenSplit_noRemainder() {
        BigDecimal total = new BigDecimal("100.00");
        int n = 4;

        BigDecimal base = total.divide(BigDecimal.valueOf(n), 2, RoundingMode.DOWN);
        BigDecimal last = total.subtract(base.multiply(BigDecimal.valueOf(n - 1)));

        assertThat(base).isEqualByComparingTo(new BigDecimal("25.00"));
        assertThat(last).isEqualByComparingTo(new BigDecimal("25.00"));
    }

    @Test
    void allAmountsArePositive() {
        BigDecimal total = new BigDecimal("0.10");
        int n = 3;

        BigDecimal base = total.divide(BigDecimal.valueOf(n), 2, RoundingMode.DOWN);
        BigDecimal last = total.subtract(base.multiply(BigDecimal.valueOf(n - 1)));

        assertThat(base.compareTo(BigDecimal.ZERO)).isGreaterThanOrEqualTo(0);
        assertThat(last.compareTo(BigDecimal.ZERO)).isGreaterThan(0);
    }
}
