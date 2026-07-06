package za.co.neroland.nerolink.auth;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory issuer of short-lived pairing codes. A player runs {@code /nerolink pair}; the
 * bridge mints a single-use {@code XXXX-XXXX} code (5-minute TTL) bound to that player's UUID
 * and whispers it to them only. The companion client redeems it once via {@code POST /pair}
 * for a long-lived device token.
 *
 * <p>Codes are transient (never persisted): a server restart clears any un-redeemed code,
 * which is the desired security posture. {@link #forget(UUID)} drops a player's pending code
 * for POPIA/GDPR erasure. Codes are never logged.
 */
public final class PairingService {

    /** Unambiguous alphabet (no O/0, I/1) for a code a human reads off chat and types on a phone. */
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final long TTL_MILLIS = 5 * 60 * 1000L;

    private final SecureRandom random = new SecureRandom();
    /** code -> pending pairing. */
    private final Map<String, Pending> byCode = new ConcurrentHashMap<>();

    private record Pending(UUID player, long expiresAt) {
    }

    /**
     * Mint a fresh single-use code for a player, replacing any earlier un-redeemed code they
     * hold. Returns the {@code XXXX-XXXX} code to whisper to the player.
     */
    public String issue(UUID player) {
        purgeExpired();
        // One live code per player: drop their previous pending code first.
        byCode.values().removeIf(p -> p.player().equals(player));
        String code = generateUniqueCode();
        byCode.put(code, new Pending(player, System.currentTimeMillis() + TTL_MILLIS));
        return code;
    }

    /**
     * Redeem a code, consuming it (single-use). Returns the bound player UUID if the code is
     * valid and unexpired, else empty.
     */
    public Optional<UUID> redeem(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(java.util.Locale.ROOT);
        Pending pending = byCode.remove(normalized);
        if (pending == null) {
            return Optional.empty();
        }
        if (System.currentTimeMillis() > pending.expiresAt()) {
            return Optional.empty();
        }
        return Optional.of(pending.player());
    }

    /** POPIA/GDPR erasure: drop a player's pending pairing code. */
    public void forget(UUID player) {
        byCode.values().removeIf(p -> p.player().equals(player));
    }

    private String generateUniqueCode() {
        for (int attempt = 0; attempt < 32; attempt++) {
            StringBuilder sb = new StringBuilder(9);
            for (int i = 0; i < 8; i++) {
                if (i == 4) {
                    sb.append('-');
                }
                sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
            }
            String code = sb.toString();
            if (!byCode.containsKey(code)) {
                return code;
            }
        }
        // Astronomically unlikely fallthrough; last generated is fine.
        throw new IllegalStateException("could not allocate a unique pairing code");
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        byCode.values().removeIf(p -> now > p.expiresAt());
    }
}
