package com.local.planomagic;

import java.util.Locale;

/**
 * Pure routing decisions. No Android state, cookies, credentials or network calls.
 * Keeping these rules independently testable prevents a loaded login page from
 * being confused with a completed authentication.
 */
final class SiteRoutingPolicy {
    private SiteRoutingPolicy() {}

    static boolean isAtlassianCloudHost(String host) {
        if (host == null) return false;
        String value = host.toLowerCase(Locale.ROOT);
        return value.endsWith(".atlassian.net")
                && value.length() > ".atlassian.net".length();
    }

    static boolean isIdentityProviderHost(String host) {
        if (host == null) return false;
        String value = host.toLowerCase(Locale.ROOT);

        return value.equals("id.atlassian.com")
                || value.equals("login.microsoftonline.com")
                || value.equals("login.microsoftonline.us")
                || value.equals("accounts.google.com")
                || value.equals("okta.com")
                || value.endsWith(".okta.com")
                || value.equals("auth0.com")
                || value.endsWith(".auth0.com")
                || value.equals("onelogin.com")
                || value.endsWith(".onelogin.com")
                || value.equals("pingidentity.com")
                || value.endsWith(".pingidentity.com");
    }

    static boolean useExternalBrowser(String host, String authType, String openingMode) {
        if ("IN_APP".equals(openingMode)) return false;
        if ("BROWSER".equals(openingMode) || "EDGE".equals(openingMode)
                || "CHROME".equals(openingMode) || "SAMSUNG".equals(openingMode)) return true;
        return isAtlassianCloudHost(host) || "SSO".equals(authType)
                || "MFA".equals(authType);
    }

    static boolean mayInjectCredentials(String configuredHost, String currentHost,
                                        String authType) {
        if (!"FORM".equals(authType) && !"BASIC".equals(authType)) return false;
        if (configuredHost == null || currentHost == null) return false;
        if (!configuredHost.equalsIgnoreCase(currentHost)) return false;
        return !isIdentityProviderHost(currentHost);
    }

    static boolean shouldRelock(long backgroundAtMillis, long nowMillis, int seconds) {
        return backgroundAtMillis > 0 && seconds > 0 && nowMillis >= backgroundAtMillis
                && (nowMillis - backgroundAtMillis) >= seconds * 1000L;
    }
}
