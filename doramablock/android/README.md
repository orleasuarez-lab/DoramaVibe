# DoramaBlock Android — MVP 0.1

This is the first native Android implementation, not the static dashboard.

## What it does

- Requests the user's Android VPN permission explicitly.
- Starts a local `VpnService` in the foreground.
- Configures a synthetic IPv4 DNS server at `10.111.0.1`.
- Routes only that DNS server address (/32) into the TUN interface.
- Reads ordinary IPv4/UDP DNS queries.
- Checks the requested hostname against a small built-in blocklist.
- Returns NXDOMAIN for listed ad/tracker domains.
- Forwards other DNS queries to Cloudflare's public resolver (1.1.1.1) using a socket protected from the VPN to avoid a routing loop.
- Shows counts for blocked and forwarded DNS queries.

Normal application IP traffic is not intentionally routed through the TUN. The goal of this MVP is to filter DNS while leaving ordinary internet traffic on Android's normal network path.

## Current limitations — important

This is deliberately a narrow DNS-filter MVP, not a universal ad blocker.

- It only parses IPv4 UDP DNS on port 53.
- It does not filter IPv6 DNS, DNS-over-HTTPS (DoH), DNS-over-TLS (DoT), app-private resolvers, or DNS-over-TCP.
- It uses a small static sample list, not a full maintained blocklist.
- Domain filtering cannot distinguish an ad from ordinary content when both use the same host or delivery path.
- It cannot reliably remove ads stitched into video streams, including some in-app video advertising.
- A blocked domain can break a feature in an app. The list is intentionally conservative.
- The app has not been compiled or tested on a physical phone from this repository yet. CI will provide the first build/test result.
- This is a debug APK workflow, not a Google Play release. Store distribution requires separate policy, privacy, security, and target-SDK review.

## Build

The repository's GitHub Actions workflow uses JDK 17, Gradle 8.9, Android SDK 35, and runs unit tests plus `assembleDebug`.

The APK is uploaded as the workflow artifact named `doramablock-debug-apk`.

## Manual test plan

1. Install the debug APK on an Android test device.
2. Open DoramaBlock and press **ATIVAR PROTEÇÃO DNS**.
3. Accept Android's VPN permission dialog.
4. Confirm normal browsing still works.
5. Query a known test domain from the configured list and confirm the blocked counter increments.
6. Query a normal domain and confirm the forwarded counter increments.
7. Stop the service and verify normal DNS resolution remains available.
8. Repeat with Private DNS enabled/disabled and different apps; document bypasses rather than claiming complete coverage.

## Privacy

The first MVP has no analytics backend and stores only local counters in app preferences. DNS queries are forwarded to 1.1.1.1. Do not use this build for sensitive testing until the resolver and privacy behavior have been reviewed.
