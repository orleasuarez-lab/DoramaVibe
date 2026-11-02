package com.doramablock.android;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Small, conservative starter list.
 *
 * This is intentionally not advertised as a complete ad list. A domain-level
 * filter cannot reliably separate an advertisement from first-party content
 * when both are served by the same host.
 */
public final class DomainBlocklist {
    private static final Set<String> BLOCKED;

    static {
        Set<String> domains = new HashSet<>();
        domains.add("doubleclick.net");
        domains.add("googlesyndication.com");
        domains.add("googleadservices.com");
        domains.add("adservice.google.com");
        domains.add("adnxs.com");
        domains.add("adform.net");
        domains.add("taboola.com");
        domains.add("outbrain.com");
        domains.add("adsrvr.org");
        domains.add("criteo.com");
        domains.add("rubiconproject.com");
        domains.add("pubmatic.com");
        domains.add("openx.net");
        domains.add("scorecardresearch.com");
        domains.add("moatads.com");
        domains.add("serving-sys.com");
        BLOCKED = Collections.unmodifiableSet(domains);
    }

    private DomainBlocklist() {
    }

    public static boolean isBlocked(String rawDomain) {
        String domain = normalize(rawDomain);
        if (domain.isEmpty()) {
            return false;
        }

        for (String blocked : BLOCKED) {
            if (domain.equals(blocked) || domain.endsWith("." + blocked)) {
                return true;
            }
        }
        return false;
    }

    public static String normalize(String rawDomain) {
        if (rawDomain == null) {
            return "";
        }

        String domain = rawDomain.trim().toLowerCase(Locale.ROOT);
        while (domain.endsWith(".")) {
            domain = domain.substring(0, domain.length() - 1);
        }

        if (domain.isEmpty() || domain.length() > 253 || domain.contains("..")) {
            return "";
        }
        if (domain.startsWith(".") || domain.contains("/") || domain.contains(":")) {
            return "";
        }
        return domain;
    }

    public static int size() {
        return BLOCKED.size();
    }
}
