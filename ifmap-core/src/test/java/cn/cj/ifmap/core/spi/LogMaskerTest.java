package cn.cj.ifmap.core.spi;

import cn.cj.ifmap.core.util.Logs;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 默认脱敏实现 + 截断。 */
class LogMaskerTest {

    private final DefaultLogMasker masker = new DefaultLogMasker();

    @Test
    void masksPhoneNumber() {
        assertEquals("{\"mobile\":\"138****8888\"}", masker.mask("{\"mobile\":\"13812348888\"}"));
    }

    @Test
    void masksIdCardKeepingSixAndFour() {
        assertEquals("{\"certNo\":\"110101********1234\"}",
                masker.mask("{\"certNo\":\"110101199001011234\"}"));
    }

    @Test
    void masksBankCardKeepingSixAndFour() {
        assertEquals("{\"acctNo\":\"622202******7890\"}",
                masker.mask("{\"acctNo\":\"6222021234567890\"}"));
    }

    @Test
    void masksNameFieldsByFieldName() {
        assertEquals("{\"acctName\":\"张*\"}", masker.mask("{\"acctName\":\"张三\"}"));
        assertEquals("{\"acctName\":\"张**\"}", masker.mask("{\"acctName\":\"张小三\"}"));
        assertEquals("{\"ACCTNAME\":\"李*\"}", masker.mask("{\"ACCTNAME\":\"李四\"}"));
    }

    @Test
    void masksUnquotedNumericBankCard() {
        assertEquals("{\"acctNo\":\"622202******7890\"}", masker.mask("{\"acctNo\":6222021234567890}"));
    }

    @Test
    void doesNotTouchShortNumbersOrAmounts() {
        String json = "{\"amount\":\"1000.00\",\"seq\":\"123456\",\"count\":3}";
        assertEquals(json, masker.mask(json));
    }

    @Test
    void doesNotMaskNestedJsonString() {
        String json = "{\"sub\":\"{\\\"mobile\\\":\\\"13812348888\\\"}\"}";
        assertEquals(json, masker.mask(json));
    }

    @Test
    void emptyMaskFieldsFallsBackToDefaults() {
        DefaultLogMasker fromConfig = new DefaultLogMasker(new java.util.LinkedHashSet<String>(),
                new java.util.LinkedHashSet<String>());
        assertEquals("{\"acctName\":\"张*\"}", fromConfig.mask("{\"acctName\":\"张三\"}"));
    }

    @Test
    void excludeFieldsAreReplacedEntirely() {
        DefaultLogMasker strict = new DefaultLogMasker(Collections.singleton("acctName"),
                Collections.singleton("idCard"));
        assertEquals("{\"idCard\":\"***\"}", strict.mask("{\"idCard\":\"110101199001011234\"}"));
    }

    @Test
    void isIdempotent() {
        String once = masker.mask("{\"mobile\":\"13812348888\",\"acctName\":\"张三\"}");
        assertEquals(once, masker.mask(once));
    }

    @Test
    void nullsAndNonJsonAreSafe() {
        assertNull(masker.mask(null));
        assertEquals("", masker.mask(""));
        assertEquals("plain text", masker.mask("plain text"));
    }

    @Test
    void headTailShortValueIsAllStars() {
        assertEquals("****", DefaultLogMasker.headTail("1234", 6, 4));
        assertEquals("622202******7890", DefaultLogMasker.headTail("6222021234567890", 6, 4));
    }

    @Test
    void truncatesAndMarks() {
        assertEquals("abcdefg" + Logs.TRUNCATED, Logs.truncate("abcdefghij", 7));
        assertEquals("abcdefghij", Logs.truncate("abcdefghij", 10));
        assertEquals("abcdefghij", Logs.truncate("abcdefghij", 0));
        assertNull(Logs.truncate(null, 5));
        assertTrue(Logs.truncate("abcdefghij", 3).endsWith(Logs.TRUNCATED));
        assertFalse(Logs.truncate("abc", 3).contains(Logs.TRUNCATED));
    }
}
