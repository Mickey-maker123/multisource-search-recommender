package local.federated.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** A declarative column map. Only explicitly selected non-sensitive fields form search text. */
final class TableMapping {
    private final JsonNode config;
    private static final Set<String> TYPES=Set.of("string","decimal","date","boolean");
    TableMapping(JsonNode config) {
        fields(config,Set.of("schemaVersion","idColumn","titleColumn","fields"));
        if(!config.path("schemaVersion").isTextual()||!config.path("schemaVersion").asText().equals("1.0")||!name(config.path("idColumn"))||!name(config.path("titleColumn")))fail("INVALID_TABLE_MAPPING");
        var defs=config.path("fields");
        if(!defs.isObject()||defs.size()>32)fail("INVALID_TABLE_MAPPING");
        defs.fields().forEachRemaining(entry->{
            if(!entry.getKey().matches("[A-Za-z][A-Za-z0-9_]{0,47}"))fail("INVALID_FIELD_NAME");
            var field=entry.getValue();fields(field,Set.of("column","type","required","semantic","sensitive"));
            if(!name(field.path("column"))||!TYPES.contains(field.path("type").asText()))fail("INVALID_TABLE_MAPPING");
            for(String flag:List.of("required","semantic","sensitive"))if(field.has(flag)&&!field.get(flag).isBoolean())fail("INVALID_TABLE_MAPPING");
            boolean sensitive=field.path("sensitive").asBoolean()||Set.of("phone","telephone","mobile","address").contains(entry.getKey().toLowerCase(Locale.ROOT));
            if(sensitive&&(field.path("semantic").asBoolean()||field.path("column").asText().equals(config.path("titleColumn").asText())))fail("SENSITIVE_SEMANTIC_FIELD");
        });
        this.config=config.deepCopy();
    }
    ObjectNode map(JsonNode table) {
        if(!table.path("state").asText().equals("ready"))fail("TABLE_NOT_READY");
        Set<String> columns=new HashSet<>();table.path("headers").forEach(h->columns.add(h.asText()));
        for(String field:List.of("idColumn","titleColumn"))if(!columns.contains(config.path(field).asText()))fail("MISSING_COLUMN");
        config.path("fields").forEach(f->{if(!columns.contains(f.path("column").asText()))fail("MISSING_COLUMN");});
        var result=Contract.JSON.createObjectNode();var rows=result.putArray("rows");int errors=0;
        Map<String,Integer> counts=new HashMap<>();
        for(var row:table.path("rows"))if(row.path("code").asText().equals("OK"))counts.merge(row.path("values").path(config.path("idColumn").asText()).asText(),1,Integer::sum);
        for(var row:table.path("rows")) {
            var out=rows.addObject().put("row",row.path("row").asInt());
            try {
                if(!row.path("code").asText().equals("OK"))fail(row.path("code").asText());
                String id=cell(row,config.path("idColumn").asText(),true),title=cell(row,config.path("titleColumn").asText(),true);
                if(id.isBlank()||id.length()>256||title.isBlank()||title.length()>512)fail("INVALID_ROW_ID_OR_TITLE");
                if(counts.getOrDefault(id,0)>1)fail("DUPLICATE_ROW_ID");
                out.put("nativeId",id).put("title",title);StringBuilder text=new StringBuilder(title);var typed=out.putObject("typedFields");
                for(var entries=config.path("fields").fields();entries.hasNext();) {
                    var entry=entries.next();var field=entry.getValue();String type=field.path("type").asText();
                    String raw=cell(row,field.path("column").asText(),type.equals("string"));
                    if(raw.isEmpty()){if(field.path("required").asBoolean())fail("REQUIRED_VALUE_MISSING");continue;}
                    JsonNode value=value(type,raw);
                    typed.putObject(entry.getKey()).put("type",type).set("value",value);
                    if(field.path("semantic").asBoolean())text.append('\n').append(entry.getKey()).append(": ").append(raw);
                }
                if(text.codePointCount(0,text.length())>20000)fail("ROW_TEXT_LIMIT");
                out.put("text",text.toString()).put("code","OK");
            } catch(IllegalArgumentException e) {
                out.removeAll();out.put("row",row.path("row").asInt()).put("code",e.getMessage()!=null&&e.getMessage().matches("[A-Z_]{1,64}")?e.getMessage():"INVALID_TYPED_VALUE");errors++;
            }
        }
        return result.put("errors",errors).put("total",rows.size());
    }
    private static String cell(JsonNode row,String column,boolean stringOnly) {
        if(stringOnly&&!row.path("cellTypes").path(column).asText().equals("string"))fail("STRING_CELL_REQUIRED");
        return row.path("values").path(column).asText();
    }
    static JsonNode value(String type,String raw) {
        try {return switch(type) {
            case "string" -> {if(raw.length()>4096||raw.indexOf('\0')>=0)fail("INVALID_TYPED_VALUE");yield TextNode.valueOf(raw);}
            case "decimal" -> {if(!raw.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?"))fail("INVALID_TYPED_VALUE");var number=new BigDecimal(raw);if(number.precision()>20||number.scale()>8)fail("DECIMAL_LIMIT");yield TextNode.valueOf(number.stripTrailingZeros().toPlainString());}
            case "date" -> {if(!raw.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))fail("INVALID_TYPED_VALUE");LocalDate.parse(raw);yield TextNode.valueOf(raw);}
            case "boolean" -> {if(!Set.of("true","false").contains(raw))fail("INVALID_TYPED_VALUE");yield BooleanNode.valueOf(raw.equals("true"));}
            default -> throw new IllegalArgumentException("INVALID_FIELD_TYPE");
        };}catch(java.time.DateTimeException|NumberFormatException e){throw new IllegalArgumentException("INVALID_TYPED_VALUE");}
    }
    private static boolean name(JsonNode n){return n.isTextual()&&!n.asText().isBlank()&&n.asText().length()<=128;}
    private static void fields(JsonNode n,Set<String> allowed){if(!n.isObject())fail("INVALID_TABLE_MAPPING");n.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))fail("INVALID_TABLE_MAPPING");});}
    private static void fail(String code){throw new IllegalArgumentException(code);}
}
