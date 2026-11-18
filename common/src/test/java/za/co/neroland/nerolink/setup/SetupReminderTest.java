package za.co.neroland.nerolink.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import za.co.neroland.nerolink.setup.SetupReminder.Audience;

/** Who gets the "installed but not set up" welcome message. */
class SetupReminderTest {

    @Test
    void operatorIsRemindedUntilARelayIsConfigured() {
        assertEquals(Audience.OPERATOR, SetupReminder.audience(true, true, true, false, false, true));
        assertEquals(Audience.OPERATOR, SetupReminder.audience(true, true, true, false, true, true));
        assertEquals(Audience.NONE, SetupReminder.audience(true, true, true, true, false, true));
    }

    @Test
    void playerIsOnlyToldWhenPairingIsImpossible() {
        assertEquals(Audience.PLAYER, SetupReminder.audience(true, true, true, false, false, false));
        assertEquals(Audience.NONE, SetupReminder.audience(true, true, true, false, true, false));
        assertEquals(Audience.NONE, SetupReminder.audience(true, true, true, true, false, false));
    }

    @Test
    void silentWhenDisabledOrStopped() {
        assertEquals(Audience.NONE, SetupReminder.audience(false, true, true, false, false, true));
        assertEquals(Audience.NONE, SetupReminder.audience(true, false, true, false, false, true));
        assertEquals(Audience.NONE, SetupReminder.audience(false, true, false, false, false, false));
    }

    @Test
    void singlePlayerHostIsGuidedWithOrWithoutCheats() {
        assertEquals(Audience.OPERATOR, SetupReminder.audience(true, true, false, false, false, true));
        assertEquals(Audience.HOST, SetupReminder.audience(true, true, false, false, false, false));
        assertEquals(Audience.NONE, SetupReminder.audience(true, true, false, false, true, false));
        assertEquals(Audience.NONE, SetupReminder.audience(true, true, false, true, false, false));
    }
}
