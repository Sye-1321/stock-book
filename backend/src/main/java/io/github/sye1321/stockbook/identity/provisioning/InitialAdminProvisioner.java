package io.github.sye1321.stockbook.identity.provisioning;

import io.github.sye1321.stockbook.StockBookApplication;
import java.io.Console;
import java.io.IOException;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

public final class InitialAdminProvisioner {

    private InitialAdminProvisioner() {}

    public static void main(String[] args) throws IOException {
        SpringApplication application = new SpringApplication(StockBookApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false);

        try (ConfigurableApplicationContext context = application.run("--spring.main.banner-mode=off")) {
            Credentials credentials = readCredentials();
            try {
                context.getBean(InitialAdminProvisioningService.class)
                        .provision(credentials.loginIdentifier(), credentials.displayName(), credentials.password());
                System.out.println("Initial administrator provisioned successfully.");
            } finally {
                Arrays.fill(credentials.password(), '\0');
            }
        }
    }

    private static Credentials readCredentials() throws IOException {
        Console console = System.console();
        if (console != null) {
            String loginIdentifier = console.readLine("Login identifier: ");
            String displayName = console.readLine("Display name: ");
            char[] password = console.readPassword("Password: ");
            char[] confirmation = console.readPassword("Confirm password: ");
            try {
                requireMatchingPasswords(password, confirmation);
                return new Credentials(loginIdentifier, displayName, password);
            } finally {
                Arrays.fill(confirmation, '\0');
            }
        }

        String loginIdentifier = System.getenv("STOCKBOOK_ADMIN_LOGIN");
        String displayName = System.getenv("STOCKBOOK_ADMIN_DISPLAY_NAME");
        String password = System.getenv("STOCKBOOK_ADMIN_PASSWORD");
        if (loginIdentifier != null && displayName != null && password != null) {
            return new Credentials(loginIdentifier, displayName, password.toCharArray());
        }

        throw new IllegalStateException(
                "A secure console is unavailable; set STOCKBOOK_ADMIN_LOGIN, "
                        + "STOCKBOOK_ADMIN_DISPLAY_NAME, and STOCKBOOK_ADMIN_PASSWORD for this process");
    }

    private static void requireMatchingPasswords(char[] password, char[] confirmation) {
        if (!Arrays.equals(password, confirmation)) {
            Arrays.fill(password, '\0');
            throw new IllegalArgumentException("Passwords do not match");
        }
    }

    private record Credentials(String loginIdentifier, String displayName, char[] password) {}
}
