package za.co.neroland.nerolink.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Untrusted-JSON parsing never throws and refuses hostile nesting before the parser sees it. */
class JsonTest {

    @Test
    void parsesObjects() {
        assertEquals("x", Json.parseObject("{\"a\":\"x\"}").get("a").getAsString());
    }

    @Test
    void rejectsNonObjectsAndGarbage() {
        assertNull(Json.parseObject("[1,2]"));
        assertNull(Json.parseObject("\"str\""));
        assertNull(Json.parseObject("{nope"));
        assertNull(Json.parseObject(""));
        assertNull(Json.parseObject(null));
    }

    @Test
    void rejectsDeepNestingWithoutThrowing() {
        String deep = "{\"a\":" + "[".repeat(100_000) + "]".repeat(100_000) + "}";
        assertNull(Json.parseObject(deep));
    }

    @Test
    void bracketsInsideStringsDoNotCount() {
        String s = "{\"a\":\"" + "[".repeat(100) + "\\\"" + "\"}";
        assertFalse(Json.exceedsDepth(s, Json.MAX_DEPTH));
        assertTrue(Json.exceedsDepth("[".repeat(Json.MAX_DEPTH + 1), Json.MAX_DEPTH));
    }
}
