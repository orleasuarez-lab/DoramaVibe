package com.doramablock.android;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;

public class DnsPacketTest {
    @Test
    public void extractsQuestionNameFromDnsQuery() {
        assertEquals("example.com", DnsPacket.extractQuestionName(dnsQuery()));
    }

    @Test
    public void rejectsMalformedDnsQuestion() {
        assertEquals("", DnsPacket.extractQuestionName(new byte[] {1, 2, 3}));
        assertEquals("", DnsPacket.extractQuestionName(new byte[12]));
    }

    @Test
    public void buildsNxDomainResponseWithMatchingTransactionId() {
        byte[] response = DnsPacket.buildDnsErrorResponse(dnsQuery(), 3);
        assertEquals(0x12, response[0] & 0xff);
        assertEquals(0x34, response[1] & 0xff);
        assertEquals(0x81, response[2] & 0xff);
        assertEquals(0x83, response[3] & 0xff);
        assertEquals(1, response[5] & 0xff);
    }

    @Test
    public void recognizesIpv4UdpDnsPacketOnPort53() {
        byte[] packet = ipv4DnsPacket();
        assertTrue(DnsPacket.isIpv4UdpDnsQuery(packet, packet.length));
        assertArrayEquals(dnsQuery(), DnsPacket.extractDnsPayload(packet, packet.length));
    }

    @Test
    public void rejectsNonDnsDestinationPort() {
        byte[] packet = ipv4DnsPacket();
        packet[23] = 0x35;
        packet[22] = 0x00;
        assertFalse(DnsPacket.isIpv4UdpDnsQuery(packet, packet.length));
    }

    @Test
    public void buildsIpv4UdpResponseWithReversedEndpointsAndValidHeaderChecksum() {
        byte[] request = ipv4DnsPacket();
        byte[] dnsResponse = DnsPacket.buildDnsErrorResponse(dnsQuery(), 3);
        byte[] response = DnsPacket.buildIpv4UdpResponse(request, request.length, dnsResponse);

        assertEquals(request.length - dnsQuery().length + dnsResponse.length, response.length);
        assertEquals(request[16], response[12]);
        assertEquals(request[17], response[13]);
        assertEquals(request[12], response[16]);
        assertEquals(request[13], response[17]);
        assertEquals(request[22], response[20]);
        assertEquals(request[23], response[21]);
        assertEquals(request[20], response[22]);
        assertEquals(request[21], response[23]);
        assertEquals(0xffff, checksum(response, 20));
    }

    private static byte[] dnsQuery() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x12);
        out.write(0x34);
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
        writeLabel(out, "example");
        writeLabel(out, "com");
        out.write(0);
        out.write(0);
        out.write(1);
        out.write(0);
        out.write(1);
        return out.toByteArray();
    }

    private static void writeLabel(ByteArrayOutputStream out, String value) {
        out.write(value.length());
        for (int i = 0; i < value.length(); i++) {
            out.write(value.charAt(i));
        }
    }

    private static byte[] ipv4DnsPacket() {
        byte[] dns = dnsQuery();
        byte[] packet = new byte[20 + 8 + dns.length];
        packet[0] = 0x45;
        put16(packet, 2, packet.length);
        packet[8] = 64;
        packet[9] = 17;
        packet[12] = 10;
        packet[13] = 111;
        packet[14] = 0;
        packet[15] = 2;
        packet[16] = 10;
        packet[17] = 111;
        packet[18] = 0;
        packet[19] = 1;
        put16(packet, 20, 53000);
        put16(packet, 22, 53);
        put16(packet, 24, 8 + dns.length);
        System.arraycopy(dns, 0, packet, 28, dns.length);
        return packet;
    }

    private static void put16(byte[] target, int offset, int value) {
        target[offset] = (byte) ((value >>> 8) & 0xff);
        target[offset + 1] = (byte) (value & 0xff);
    }

    private static int checksum(byte[] data, int length) {
        long sum = 0;
        for (int i = 0; i < length; i += 2) {
            sum += ((data[i] & 0xff) << 8) | (data[i + 1] & 0xff);
        }
        while ((sum >>> 16) != 0) {
            sum = (sum & 0xffff) + (sum >>> 16);
        }
        return (int) sum & 0xffff;
    }
}