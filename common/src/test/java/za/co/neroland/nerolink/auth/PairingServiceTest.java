package za.co.neroland.nerolink.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import za.co.neroland.nerolink.tls.BridgeTls;

/** Pairing codes: format, single use, expiry, one-per-player, channel-bound proof, brute-force budget. */
class PairingServiceTest {

    private static final String FP = "ad88909ec2ab31ab99c7980d12d90077c64b434dd55502757adc67155b5a0212";

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final PairingService service = new PairingService(now::get, () -> FP);
    private final UUID alice = UUID.randomUUID();

    @Test
    void codeHasReadableFormat() {
        String code = service.issue(alice);
        assertTrue(code.matches("[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}"), code);
    }

    @Test
    void redeemIsSingleUseAndCaseInsensitive() {
        String code = service.issue(alice);
        var first = service.redeem(code.toLowerCase().replace("-", " "), "1.2.3.4");
        assertEquals(PairingService.Outcome.OK, first.outcome());
        assertEquals(alice, first.player());
        assertEquals(PairingService.Outcome.INVALID, service.redeem(code, "1.2.3.4").outcome());
    }

    @Test
    void codesExpireAfterFiveMinutes() {
        String code = service.issue(alice);
        now.addAndGet(5 * 60 * 1000L + 1);
        assertEquals(PairingService.Outcome.INVALID, service.redeem(code, "src").outcome());
    }

    @Test
    void newCodeReplacesPlayersPreviousCode() {
        String first = service.issue(alice);
        String second = service.issue(alice);
        assertEquals(PairingService.Outcome.INVALID, service.redeem(first, "src").outcome());
        assertEquals(PairingService.Outcome.OK, service.redeem(second, "src").outcome());
    }

    @Test
    void proofForTheRealFingerprintRedeems() {
        String code = service.issue(alice);
        var r = service.redeemProof(BridgeTls.pairingProof(code, FP), "src");
        assertEquals(PairingService.Outcome.OK, r.outcome());
        assertEquals(alice, r.player());
    }

    @Test
    void proofForADifferentCertificateIsRejected() {
        String code = service.issue(alice);
        String mitmFp = "00" + FP.substring(2);
        var r = service.redeemProof(BridgeTls.pairingProof(code, mitmFp), "src");
        assertEquals(PairingService.Outcome.INVALID, r.outcome());
        // ...and a failed proof does not consume the real code.
        assertEquals(PairingService.Outcome.OK,
                service.redeemProof(BridgeTls.pairingProof(code, FP), "src").outcome());
    }

    @Test
    void sourceIsLockedAfterRepeatedFailures() {
        String code = service.issue(alice);
        for (int i = 0; i < PairingService.SOURCE_FAILURE_LIMIT; i++) {
            service.redeem("AAAA-AAAA", "9.9.9.9");
        }
        assertEquals(PairingService.Outcome.LOCKED, service.redeem(code, "9.9.9.9").outcome());
        // Another source is unaffected.
        assertEquals(PairingService.Outcome.OK, service.redeem(code, "1.1.1.1").outcome());
    }

    @Test
    void sourceLockLiftsAfterWindow() {
        for (int i = 0; i < PairingService.SOURCE_FAILURE_LIMIT; i++) {
            service.redeem("AAAA-AAAA", "9.9.9.9");
        }
        now.addAndGet(PairingService.FAILURE_WINDOW_MILLIS + 1);
        String code = service.issue(alice);
        assertEquals(PairingService.Outcome.OK, service.redeem(code, "9.9.9.9").outcome());
    }

    @Test
    void globalBackstopRejectsWhileFloodedButNeverBurnsCodes() {
        String code = service.issue(alice);
        for (int i = 0; i < PairingService.GLOBAL_FAILURE_LIMIT; i++) {
            service.redeem("AAAA-AAAA", "attacker-" + i);
        }
        assertEquals(PairingService.Outcome.LOCKED, service.redeem(code, "honest").outcome());
        now.addAndGet(PairingService.FAILURE_WINDOW_MILLIS + 1);
        // The flood is over and the player's code survived it.
        assertEquals(PairingService.Outcome.OK, service.redeem(code, "honest").outcome());
    }

    @Test
    void withoutTlsNoProofIsAccepted() {
        PairingService plain = new PairingService(now::get, () -> null);
        String code = plain.issue(alice);
        assertEquals(PairingService.Outcome.INVALID,
                plain.redeemProof(BridgeTls.pairingProof(code, FP), "src").outcome());
        assertEquals(PairingService.Outcome.OK, plain.redeem(code, "src").outcome());
    }

    @Test
    void forgetDropsPendingCode() {
        String code = service.issue(alice);
        service.forget(alice);
        assertEquals(PairingService.Outcome.INVALID, service.redeem(code, "src").outcome());
    }
}
