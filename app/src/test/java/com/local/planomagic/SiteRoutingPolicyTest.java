package com.local.planomagic;

import org.junit.Test;
import static org.junit.Assert.*;

public class SiteRoutingPolicyTest {
    @Test public void atlassianCloudIsBrowserByDefault() {
        assertTrue(SiteRoutingPolicy.useExternalBrowser(
                "wonderbox.atlassian.net", "FORM", "AUTO"));
        assertTrue(SiteRoutingPolicy.useExternalBrowser(
                "wonderbox.atlassian.net", "PENDING", "AUTO"));
        assertFalse(SiteRoutingPolicy.useExternalBrowser(
                "wonderbox.atlassian.net", "FORM", "IN_APP"));
    }

    @Test public void professionalAndNormalSitesUseDifferentModes() {
        assertTrue(SiteRoutingPolicy.useExternalBrowser("jira.example.org", "SSO", "AUTO"));
        assertTrue(SiteRoutingPolicy.useExternalBrowser("jira.example.org", "MFA", "AUTO"));
        assertFalse(SiteRoutingPolicy.useExternalBrowser("plano.wonderbox.com", "FORM", "AUTO"));
        assertTrue(SiteRoutingPolicy.useExternalBrowser("plano.wonderbox.com", "FORM", "EDGE"));
    }

    @Test public void identityProvidersAndLookalikesDoNotReceiveStoredPasswords() {
        assertTrue(SiteRoutingPolicy.isIdentityProviderHost("id.atlassian.com"));
        assertTrue(SiteRoutingPolicy.isIdentityProviderHost("login.microsoftonline.com"));
        assertFalse(SiteRoutingPolicy.isIdentityProviderHost("fake-login.microsoftonline.com.evil.test"));
        assertFalse(SiteRoutingPolicy.mayInjectCredentials("wonderbox.atlassian.net",
                "id.atlassian.com", "FORM"));
        assertFalse(SiteRoutingPolicy.mayInjectCredentials("login.microsoftonline.com",
                "login.microsoftonline.com", "FORM"));
        assertFalse(SiteRoutingPolicy.mayInjectCredentials("plano.wonderbox.com",
                "plano.wonderbox.com.evil.test", "FORM"));
        assertTrue(SiteRoutingPolicy.mayInjectCredentials("plano.wonderbox.com",
                "PLANO.WONDERBOX.COM", "FORM"));
    }

    @Test public void noCredentialsAreInjectedForSsoOrMfa() {
        assertFalse(SiteRoutingPolicy.mayInjectCredentials("jira.example.org",
                "jira.example.org", "SSO"));
        assertFalse(SiteRoutingPolicy.mayInjectCredentials("jira.example.org",
                "jira.example.org", "MFA"));
    }

    @Test public void vpnHttpIsRestrictedToExactCorporateSuffix() {
        assertTrue(SiteRoutingPolicy.isAllowedInternalHttp(
                "http", "wonderview.wonderbox.vpn"));
        assertTrue(SiteRoutingPolicy.isAllowedInternalHttp(
                "HTTP", "WONDERVIEW.WONDERBOX.VPN"));
        assertFalse(SiteRoutingPolicy.isAllowedInternalHttp(
                "https", "wonderview.wonderbox.vpn"));
        assertFalse(SiteRoutingPolicy.isAllowedInternalHttp(
                "http", "wonderbox.vpn.evil.test"));
        assertFalse(SiteRoutingPolicy.isAllowedInternalHttp(
                "http", "evilwonderbox.vpn"));
        assertFalse(SiteRoutingPolicy.isAllowedInternalHttp(
                "http", "example.com"));
    }

    @Test public void twoStepAutomaticUsesOnlyExplicitExactHost() {
        assertTrue(SiteRoutingPolicy.mayInjectCredentials(
                "nova.wonderbox.com", "nova.wonderbox.com", "BASIC_FORM"));
        assertFalse(SiteRoutingPolicy.mayInjectCredentials(
                "nova.wonderbox.com", "login.microsoftonline.com", "BASIC_FORM"));
        assertFalse(SiteRoutingPolicy.mayInjectCredentials(
                "nova.wonderbox.com", "nova.wonderbox.com.evil.example", "BASIC_FORM"));
        assertFalse(SiteRoutingPolicy.mayInjectCredentials(
                "id.atlassian.com", "id.atlassian.com", "BASIC_FORM"));
    }

    @Test public void onlyWonderViewNeedsVpnByDefaultNotNova() {
        assertTrue(SiteRoutingPolicy.requiresVpn(
                "http", "wonderview.wonderbox.vpn", false));
        assertFalse(SiteRoutingPolicy.requiresVpn(
                "https", "nova.wonderbox.com", false));
        assertFalse(SiteRoutingPolicy.requiresVpn(
                "https", "plano.wonderbox.com", false));
        assertTrue(SiteRoutingPolicy.requiresVpn(
                "https", "an-internal-service.example", true));
    }

    @Test public void wonderviewLegacyHttpsCanBeCorrectedButOtherSitesAreNotDowngraded() {
        assertTrue(SiteRoutingPolicy.isKnownWonderViewHttpsMismatch(
                "https", "wonderview.wonderbox.vpn"));
        assertTrue(SiteRoutingPolicy.isKnownWonderViewHttpsMismatch(
                "HTTPS", "WONDERVIEW.WONDERBOX.VPN"));
        assertFalse(SiteRoutingPolicy.isKnownWonderViewHttpsMismatch(
                "http", "wonderview.wonderbox.vpn"));
        assertFalse(SiteRoutingPolicy.isKnownWonderViewHttpsMismatch(
                "https", "wonderview.wonderbox.vpn.evil.example"));
        assertFalse(SiteRoutingPolicy.isKnownWonderViewHttpsMismatch(
                "https", "nova.wonderbox.com"));
        assertFalse(SiteRoutingPolicy.isKnownWonderViewHttpsMismatch(
                "https", "another.wonderbox.vpn"));
    }

    @Test public void autoLockIsEvaluatedBeforeShortcutOpens() {
        long backgroundAt = 1_000_000L;
        assertFalse(SiteRoutingPolicy.shouldRelock(backgroundAt, backgroundAt + 59000, 60));
        assertTrue(SiteRoutingPolicy.shouldRelock(backgroundAt, backgroundAt + 60000, 60));
        assertFalse(SiteRoutingPolicy.shouldRelock(backgroundAt, backgroundAt + 360000, 0));
        assertFalse(SiteRoutingPolicy.shouldRelock(0, backgroundAt + 360000, 60));
    }
}
