package com.doramablock.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class DomainBlocklistTest {
    @Test
    public void blocksExactDomain() {
        assertTrue(DomainBlocklist.isBlocked("doubleclick.net"));
    }

    @Test
    public void blocksSubdomains() {
        assertTrue(DomainBlocklist.isBlocked("pagead.doubleclick.net"));
        assertTrue(DomainBlocklist.isBlocked("a.b.googlesyndication.com"));
    }

    @Test
    public void doesNotBlockLookalikeSuffix() {
        assertFalse(DomainBlocklist.isBlocked("notdoubleclick.net"));
        assertFalse(DomainBlocklist.isBlocked("doubleclick.net.attacker.example"));
    }

    @Test
    public void normalizesCaseAndTrailingDot() {
        assertTrue(DomainBlocklist.isBlocked("PAGEAD.DOUBLECLICK.NET."));
    }

    @Test
    public void toleratesEmptyAndInvalidInput() {
        assertFalse(DomainBlocklist.isBlocked(null));
        assertFalse(DomainBlocklist.isBlocked(""));
        assertFalse(DomainBlocklist.isBlocked("doubleclick.net/path"));
    }
}
