package za.co.neroland.nerolink.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/** Link codes: Server ID + pairing code in one string the app can split again. */
class LinkCodeTest {

    @Test
    void joinsShortServerIdAndCode() {
        assertEquals(Optional.of("K7Q2MX-ABCD-EFGH"), LinkCode.compose("K7Q2MX", "ABCD-EFGH"));
        assertEquals(Optional.of("K7Q2MX-ABCD-EFGH"), LinkCode.compose(" k7q2mx ", "abcdefgh"));
    }

    @Test
    void theLastEightCharactersAreAlwaysThePairingCode() {
        String link = LinkCode.compose("7KM3", "WXYZ-2345").orElseThrow();
        String canonical = link.replace("-", "");
        assertEquals("WXYZ2345", canonical.substring(canonical.length() - 8));
        assertEquals("7KM3", canonical.substring(0, canonical.length() - 8));
    }

    @Test
    void legacyUuidServerIdsAreNotCarried() {
        assertTrue(LinkCode.compose("0f8fad5b-d9cb-469f-a165-70867728950e", "ABCD-EFGH").isEmpty());
    }

    @Test
    void malformedInputIsNotCarried() {
        assertTrue(LinkCode.compose("ABC", "ABCD-EFGH").isEmpty());
        assertTrue(LinkCode.compose("ABCDEFGHJKMNP", "ABCD-EFGH").isEmpty());
        assertTrue(LinkCode.compose("K7Q2MX", "ABC").isEmpty());
        assertTrue(LinkCode.compose(null, "ABCD-EFGH").isEmpty());
        assertTrue(LinkCode.compose("K7Q2MX", null).isEmpty());
    }

    @Test
    void recognisesTheOfficialRelay() {
        assertTrue(LinkCode.isOfficialRelay("https://relay.nerolandmc.net"));
        assertTrue(LinkCode.isOfficialRelay("https://NeroRelay.NeroServer.xyz/"));
        assertFalse(LinkCode.isOfficialRelay("https://relay.example.org"));
        assertFalse(LinkCode.isOfficialRelay("not a url"));
    }
}
