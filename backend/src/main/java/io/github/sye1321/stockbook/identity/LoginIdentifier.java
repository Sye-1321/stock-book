package io.github.sye1321.stockbook.identity;

import java.util.Locale;

public final class LoginIdentifier {

    private LoginIdentifier() {}

    public static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
