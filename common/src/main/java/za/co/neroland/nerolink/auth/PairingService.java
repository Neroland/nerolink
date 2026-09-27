package za.co.neroland.nerolink.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import za.co.neroland.nerolink.tls.BridgeTls;

/**
 * In-memory issuer of short-lived pairing codes. A player runs {@code /nerolink pair}; the
 * bridge mints a single-use {@code XXXX-XXXX} code (5-minute TTL) bound to that player's UUID
 * and whispers it to them only. The companion client redeems it once via {@code POST /pair}
 * for a long-lived device token.
 *
 * <p>Codes are transient (never persisted): a server restart clears any un-redeemed code,
 * which is the desired security posture. {@link #forget(UUID)} drops a player's pending code
 * for POPIA/GDPR erasure. Codes are never logged.
 *
 * <p><b>Brute-force protection.</b> {@code POST /pair} is public. A code has 40 bits of entropy
 * and lives 5 minutes, so online guessing is hopeless on its own; failed redemptions are still
 * budgeted per source (the client IP for direct connections, a salted IP hash forwarded by the
 * relay) to keep noise and log volume down, with a generous global ceiling as a backstop. Being
 * over a budget only rejects attempts while it lasts; it never invalidates players' codes, so an
 * attacker cannot use it to lock everyone out for longer than they keep flooding.
 *
 * <p><b>Direct-mode proofs</b> are computed once per code at {@link #issue} (PBKDF2 is slow by
 * design) and compared in constant time on redemption, so failed attempts cost the server nothing.
 */
public final class PairingService {

    /** Unambiguous alphabet (no O/0, I/1) for a code a human reads off chat and types on a phone. */
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final long TTL_MILLIS = 5 * 60 * 1000L;

    /** Failed redemptions allowed per source per rolling minute. */
    static final int SOURCE_FAILURE_LIMIT = 5;
    /** Failed redemptions allowed across all sources per rolling minute (backstop only). */
    static final int GLOBAL_FAILURE_LIMIT = 120;
    static final long FAILURE_WINDOW_MILLIS = 60_000L;

    /** Outcome of a redemption attempt. */
    public enum Outcome { OK, INVALID, LOCKED }

    /** Result of {@link #redeem}/{@link #redeemProof}: the bound player when {@link Outcome#OK}. */
    public record Redemption(Outcome outcome, UUID player) {
        static final Redemption INVALID = new Redemption(Outcome.INVALID, null);
        static final Redemption LOCKED = new Redemption(Outcome.LOCKED, null);
    }

    private final SecureRandom random = new SecureRandom();
    private final LongSupplier clock;
    private final Supplier<String> fingerprint;
    private final Map<String, Deque<Long>> failuresBySource = new ConcurrentHashMap<>();
    private final Deque<Long> globalFailures = new ArrayDeque<>();

    /**
     * @param fingerprint the bridge certificate fingerprint (hex), or null when direct TLS is off;
     *                    used to pre-compute each code's direct-mode proof at issue time
     */
    public PairingService(Supplier<String> fingerprint) {
        this(System::currentTimeMillis, fingerprint);
    }

    /** Test seam: inject a clock. */
    PairingService(LongSupplier clock, Supplier<String> fingerprint) {
        this.clock = clock;
        this.fingerprint = fingerprint;
    }
    /** code -> pending pairing. */
    private final Map<String, Pending> byCode = new ConcurrentHashMap<>();

    /**
     * A live code: its owner, expiry and (direct TLS only) its proof, computed off-thread because
     * PBKDF2 is slow by design and {@link #issue} runs on the game thread.
     */
    private record Pending(UUID player, long expiresAt, java.util.concurrent.CompletableFuture<String> proof) {
    }

    /** One daemon thread for proof derivation; codes are issued rarely. */
    private static final java.util.concurrent.Executor PROOF_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "nerolink-pairing-proof");
                t.setDaemon(true);
                return t;
            });

    /**
     * Mint a fresh single-use code for a player, replacing any earlier un-redeemed code they
     * hold. Returns the {@code XXXX-XXXX} code to whisper to the player.
     */
    public String issue(UUID player) {
        purgeExpired();
        // One live code per player: drop their previous pending code first.
        byCode.values().removeIf(p -> p.player().equals(player));
        String code = generateUniqueCode();
        String fp = fingerprint == null ? null : fingerprint.get();
        java.util.concurrent.CompletableFuture<String> proof = fp == null ? null
                : java.util.concurrent.CompletableFuture.supplyAsync(() -> BridgeTls.pairingProof(code, fp), PROOF_EXECUTOR);
        byCode.put(code, new Pending(player, clock.getAsLong() + TTL_MILLIS, proof));
        return code;
    }

    /**
     * Redeem a plain code (relay transport), consuming it (single-use).
     *
     * @param source rate-limit key for the caller (client IP, or {@code "relay"})
     */
    public Redemption redeem(String code, String source) {
        if (isLocked(source)) {
            return Redemption.LOCKED;
        }
        if (code == null) {
            return fail(source);
        }
        String normalized = normalize(code);
        Pending pending = normalized == null ? null : byCode.remove(normalized);
        if (pending == null || clock.getAsLong() > pending.expiresAt()) {
            return fail(source);
        }
        return new Redemption(Outcome.OK, pending.player());
    }

    /**
     * Redeem by channel-bound proof (direct TLS transport): the client proves knowledge of a
     * pending code for the certificate it actually saw, without sending the code. Matches in
     * constant time against every live code; consumes the matching one.
     *
     * @param proof hex proof from the client ({@link BridgeTls#pairingProof})
     */
    public Redemption redeemProof(String proof, String source) {
        if (isLocked(source)) {
            return Redemption.LOCKED;
        }
        if (proof == null) {
            return fail(source);
        }
        byte[] presented = proof.trim().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        long now = clock.getAsLong();
        String matched = null;
        for (Map.Entry<String, Pending> e : byCode.entrySet()) {
            String expected = proofOf(e.getValue());
            if (expected != null
                    && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), presented)
                    && now <= e.getValue().expiresAt()) {
                matched = e.getKey();
            }
        }
        Pending pending = matched == null ? null : byCode.remove(matched);
        if (pending == null) {
            return fail(source);
        }
        return new Redemption(Outcome.OK, pending.player());
    }

    /** The code's proof; waits briefly if it is still being derived (only right after issue). */
    private static String proofOf(Pending p) {
        if (p.proof() == null) {
            return null;
        }
        try {
            return p.proof().get(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            return null;
        }
    }

    /** Whether the service (or this source) is currently locked out after too many failures. */
    public boolean isLocked(String source) {
        long now = clock.getAsLong();
        synchronized (globalFailures) {
            prune(globalFailures, now);
            if (globalFailures.size() >= GLOBAL_FAILURE_LIMIT) {
                return true;
            }
        }
        Deque<Long> q = failuresBySource.get(sourceKey(source));
        if (q == null) {
            return false;
        }
        synchronized (q) {
            prune(q, now);
            return q.size() >= SOURCE_FAILURE_LIMIT;
        }
    }

    private Redemption fail(String source) {
        long now = clock.getAsLong();
        Deque<Long> q = failuresBySource.computeIfAbsent(sourceKey(source), k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q, now);
            q.addLast(now);
        }
        synchronized (globalFailures) {
            prune(globalFailures, now);
            globalFailures.addLast(now);
        }
        // Bound memory: drop idle sources opportunistically.
        if (failuresBySource.size() > 1024) {
            failuresBySource.entrySet().removeIf(en -> {
                synchronized (en.getValue()) {
                    prune(en.getValue(), now);
                    return en.getValue().isEmpty();
                }
            });
        }
        return Redemption.INVALID;
    }

    private static void prune(Deque<Long> q, long now) {
        while (!q.isEmpty() && now - q.peekFirst() > FAILURE_WINDOW_MILLIS) {
            q.pollFirst();
        }
    }

    private static String sourceKey(String source) {
        return source == null || source.isBlank() ? "unknown" : source;
    }

    /** {@code abcd efgh} / {@code ABCDEFGH} / {@code abcd-efgh} -> {@code ABCD-EFGH}; null if malformed. */
    static String normalize(String code) {
        String c = BridgeTls.canonicalCode(code);
        if (c.length() != 8) {
            return null;
        }
        return c.substring(0, 4) + "-" + c.substring(4);
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
        long now = clock.getAsLong();
        byCode.values().removeIf(p -> now > p.expiresAt());
    }
}
