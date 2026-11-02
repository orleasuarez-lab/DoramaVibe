package com.doramablock.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * First functional DoramaBlock Android MVP.
 *
 * The VPN routes only the app's synthetic IPv4 DNS server (/32) into the TUN.
 * Normal app traffic is not routed through the tunnel. DNS queries are checked
 * against a conservative local domain list; permitted queries are forwarded
 * through a protected UDP socket to the configured upstream resolver.
 */
public final class AdsVpnService extends VpnService {
    public static final String ACTION_START = "com.doramablock.android.START";
    public static final String ACTION_STOP = "com.doramablock.android.STOP";

    private static final String PREFS = "doramablock_state";
    private static final String PREF_ACTIVE = "active";
    private static final String PREF_BLOCKED = "blocked_count";
    private static final String PREF_ALLOWED = "allowed_count";

    private static final String CHANNEL_ID = "doramablock_vpn";
    private static final int NOTIFICATION_ID = 4107;

    private static final String VPN_ADDRESS = "10.111.0.2";
    private static final String DNS_ADDRESS = "10.111.0.1";
    private static final String UPSTREAM_DNS = "1.1.1.1";

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ParcelFileDescriptor tunInterface;
    private Thread worker;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopFiltering();
            return START_NOT_STICKY;
        }

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Preparando filtro DNS..."));

        if (!running.get()) {
            startFiltering();
        }
        return START_STICKY;
    }

    private void startFiltering() {
        try {
            Builder builder = new Builder()
                    .setSession("DoramaBlock - filtro DNS")
                    .setMtu(1500)
                    .addAddress(VPN_ADDRESS, 32)
                    .addDnsServer(DNS_ADDRESS)
                    // Only DNS addressed to this synthetic resolver enters the TUN.
                    .addRoute(DNS_ADDRESS, 32);

            tunInterface = builder.establish();
            if (tunInterface == null) {
                setState(false);
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return;
            }

            running.set(true);
            setState(true);
            worker = new Thread(this::runPacketLoop, "DoramaBlock-DNS");
            worker.start();
            updateNotification("Filtro DNS ativo; internet normal fora do DNS");
        } catch (RuntimeException | IOException error) {
            setState(false);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void runPacketLoop() {
        ParcelFileDescriptor readDescriptor = null;
        ParcelFileDescriptor writeDescriptor = null;

        try {
            readDescriptor = ParcelFileDescriptor.dup(tunInterface.getFileDescriptor());
            writeDescriptor = ParcelFileDescriptor.dup(tunInterface.getFileDescriptor());

            try (FileInputStream input =
                         new FileInputStream(readDescriptor.getFileDescriptor());
                 FileOutputStream output =
                         new FileOutputStream(writeDescriptor.getFileDescriptor())) {

                byte[] packetBuffer = new byte[32767];

                while (running.get() && !Thread.currentThread().isInterrupted()) {
                    int length = input.read(packetBuffer);
                    if (length <= 0) {
                        continue;
                    }

                    if (!DnsPacket.isIpv4UdpDnsQuery(packetBuffer, length)) {
                        // The tunnel is configured for one IPv4 DNS /32 only.
                        continue;
                    }

                    byte[] request = new byte[length];
                    System.arraycopy(packetBuffer, 0, request, 0, length);
                    byte[] dnsQuery = DnsPacket.extractDnsPayload(request, length);
                    String domain = DnsPacket.extractQuestionName(dnsQuery);

                    byte[] dnsResponse;
                    if (DomainBlocklist.isBlocked(domain)) {
                        dnsResponse = DnsPacket.buildDnsErrorResponse(dnsQuery, 3); // NXDOMAIN.
                        incrementCounter(PREF_BLOCKED);
                        updateNotification("Domínios bloqueados: " + getCount(PREF_BLOCKED));
                    } else {
                        dnsResponse = forwardDns(dnsQuery);
                        if (dnsResponse == null || dnsResponse.length == 0) {
                            dnsResponse = DnsPacket.buildDnsErrorResponse(dnsQuery, 2); // SERVFAIL.
                        } else {
                            incrementCounter(PREF_ALLOWED);
                        }
                    }

                    byte[] responsePacket =
                            DnsPacket.buildIpv4UdpResponse(request, length, dnsResponse);
                    if (responsePacket.length > 0) {
                        output.write(responsePacket);
                        output.flush();
                    }
                }
            }
        } catch (IOException error) {
            if (running.get()) {
                setState(false);
                running.set(false);
                stopSelf();
            }
        } finally {
            closeQuietly(readDescriptor);
            closeQuietly(writeDescriptor);
            if (running.get()) {
                running.set(false);
                setState(false);
                stopSelf();
            }
        }
    }

    private byte[] forwardDns(byte[] query) {
        if (query == null || query.length == 0) {
            return null;
        }

        try (DatagramSocket socket = new DatagramSocket()) {
            // This socket must bypass our own VPN, otherwise DNS loops forever.
            if (!protect(socket)) {
                return null;
            }
            socket.setSoTimeout(2500);

            InetAddress resolver = InetAddress.getByName(UPSTREAM_DNS);
            DatagramPacket outgoing =
                    new DatagramPacket(query, query.length, resolver, 53);
            socket.send(outgoing);

            byte[] responseBuffer = new byte[4096];
            DatagramPacket incoming =
                    new DatagramPacket(responseBuffer, responseBuffer.length);
            socket.receive(incoming);

            byte[] response = new byte[incoming.getLength()];
            System.arraycopy(incoming.getData(), incoming.getOffset(),
                    response, 0, incoming.getLength());
            return response;
        } catch (IOException error) {
            return null;
        }
    }

    private void stopFiltering() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        closeQuietly(tunInterface);
        tunInterface = null;
        setState(false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onRevoke() {
        stopFiltering();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stopFiltering();
        super.onDestroy();
    }

    private void setState(boolean active) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putBoolean(PREF_ACTIVE, active).apply();
    }

    private void incrementCounter(String key) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.edit().putLong(key, prefs.getLong(key, 0L) + 1L).apply();
    }

    private long getCount(String key) {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getLong(key, 0L);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "DoramaBlock", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Status do filtro DNS local");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String text) {
        Intent openApp = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent =
                PendingIntent.getActivity(this, 0, openApp, flags);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_lock)
                    .setContentTitle("DoramaBlock")
                    .setContentText(text)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .build();
        }

        return new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("DoramaBlock")
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null && running.get()) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor != null) {
            try {
                descriptor.close();
            } catch (IOException ignored) {
                // Descriptor may already have been closed by its stream.
            }
        }
    }
}
