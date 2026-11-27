package io.github.sye1321.stockbook.finance;

import java.time.ZoneId;

public record FinanceSettings(String currencyCode, ZoneId timezone) {}
