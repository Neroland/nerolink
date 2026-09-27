package za.co.neroland.nerolink.tls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The self-signed identity: valid X.509, stable across reloads, and the pairing proof vector. */
class BridgeTlsTest {

    @TempDir
    Path dir;

    @Test
    void generatesAValidSelfSignedCertificate() throws Exception {
        BridgeTls tls = BridgeTls.generate();
        tls.certificate().checkValidity();
        tls.certificate().verify(tls.certificate().getPublicKey());
        assertEquals("CN=NeroLink Bridge", tls.certificate().getSubjectX500Principal().getName());
        assertTrue(tls.fingerprint().matches("[0-9a-f]{64}"));
        assertTrue(tls.shortFingerprint().matches("[0-9A-F]{4}(-[0-9A-F]{4}){3}"));
    }

    @Test
    void identityIsPersistedAndReused() throws Exception {
        Path file = dir.resolve("nerolink/bridge-tls.p12");
        BridgeTls first = BridgeTls.loadOrCreate(file);
        BridgeTls second = BridgeTls.loadOrCreate(file);
        assertEquals(first.fingerprint(), second.fingerprint());
    }

    @Test
    void corruptFileIsMovedAsideAndReplaced() throws Exception {
        Path file = dir.resolve("bridge-tls.p12");
        Files.writeString(file, "not a keystore");
        BridgeTls tls = BridgeTls.loadOrCreate(file);
        assertTrue(Files.exists(dir.resolve("bridge-tls.p12.broken")));
        assertEquals(tls.fingerprint(), BridgeTls.loadOrCreate(file).fingerprint());
    }

    /** Fixed vector shared with the companion app's Dart implementation (test/pairing_proof_test.dart). */
    @Test
    void pairingProofMatchesSharedVector() {
        String fp = "ad88909ec2ab31ab99c7980d12d90077c64b434dd55502757adc67155b5a0212";
        // PBKDF2-HMAC-SHA256(code, "nerolink-pair-v1:" + fp, 100000, 32); cross-checked with Python's hashlib.
        String expected = "75dab7f42db2e321281d777d8a5f9a12e734ffade783762763531f3628b1a6c1";
        assertEquals(expected, BridgeTls.pairingProof("abcd-efgh", fp));
        assertEquals(expected, BridgeTls.pairingProof("ABCDEFGH", fp.toUpperCase()));
        assertNotEquals(expected, BridgeTls.pairingProof("ABCDEFGJ", fp));
    }
}
