package com.neugoga.spellblock.datapack;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JsonHelperTest {
	@Test
	void parsesCommentsAndTrailingCommasWhilePreservingStrings() {
		String raw = """
			{
			  // single-line comment
			  "enabled": true,
			  "note": "keep, trailing } and // comments /* inside strings */",
			  /* multi-line
			     comment */
			  "items": [
			    "one",
			    "two", // comment before closing bracket
			  ],
			  "nested": {
			    "a": 1,
			    "b": 2,
			  },
			}
			""";
		JsonObject obj = JsonHelper.parseObject(raw);
		assertTrue(JsonHelper.bool(obj, "enabled", false));
		assertEquals("keep, trailing } and // comments /* inside strings */", JsonHelper.str(obj, "note", ""));
		assertEquals(2, JsonHelper.stringList(obj, "items").size());
		assertEquals(2, obj.getAsJsonObject("nested").get("b").getAsInt());
	}

	@Test
	void rejectsNonObjectOrBrokenJson() {
		assertThrows(RuntimeException.class, () -> JsonHelper.parseObject("[1, 2, 3]"));
		assertThrows(RuntimeException.class, () -> JsonHelper.parseObject("{ \"broken\": "));
	}
}
