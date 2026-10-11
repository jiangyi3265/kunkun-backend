package com.ruoyi.boost;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoostUserNumberTest {
    @Test void existingMembersReceiveShortStableNumericNumbers() {
        assertEquals("10001", BoostPlayerMarket.userNo(1));
        assertEquals("10005", BoostPlayerMarket.userNo(5));
        assertEquals("19999", BoostPlayerMarket.userNo(9999));
    }

    @Test void invalidOrExhaustedIdsNeverWrapOrProduceLongerNumbers() {
        for (long id : new long[]{0, -1, 10000, Long.MAX_VALUE}) {
            assertThrows(BoostException.class, () -> BoostPlayerMarket.userNo(id));
        }
    }

    @Test void numericAndCanonicalLegacyNumbersResolveToTheSameMember() {
        assertEquals(5, BoostPlayerMarket.memberIdFromUserNo("10005"));
        assertEquals(5, BoostPlayerMarket.memberIdFromUserNo(" kk000005 "));
        assertEquals(9999, BoostPlayerMarket.memberIdFromUserNo("19999"));
        assertEquals(9999, BoostPlayerMarket.memberIdFromUserNo("KK009999"));
    }

    @Test void malformedAndPartialNumbersAreNotSearchAliases() {
        for (String value : new String[]{"", "1000", "20000", "99999", "KK010000", "10000", "010001", "100000", "10001 OR 1=1", "KK5", "KK0000005", "KK000000", "KK090000", "KK9999999999999999999"}) {
            assertThrows(BoostException.class, () -> BoostPlayerMarket.memberIdFromUserNo(value), value);
        }
    }
}
