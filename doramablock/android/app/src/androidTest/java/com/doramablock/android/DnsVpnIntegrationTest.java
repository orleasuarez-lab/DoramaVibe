package com.doramablock.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

@RunWith(AndroidJUnit4.class)
public final class DnsVpnIntegrationTest {
    private static final String PREFS = "doramablock_state";
    private static final String PREF_ACTIVE = "active";
    private static final String PREF_BLOCKED = "blocked_count";

    @Test
    public void vpnReturnsNxDomainForBlockedDomain() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        UiDevice device = UiDevice.getInstance(
                InstrumentationRegistry.getInstrumentation());

        try {
            Intent launch = new Intent(context, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);

            if (!prefs.getBoolean(PREF_ACTIVE, false)) {
                UiObject2 startButton = findStartButton(device);
                assertNotNull("DoramaBlock start button was not visible", startButton);
                startButton.click();

                UiObject2 consent = waitForVpnConsent(device);
                assertNotNull("Android VPN permission dialog did not appear", consent);
                consent.click();
            }

            assertTrue("VPN service did not become active",
                    waitForPreference(prefs, PREF_ACTIVE, true, 15000L));

            long blockedBefore = prefs.getLong(PREF_BLOCKED, 0L);
            byte[] query = dnsQuery("doubleclick.net");

            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(10000);
                InetAddress localDns = InetAddress.getByName("10.111.0.1");
                DatagramPacket outgoing =
                        new DatagramPacket(query, query.length, localDns, 53);
                socket.send(outgoing);

                byte[] responseBuffer = new byte[2048];
                DatagramPacket incoming =
                        new DatagramPacket(responseBuffer, responseBuffer.length);
                socket.receive(incoming);

                assertTrue("DNS response was too short", incoming.getLength() >= 12);
                assertEquals("DNS transaction ID did not match",
                        query[0] & 0xff, incoming.getData()[0] & 0xff);
                assertEquals("DNS response was not marked as a response",
                        0x80, incoming.getData()[2] & 0x80);
                assertEquals("Blocked domain did not return NXDOMAIN",
                        3, incoming.getData()[3] & 0x0f);
            }

            assertTrue("Blocked DNS counter did not increase",
                    waitForCounterIncrease(prefs, blockedBefore, 5000L));
        } finally {
            Intent stop = new Intent(context, AdsVpnService.class);
            stop.setAction(AdsVpnService.ACTION_STOP);
            context.startService(stop);
        }
    }

    private static UiObject2 findStartButton(UiDevice device) {
        UiObject2 button = device.wait(
                Until.findObject(By.text("ATIVAR FILTRO DNS")), 5000L);
        for (int i = 0; button == null && i < 4; i++) {
            device.swipe(500, 800, 500, 300, 10);
            button = device.wait(
                    Until.findObject(By.text("ATIVAR FILTRO DNS")), 1500L);
        }
        return button;
    }

    private static UiObject2 waitForVpnConsent(UiDevice device) {
        UiObject2 button = device.wait(Until.findObject(By.text("OK")), 10000L);
        if (button == null) {
            button = device.wait(Until.findObject(By.text("Allow")), 1000L);
        }
        if (button == null) {
            button = device.wait(Until.findObject(By.text("Permitir")), 1000L);
        }
        return button;
    }

    private static boolean waitForPreference(
            SharedPreferences prefs, String key, boolean expected, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (prefs.getBoolean(key, false) == expected) {
                return true;
            }
            SystemClock.sleep(200L);
        }
        return prefs.getBoolean(key, false) == expected;
    }

    private static boolean waitForCounterIncrease(
            SharedPreferences prefs, long before, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (prefs.getLong(PREF_BLOCKED, 0L) > before) {
                return true;
            }
            SystemClock.sleep(200L);
        }
        return prefs.getLong(PREF_BLOCKED, 0L) > before;
    }

    private static byte[] dnsQuery(String domain) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x56);
        out.write(0x78);
        out.write(0x01);
        out.write(0x00);
        out.write(0x00);
        out.write(0x01);
        out.write(0x00);
        out.write(0x00);
        out.write(0x00);
        out.write(0x00);
        out.write(0x00);
        out.write(0x00);

        for (String label : domain.split("\\.")) {
            byte[] bytes = label.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            out.write(bytes.length);
            out.write(bytes);
        }
        out.write(0x00);
        out.write(0x00);
        out.write(0x01);
        out.write(0x00);
        out.write(0x01);
        return out.toByteArray();
    }
}
