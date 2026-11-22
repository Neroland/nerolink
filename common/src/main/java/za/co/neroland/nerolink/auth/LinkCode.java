package za.co.neroland.nerolink.auth;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The single "link code" a player types into the app in relay mode: the relay Server ID and the
 * one-time pairing code joined as {@code SERVERID-XXXX-XXXX} (e.g. {@code K7Q2MX-ABCD-EFGH}).
 *
 * <p>The app splits it again: the last eight characters are the pairing code, everything before
 * them is the Server ID. Only the pairing code ever reaches {@code POST /pair}; the Server ID
 * only picks the relay route ({@code /s/<serverId>}), exactly as when it was typed separately,
 * so the bridge and relay protocols are unchanged. The Server ID is not a secret (the relay
 * says so: the server key is), so joining them adds nothing an onlooker could not already read
 * off chat.
 *
 * <p>Older apps cannot take a link code (their code field stops at eight characters), so the
 * pairing whisper keeps the Server ID and the bare code available as well.
 */
public final class LinkCode {

    /** Hosts of the official relay; the app pre-fills one of them, so players never type it. */
    private static final Set<String> OFFICIAL_RELAY_HOSTS =
            Set.of("relay.nerolandmc.net", "nerorelay.neroserver.xyz");

    private LinkCode() {
    }

    /**
     * Join a relay Server ID and a pairing code into a link code, or empty when the id cannot be
     * carried in one: legacy UUID registrations, or anything outside 4–12 letters and digits
     * (the relay's short-id rule). Callers then fall back to showing the two separately.
     */
    public static Optional<String> compose(String serverId, String pairingCode) {
        if (serverId == null || pairingCode == null) {
            return Optional.empty();
        }
        String id = serverId.trim().toUpperCase(Locale.ROOT);
        String code = PairingService.normalize(pairingCode);
        if (!id.matches("[0-9A-Z]{4,12}") || code == null) {
            return Optional.empty();
        }
        return Optional.of(id + "-" + code);
    }

    /**
     * Whether {@code origin} is the official relay the app uses by default. Anything else (a
     * self-hosted relay) has to be typed into the app's relay address field, so the pairing
     * whisper shows it. An unparsable origin counts as custom.
     */
    public static boolean isOfficialRelay(String origin) {
        if (origin == null || origin.isBlank()) {
            return true; // no stored origin: a manual relayUrl override, shown elsewhere
        }
        try {
            String host = URI.create(origin.trim()).getHost();
            return host != null && OFFICIAL_RELAY_HOSTS.contains(host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
