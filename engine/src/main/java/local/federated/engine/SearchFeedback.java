package local.federated.engine;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Describes retrieval evidence; never claims that a fact does not exist or an answer is verified. */
final class SearchFeedback {
    private SearchFeedback() {}
    private static final Pattern IDENTIFIER=Pattern.compile("[A-Za-z][A-Za-z0-9]*");
    private static String lexical(String value) {
        return SearchText.terms(Normalizer.normalize(value,Normalizer.Form.NFKC)).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+"," ").strip();
    }
    static boolean lacksTermSupport(String query,String evidence) {
        query=Normalizer.normalize(query,Normalizer.Form.NFKC);
        boolean mixed=query.codePoints().anyMatch(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN);
        String visible=" "+lexical(evidence)+" ";
        var words=IDENTIFIER.matcher(query);
        while(words.find()) {
            String word=words.group();
            if(word.length()<3)continue;
            boolean technical=mixed||word.equals(word.toUpperCase(Locale.ROOT))||!SearchText.terms(word).equals(word);
            if(technical&&!visible.contains(" "+lexical(word)+" "))return true;
        }
        return false;
    }
    static String state(String mode,boolean degraded,int count,double bestCosine,double threshold,String query,String evidence) {
        String state=state(mode,degraded,count,bestCosine,threshold);
        return state.equals("semantic_results")&&lacksTermSupport(query,evidence)?"low_term_support":state;
    }
    static String state(String mode,boolean degraded,int count,double bestCosine,double threshold) {
        if(degraded)return "incomplete";
        if(count==0)return "no_results";
        if(mode.equals("keyword"))return "keyword_results";
        return bestCosine<threshold?"low_semantic_match":"semantic_results";
    }
}
