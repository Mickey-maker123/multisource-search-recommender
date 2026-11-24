package local.federated.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SearchTextTest {
    @Test void identifiersHaveSupplementalWordBoundariesWithoutChangingChinese() {
        assertEquals("Jsoup parse(html)",SearchText.terms("Jsoup.parse(html)"));
        assertEquals("HTTP Exception",SearchText.terms("HTTPException"));
        assertEquals("response model exclude unset",SearchText.terms("response_model_exclude_unset"));
        assertEquals("内网离线部署",SearchText.terms("内网离线部署"));
        assertEquals("01000001",SearchText.terms("01000001"));
        assertEquals("A".repeat(20000),SearchText.terms("A".repeat(20000)));
    }
}
