package io.auralis.bootstrap;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Secrets deliberately have no generated toString; validation never echoes supplied values. */
@ConfigurationProperties("auralis")
public class OnlineProperties {
    private String jdbcUrl;
    private String databaseUser;
    private String databasePassword;
    private String productOrigin;
    private String pythonUrl;
    private String serviceToken;
    private String contractRevision;

    public void validate(String revision) {
        try {
            if (blank(jdbcUrl) || !jdbcUrl.startsWith("jdbc:postgresql://")) invalid();
            URI db = URI.create(jdbcUrl.substring(5));
            if (db.getHost() == null
                    || db.getUserInfo() != null
                    || db.getQuery() != null
                    || db.getFragment() != null
                    || !db.getPath().matches("/auralis_core(?:_verify)?")) invalid();
            if (blank(databaseUser)
                    || blank(databasePassword)
                    || databaseUser.equals("postgres")
                    || databaseUser.endsWith("_migrator")
                    || databaseUser.endsWith("_importer")) invalid();
            URI origin = URI.create(productOrigin);
            if (!origin.equals(
                            new URI(
                                    origin.getScheme(),
                                    null,
                                    origin.getHost(),
                                    origin.getPort(),
                                    null,
                                    null,
                                    null))
                    || !("https".equals(origin.getScheme())
                            || ("http".equals(origin.getScheme()) && loopback(origin)))) invalid();
            URI python = URI.create(pythonUrl);
            if (!"http".equals(python.getScheme())
                    || !loopback(python)
                    || python.getPort() < 1
                    || python.getUserInfo() != null
                    || python.getQuery() != null
                    || python.getFragment() != null
                    || !python.getPath().isEmpty()) invalid();
            if (blank(serviceToken)
                    || !serviceToken.matches("[A-Za-z0-9_\\-]{32,256}")
                    || !revision.equals(contractRevision)) invalid();
        } catch (Exception failure) {
            throw new IllegalArgumentException("INVALID_ONLINE_CONFIGURATION");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean loopback(URI uri) {
        return "127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost());
    }

    private static void invalid() {
        throw new IllegalArgumentException("INVALID_ONLINE_CONFIGURATION");
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String value) {
        jdbcUrl = value;
    }

    public String getDatabaseUser() {
        return databaseUser;
    }

    public void setDatabaseUser(String value) {
        databaseUser = value;
    }

    public String getDatabasePassword() {
        return databasePassword;
    }

    public void setDatabasePassword(String value) {
        databasePassword = value;
    }

    public String getProductOrigin() {
        return productOrigin;
    }

    public void setProductOrigin(String value) {
        productOrigin = value;
    }

    public String getPythonUrl() {
        return pythonUrl;
    }

    public void setPythonUrl(String value) {
        pythonUrl = value;
    }

    public String getServiceToken() {
        return serviceToken;
    }

    public void setServiceToken(String value) {
        serviceToken = value;
    }

    public String getContractRevision() {
        return contractRevision;
    }

    public void setContractRevision(String value) {
        contractRevision = value;
    }
}
