package com.doramablock.android;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Minimal IPv4/UDP DNS packet helpers for the first VPN MVP.
 *
 * Scope: ordinary IPv4 UDP DNS queries routed to the VPN's synthetic DNS
 * address. This class deliberately rejects fragments and non-DNS traffic.
 * It does not implement TCP DNS, IPv6, DoH or DoT.
 */
public final class DnsPacket {
    private static final int IP_PROTOCOL_UDP = 17;
    private static final int DNS_HEADER_SIZE = 12;

    private DnsPacket() {
    }

    public static boolean isIpv4UdpDnsQuery(byte[] packet, int length) {
        if (packet == null || length < 20 || length > packet.length) {
            return false;
        }

        int version = (packet[0] >>> 4) & 0x0F;
        int ihl = (packet[0] & 0x0F) * 4;
        if (version != 4 || ihl < 20 || length < ihl + 8) {
            return false;
        }
        if ((packet[9] & 0xFF) != IP_PROTOCOL_UDP) {
            return false;
        }

        int fragmentField = readUnsignedShort(packet, 6);
        if ((fragmentField & 0x3FFF) != 0) {
            return false;
        }

        int destinationPort = readUnsignedShort(packet, ihl + 2);
        int udpLength = readUnsignedShort(packet, ihl + 4);
        return destinationPort == 53 && udpLength >= 8
                && ihl + udpLength <= length
                && udpLength >= 8 + DNS_HEADER_SIZE;
    }

    public static byte[] extractDnsPayload(byte[] packet, int length) {
        if (!isIpv4UdpDnsQuery(packet, length)) {
            return new byte[0];
        }

        int ihl = (packet[0] & 0x0F) * 4;
        int udpLength = readUnsignedShort(packet, ihl + 4);
        int dnsLength = udpLength - 8;
        byte[] dns = new byte[dnsLength];
        System.arraycopy(packet, ihl + 8, dns, 0, dnsLength);
        return dns;
    }

    /**
     * Reads the first uncompressed DNS question name. Returns an empty string
     * for malformed packets, responses, or compressed question names.
     */
    public static String extractQuestionName(byte[] dns) {
        if (dns == null || dns.length < DNS_HEADER_SIZE) {
            return "";
        }

        int flags = readUnsignedShort(dns, 2);
        int questionCount = readUnsignedShort(dns, 4);
        if ((flags & 0x8000) != 0 || questionCount < 1) {
            return "";
        }

        int offset = DNS_HEADER_SIZE;
        StringBuilder name = new StringBuilder();
        int labels = 0;

        while (offset < dns.length) {
            int labelLength = dns[offset++] & 0xFF;
            if (labelLength == 0) {
                if (name.length() == 0) {
                    return "";
                }
                return name.toString().toLowerCase(Locale.ROOT);
            }

            // Compression pointers are not expected in a query QNAME.
            if ((labelLength & 0xC0) != 0 || labelLength > 63
                    || offset + labelLength > dns.length) {
                return "";
            }

            if (labels++ > 126) {
                return "";
            }
            if (name.length() > 0) {
                name.append('.');
            }

            for (int i = 0; i < labelLength; i++) {
                int value = dns[offset + i] & 0xFF;
                if (value < 0x21 || value > 0x7E) {
                    return "";
                }
                name.append((char) value);
            }
            offset += labelLength;
        }
        return "";
    }

    public static byte[] buildDnsErrorResponse(byte[] query, int rcode) {
        if (query == null || query.length < DNS_HEADER_SIZE || rcode < 0 || rcode > 15) {
            return new byte[0];
        }

        int flags = readUnsignedShort(query, 2);
        if ((flags & 0x8000) != 0) {
            return new byte[0];
        }

        int questionEnd = findQuestionEnd(query);
        if (questionEnd < DNS_HEADER_SIZE) {
            return new byte[0];
        }

        byte[] response = new byte[questionEnd];
        System.arraycopy(query, 0, response, 0, questionEnd);

        int responseFlags = 0x8000 | 0x0080 | (flags & 0x0100) | rcode;
        writeUnsignedShort(response, 2, responseFlags);
        writeUnsignedShort(response, 4, 1);
        writeUnsignedShort(response, 6, 0);
        writeUnsignedShort(response, 8, 0);
        writeUnsignedShort(response, 10, 0);
        return response;
    }

    public static byte[] buildIpv4UdpResponse(
            byte[] requestPacket, int requestLength, byte[] dnsResponse) {
        if (!isIpv4UdpDnsQuery(requestPacket, requestLength)
                || dnsResponse == null || dnsResponse.length == 0) {
            return new byte[0];
        }

        int ipHeaderLength = (requestPacket[0] & 0x0F) * 4;
        int responseLength = ipHeaderLength + 8 + dnsResponse.length;
        if (responseLength > 65535) {
            return new byte[0];
        }

        byte[] response = new byte[responseLength];
        System.arraycopy(requestPacket, 0, response, 0, ipHeaderLength);

        // Reverse the IPv4 source and destination addresses.
        System.arraycopy(requestPacket, 16, response, 12, 4);
        System.arraycopy(requestPacket, 12, response, 16, 4);

        writeUnsignedShort(response, 2, responseLength);
        response[10] = 0;
        response[11] = 0;

        int udpOffset = ipHeaderLength;
        int requestUdpOffset = ipHeaderLength;

        // Reverse UDP source and destination ports.
        response[udpOffset] = requestPacket[requestUdpOffset + 2];
        response[udpOffset + 1] = requestPacket[requestUdpOffset + 3];
        response[udpOffset + 2] = requestPacket[requestUdpOffset];
        response[udpOffset + 3] = requestPacket[requestUdpOffset + 1];

        writeUnsignedShort(response, udpOffset + 4, 8 + dnsResponse.length);
        response[udpOffset + 6] = 0;
        response[udpOffset + 7] = 0; // Optional UDP checksum for IPv4.
        System.arraycopy(dnsResponse, 0, response, udpOffset + 8, dnsResponse.length);

        writeUnsignedShort(response, 10, ipv4HeaderChecksum(response, ipHeaderLength));
        return response;
    }

    private static int findQuestionEnd(byte[] dns) {
        if (dns.length < DNS_HEADER_SIZE || readUnsignedShort(dns, 4) < 1) {
            return -1;
        }

        int offset = DNS_HEADER_SIZE;
        while (offset < dns.length) {
            int labelLength = dns[offset++] & 0xFF;
            if (labelLength == 0) {
                if (offset + 4 <= dns.length) {
                    return offset + 4; // QTYPE + QCLASS.
                }
                return -1;
            }
            if ((labelLength & 0xC0) != 0 || labelLength > 63
                    || offset + labelLength > dns.length) {
                return -1;
            }
            offset += labelLength;
        }
        return -1;
    }

    private static int ipv4HeaderChecksum(byte[] packet, int headerLength) {
        long sum = 0;
        for (int i = 0; i < headerLength; i += 2) {
            int high = packet[i] & 0xFF;
            int low = (i + 1 < headerLength) ? packet[i + 1] & 0xFF : 0;
            sum += (high << 8) | low;
        }
        while ((sum >>> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >>> 16);
        }
        return (int) (~sum) & 0xFFFF;
    }

    private static int readUnsignedShort(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static void writeUnsignedShort(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >>> 8) & 0xFF);
        data[offset + 1] = (byte) (value & 0xFF);
    }
}
