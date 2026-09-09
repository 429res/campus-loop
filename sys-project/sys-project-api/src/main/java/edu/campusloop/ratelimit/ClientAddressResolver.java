package edu.campusloop.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

@Component
public class ClientAddressResolver {
    private static final int MAX_FORWARDED_HOPS = 20;
    private final List<Network> trusted;

    public ClientAddressResolver(RateLimitProperties properties) {
        trusted = new ArrayList<>();
        for (String value : properties.getTrustedProxies().split(",")) {
            if (!value.isBlank()) trusted.add(Network.parse(value.trim()));
        }
    }

    public String resolve(HttpServletRequest request) {
        InetAddress peer = literal(request.getRemoteAddr());
        if (peer == null) return "unknown";
        if (!isTrusted(peer)) return peer.getHostAddress();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) return peer.getHostAddress();
        String[] hops = forwarded.split(",");
        if (hops.length > MAX_FORWARDED_HOPS) return peer.getHostAddress();
        InetAddress current = peer;
        for (int index = hops.length - 1; index >= 0 && isTrusted(current); index--) {
            InetAddress candidate = literal(hops[index].trim());
            if (candidate == null) return peer.getHostAddress();
            current = candidate;
        }
        return current.getHostAddress();
    }

    private boolean isTrusted(InetAddress address) { return trusted.stream().anyMatch(network -> network.contains(address)); }

    private static InetAddress literal(String value) {
        if (value == null || value.isBlank() || value.contains("%") || !value.matches("[0-9A-Fa-f:.]+")) return null;
        if (value.indexOf(':') < 0) {
            String[] parts = value.split("\\.", -1);
            if (parts.length != 4) return null;
            for (String part : parts) {
                try { if (part.isEmpty() || Integer.parseInt(part) > 255) return null; }
                catch (NumberFormatException ignored) { return null; }
            }
        }
        try { return InetAddress.getByName(value); }
        catch (UnknownHostException ignored) { return null; }
    }

    private record Network(byte[] address, int prefix) {
        static Network parse(String value) {
            String[] parts = value.split("/", -1);
            InetAddress address = literal(parts[0]);
            if (address == null || parts.length > 2) throw new IllegalStateException("CAMPUS_RATE_LIMIT_TRUSTED_PROXIES 只接受 IP 或 CIDR");
            int bits = address.getAddress().length * 8;
            int prefix;
            try { prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : bits; }
            catch (NumberFormatException e) { throw new IllegalStateException("可信代理 CIDR 前缀不正确"); }
            if (prefix < 0 || prefix > bits) throw new IllegalStateException("可信代理 CIDR 前缀不正确");
            return new Network(address.getAddress(), prefix);
        }

        boolean contains(InetAddress candidate) {
            byte[] other = candidate.getAddress();
            if (other.length != address.length) return false;
            int whole = prefix / 8, remaining = prefix % 8;
            for (int i = 0; i < whole; i++) if (address[i] != other[i]) return false;
            if (remaining == 0) return true;
            int mask = 0xff << (8 - remaining);
            return (address[whole] & mask) == (other[whole] & mask);
        }
    }
}
