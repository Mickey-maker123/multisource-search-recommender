package local.federated.engine;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;

final class TypedFilters {
    static void validate(JsonNode clauses) {
        for(var clause:clauses) {
            String type=clause.path("type").asText();var value=clause.path("value");
            if(type.equals("boolean")?!value.isBoolean():!value.isTextual())throw new IllegalArgumentException("INVALID_FILTER_TYPE");
            if(!clause.path("op").asText().equals("eq")&&!Set.of("decimal","date").contains(type))throw new IllegalArgumentException("INVALID_FILTER_OPERATOR");
            TableMapping.value(type,value.asText());
        }
    }
    static boolean matches(JsonNode item,JsonNode clauses) {
        for(var clause:clauses) {
            var field=item.path("typedFields").path(clause.path("field").asText());String type=clause.path("type").asText();
            if(!field.path("type").asText().equals(type))return false;
            String actual=field.path("value").asText(),expected=clause.path("value").asText();
            int comparison=type.equals("decimal")?new BigDecimal(actual).compareTo(new BigDecimal(expected)):actual.compareTo(expected);
            boolean match=switch(clause.path("op").asText()){case "eq" -> comparison==0;case "gte" -> comparison>=0;case "lte" -> comparison<=0;default -> false;};
            if(!match)return false;
        }
        return true;
    }
    static void known(JsonNode clauses,Map<String,Set<String>> available) {
        for(var clause:clauses) {
            var types=available.get(clause.path("field").asText());
            if(types==null)throw new IllegalArgumentException("UNKNOWN_TYPED_FIELD");
            if(types.size()!=1||!types.contains(clause.path("type").asText()))throw new IllegalArgumentException("FIELD_TYPE_MISMATCH");
        }
    }
}
