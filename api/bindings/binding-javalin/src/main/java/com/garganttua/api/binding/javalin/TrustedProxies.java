package com.garganttua.api.binding.javalin;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The reverse proxies whose {@code X-Forwarded-For} is believed — addresses or ranges ({@code 10.0.0.0/8},
 * {@code 192.168.1.5}, {@code fd00::/8}) — and the client address a request resolves to through them.
 *
 * <p>
 * The client is found walking {@code X-Forwarded-For} from the RIGHT, the hop each trusted proxy appended,
 * and stopping at the first address that is not a trusted proxy: anything to its left was written by the
 * client itself and proves nothing. A connection that does not come from a trusted proxy is the client,
 * whatever headers it sends. With no trusted proxy declared, the header is never read.
 * </p>
 *
 * <p>
 * Addresses are parsed as LITERALS ({@link InetAddress#ofLiteral}): a header value is never resolved
 * through DNS, so a forged header cannot make the server look anything up.
 * </p>
 */
final class TrustedProxies {

	/** One trusted range: the network's bytes and its prefix length in bits. */
	private record Range(byte[] network, int prefix) {

		boolean contains(byte[] address) {
			if (address.length != this.network.length) {
				return false;
			}
			int full = this.prefix / 8;
			for (int i = 0; i < full; i++) {
				if (address[i] != this.network[i]) {
					return false;
				}
			}
			int rest = this.prefix % 8;
			if (rest == 0) {
				return true;
			}
			int mask = 0xFF << (8 - rest) & 0xFF;
			return (address[full] & mask) == (this.network[full] & mask);
		}
	}

	static final TrustedProxies NONE = new TrustedProxies(List.of());

	private final List<Range> ranges;

	private TrustedProxies(List<Range> ranges) {
		this.ranges = ranges;
	}

	/**
	 * Parses a list of addresses and ranges.
	 *
	 * @param entries addresses or CIDR ranges; blank entries are ignored
	 * @return the trusted proxies
	 * @throws IllegalArgumentException when an entry is neither
	 */
	static TrustedProxies of(List<String> entries) {
		List<Range> ranges = new ArrayList<>();
		for (String raw : entries) {
			String entry = raw == null ? "" : raw.trim();
			if (entry.isEmpty()) {
				continue;
			}
			int slash = entry.indexOf('/');
			byte[] network = literal(slash < 0 ? entry : entry.substring(0, slash))
					.orElseThrow(() -> new IllegalArgumentException("Not an address or range: '" + raw + "'"));
			int prefix = network.length * 8;
			if (slash >= 0) {
				try {
					prefix = Integer.parseInt(entry.substring(slash + 1));
				} catch (NumberFormatException e) {
					throw new IllegalArgumentException("Not a prefix length in '" + raw + "'", e);
				}
				if (prefix < 0 || prefix > network.length * 8) {
					throw new IllegalArgumentException("Prefix out of range in '" + raw + "'");
				}
			}
			ranges.add(new Range(network, prefix));
		}
		return new TrustedProxies(List.copyOf(ranges));
	}

	/**
	 * The client address of a request.
	 *
	 * @param remote        the address of the connection
	 * @param forwardedFor  the {@code X-Forwarded-For} values, in arrival order (several headers allowed)
	 * @return the client: the connection itself unless it is a trusted proxy, else the first untrusted hop
	 *         from the right — or the leftmost hop when every hop is a trusted proxy
	 */
	String client(String remote, List<String> forwardedFor) {
		if (this.ranges.isEmpty() || !trusted(remote) || forwardedFor == null || forwardedFor.isEmpty()) {
			return remote;
		}
		List<String> hops = new ArrayList<>();
		for (String header : forwardedFor) {
			if (header == null) {
				continue;
			}
			for (String hop : header.split(",")) {
				if (!hop.isBlank()) {
					hops.add(hop.trim());
				}
			}
		}
		for (int i = hops.size() - 1; i >= 0; i--) {
			if (!trusted(hops.get(i))) {
				return hops.get(i);
			}
		}
		return hops.isEmpty() ? remote : hops.get(0);
	}

	private boolean trusted(String address) {
		Optional<byte[]> bytes = literal(address);
		return bytes.isPresent() && this.ranges.stream().anyMatch(range -> range.contains(bytes.get()));
	}

	/** An address literal's bytes, never through DNS; IPv6 brackets and zone ids are tolerated. */
	private static Optional<byte[]> literal(String text) {
		if (text == null || text.isBlank()) {
			return Optional.empty();
		}
		String value = text.trim();
		if (value.startsWith("[") && value.endsWith("]")) {
			value = value.substring(1, value.length() - 1);
		}
		try {
			return Optional.of(InetAddress.ofLiteral(value).getAddress());
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}
}
