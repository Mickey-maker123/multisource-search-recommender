package local.federated.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SearchFeedbackTest {
    @Test void identifierEvidenceIsAdvisoryAndUsesOnlyTheVisibleText() {
        assertTrue(SearchFeedback.lacksTermSupport("怎样配置 WidgetServer？","General server setup"));
        assertFalse(SearchFeedback.lacksTermSupport("怎样配置 WidgetServer？","Configure widget server with this option"));
        assertFalse(SearchFeedback.lacksTermSupport("How does HTTPStatus work?","http.HTTPStatus describes status codes"));
        assertTrue(SearchFeedback.lacksTermSupport("ABC 如何工作？","ABCD instructions"));
        assertFalse(SearchFeedback.lacksTermSupport("怎样配置 ＡＢＣ？","abc settings"));
        assertFalse(SearchFeedback.lacksTermSupport("How do I deploy this?","Installation instructions"));
        // A synonym is not exact identifier evidence: warn, but never remove the result.
        assertTrue(SearchFeedback.lacksTermSupport("怎样配置 WidgetServer？","配置微件服务"));
        assertEquals("low_term_support",SearchFeedback.state("hybrid",false,2,.8,.55,"ABC 怎么做","unrelated"));
        assertEquals("keyword_results",SearchFeedback.state("keyword",false,2,.8,.55,"ABC 怎么做","unrelated"));
        assertEquals("incomplete",SearchFeedback.state("hybrid",true,2,.8,.55,"ABC 怎么做","unrelated"));
        assertEquals("no_results",SearchFeedback.state("hybrid",false,0,.8,.55,"ABC 怎么做","unrelated"));
    }
    @Test void distinguishesUnavailableEmptyWeakAndKeywordEvidence() {
        assertEquals("incomplete",SearchFeedback.state("hybrid",true,0,0,.55));
        assertEquals("no_results",SearchFeedback.state("hybrid",false,0,0,.55));
        assertEquals("low_semantic_match",SearchFeedback.state("hybrid",false,10,.54,.55));
        assertEquals("semantic_results",SearchFeedback.state("hybrid",false,10,.55,.55));
        assertEquals("keyword_results",SearchFeedback.state("keyword",false,10,0,.55));
    }
}
