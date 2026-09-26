package com.deleted.xapocalypse;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class XApocalypseCommandTest {

    @Test
    void itemAmountUsesTheOptionalThirdArgument() {
        assertEquals(2, xApocalypseCommand.parseItemAmount(new String[]{"zombie_guts", "Player", "2"}));
    }

    @Test
    void itemAmountDefaultsToOneAndNeverExceedsAStack() {
        assertEquals(1, xApocalypseCommand.parseItemAmount(new String[]{"zombie_guts", "Player"}));
        assertEquals(1, xApocalypseCommand.parseItemAmount(new String[]{"zombie_guts", "Player", "nope"}));
        assertEquals(1, xApocalypseCommand.parseItemAmount(new String[]{"zombie_guts", "Player", "0"}));
        assertEquals(64, xApocalypseCommand.parseItemAmount(new String[]{"zombie_guts", "Player", "99"}));
    }
}
