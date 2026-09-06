import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public final class DatabaseTlsProbe {
    public static void main(String[] args) throws Exception {
        var properties = new Properties();
        properties.setProperty("user", "assetpulse_tls");
        properties.setProperty("password", System.getenv("ASSETPULSE_TLS_PASSWORD"));
        properties.setProperty("sslmode", "verify-full");
        properties.setProperty("sslrootcert", "/etc/ssl/certs/ca-certificates.crt");
        properties.setProperty("channelBinding", "require");
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "5");
        Class.forName("org.postgresql.Driver");

        try (var connection =
                        DriverManager.getConnection(
                                "jdbc:postgresql://" + args[0] + ":5432/assetpulse_tls", properties);
                var statement = connection.createStatement();
                var result =
                        statement.executeQuery(
                                "SELECT ssl, current_user FROM pg_stat_ssl WHERE pid = pg_backend_pid()")) {
            if (!args[1].equals("trusted")
                    || !result.next()
                    || !result.getBoolean(1)
                    || !result.getString(2).equals("assetpulse_tls")) {
                throw new AssertionError("Unexpected database TLS result");
            }
        } catch (SQLException failure) {
            boolean expected = false;
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                expected |=
                        (args[1].equals("wrong-host")
                                        && String.valueOf(cause.getMessage())
                                                .contains("could not be verified"))
                                || (args[1].equals("untrusted")
                                        && cause instanceof java.security.cert.CertPathBuilderException);
            }
            if (!expected) {
                throw failure;
            }
        }
        System.out.println("PgJDBC TLS check passed: " + args[1]);
    }
}
