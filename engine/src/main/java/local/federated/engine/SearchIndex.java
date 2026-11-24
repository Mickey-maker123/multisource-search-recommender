package local.federated.engine;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** ES requests are confined to an engine-owned index on an independently named loopback cluster. */
public final class SearchIndex {
    private final URI base;
    private final String name;
    private final HttpClient http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build();
    public SearchIndex(URI base,String name) {
        if(!"http".equals(base.getScheme())||!"127.0.0.1".equals(base.getHost())||base.getPort()<19200||base.getPort()>19299||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null||!Set.of("","/").contains(base.getPath()))throw new IllegalArgumentException("UNSAFE_ES_ENDPOINT");
        if(!name.matches("engine-v01-items(?:-test-[a-f0-9]{8})?"))throw new IllegalArgumentException("UNSAFE_INDEX_NAME");
        this.base=URI.create(base.toString().replaceAll("/+$",""));this.name=name;
    }
    private HttpResponse<String> call(String method,String path,Object body)throws IOException,InterruptedException {
        var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json");
        return http.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(Contract.JSON.writeValueAsBytes(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode ok(HttpResponse<String> response)throws IOException {
        if(response.statusCode()<200||response.statusCode()>=300)throw new IOException("INDEX_UNAVAILABLE");
        return Contract.JSON.readTree(response.body());
    }
    public String ensure()throws IOException,InterruptedException {
        var cluster=ok(call("GET","/",null));if(!"engine-v01".equals(cluster.path("cluster_name").asText()))throw new IOException("WRONG_CLUSTER");
        var existing=call("GET","/"+name+"/_mapping",null);
        if(existing.statusCode()==404) {
            Map<String,Object> props=new LinkedHashMap<>();
            for(String field:List.of("doc_id","root_key","guard","kind","domain","source","type","category","status","model","policy"))props.put(field,Map.of("type","keyword"));
            props.put("title",Map.of("type","text","analyzer","cjk"));props.put("text",Map.of("type","text","analyzer","cjk"));
            props.put("title_terms",Map.of("type","text","analyzer","cjk"));props.put("text_terms",Map.of("type","text","analyzer","cjk"));
            props.put("updated_at",Map.of("type","date"));props.put("location",Map.of("type","object","enabled",false));
            props.put("vector",Map.of("type","dense_vector","dims",1024,"index",true,"similarity","cosine"));
            props.put("access_scopes",accessMapping());
            ok(call("PUT","/"+name,Map.of("settings",Map.of("number_of_shards",1,"number_of_replicas",0),"mappings",Map.of("dynamic","strict","_meta",Map.of("engine","local.federated.engine","policy",EmbeddingClient.POLICY),"properties",props))));
        } else {
            var mapping=ok(existing).path(name).path("mappings");
            if(!"local.federated.engine".equals(mapping.path("_meta").path("engine").asText())||!EmbeddingClient.POLICY.equals(mapping.path("_meta").path("policy").asText())||mapping.path("properties").path("vector").path("dims").asInt()!=1024||!"cjk".equals(mapping.path("properties").path("text").path("analyzer").asText()))throw new IOException("INDEX_MAPPING_MISMATCH");
            for(String field:List.of("title_terms","text_terms")) {
                if(!mapping.path("properties").has(field))
                    ok(call("PUT","/"+name+"/_mapping",Map.of("properties",Map.of(field,Map.of("type","text","analyzer","cjk")))));
                else if(!"cjk".equals(mapping.path("properties").path(field).path("analyzer").asText()))throw new IOException("INDEX_MAPPING_MISMATCH");
            }
            if(!mapping.path("properties").has("access_scopes"))ok(call("PUT","/"+name+"/_mapping",Map.of("properties",Map.of("access_scopes",accessMapping()))));
            else if(!"nested".equals(mapping.path("properties").path("access_scopes").path("type").asText()))throw new IOException("INDEX_MAPPING_MISMATCH");
        }
        String uuid=ok(call("GET","/"+name+"/_settings",null)).path(name).path("settings").path("index").path("uuid").asText();
        if(uuid.isBlank())throw new IOException("INDEX_IDENTITY_MISSING");
        return Contract.digest(Contract.JSON.valueToTree(List.of(base.toString(),name,uuid,"engine-index-v3-access",EmbeddingClient.MODEL,EmbeddingClient.POLICY)));
    }
    private static Map<String,Object> accessMapping(){return Map.of("type","nested","properties",Map.of("public",Map.of("type","boolean"),"users",Map.of("type","keyword"),"groups",Map.of("type","keyword")));}
    static Object accessFilter(ItemStore.Principal principal) {
        List<Object> grants=new ArrayList<>();
        grants.add(Map.of("term",Map.of("access_scopes.public",true)));
        grants.add(Map.of("term",Map.of("access_scopes.users",principal.user())));
        if(!principal.groups().isEmpty())grants.add(Map.of("terms",Map.of("access_scopes.groups",new TreeSet<>(principal.groups()))));
        Object allowed=Map.of("bool",Map.of("should",grants,"minimum_should_match",1));
        Object denied=Map.of("nested",Map.of("path","access_scopes","query",Map.of("bool",Map.of("must_not",List.of(allowed)))));
        // An absent ACL is denied, and every scope (object plus ancestors) must grant access.
        Object exists=Map.of("nested",Map.of("path","access_scopes","query",Map.of("match_all",Map.of())));
        return Map.of("bool",Map.of("filter",List.of(exists),"must_not",List.of(denied)));
    }
    public void replace(String key,List<Map<String,Object>> documents)throws IOException,InterruptedException {
        replaceBatch(Map.of(key,documents));
    }
    public void replaceBatch(Map<String,List<Map<String,Object>>> replacements)throws IOException,InterruptedException {
        if(replacements.isEmpty())return;
        var deletion=ok(call("POST","/"+name+"/_delete_by_query?conflicts=proceed",Map.of("query",Map.of("terms",Map.of("root_key",new ArrayList<>(replacements.keySet()))))));
        if(deletion.path("timed_out").asBoolean()||deletion.path("failures").size()>0)throw new IOException("INCOMPLETE_DELETE");
        List<Map<String,Object>> documents=new ArrayList<>();replacements.values().forEach(documents::addAll);
        for(int offset=0;offset<documents.size();offset+=50) {
            StringBuilder body=new StringBuilder();int end=Math.min(offset+50,documents.size());
            for(int i=offset;i<end;i++){
                var document=documents.get(i);
                body.append(Contract.JSON.writeValueAsString(Map.of("index",Map.of("_id",document.get("doc_id"))))).append('\n');
                body.append(Contract.JSON.writeValueAsString(document)).append('\n');
            }
            var request=HttpRequest.newBuilder(URI.create(base+"/"+name+"/_bulk")).timeout(Duration.ofSeconds(10))
                .header("Content-Type","application/x-ndjson").POST(HttpRequest.BodyPublishers.ofString(body.toString(),java.nio.charset.StandardCharsets.UTF_8)).build();
            var response=ok(http.send(request,HttpResponse.BodyHandlers.ofString()));
            if(response.path("errors").asBoolean()||!response.path("items").isArray()||response.path("items").size()!=end-offset)throw new IOException("INCOMPLETE_BULK");
            for(var item:response.path("items")){
                int status=item.path("index").path("status").asInt();
                if(status<200||status>=300||item.path("index").has("error"))throw new IOException("INCOMPLETE_BULK");
            }
        }
        ok(call("POST","/"+name+"/_refresh",null));
    }
    public List<JsonNode> recall(String domain,List<String> allowed,JsonNode filters,String query,List<Double> vector,double minimum)throws IOException,InterruptedException {
        return recall(domain,allowed,null,filters,query,vector,minimum);
    }
    public List<JsonNode> recall(String domain,List<String> allowed,ItemStore.Principal principal,JsonNode filters,String query,List<Double> vector,double minimum)throws IOException,InterruptedException {
        if(allowed!=null&&allowed.isEmpty())return List.of();
        if(allowed==null&&principal==null)throw new IllegalArgumentException("SEARCH_AUTHORIZATION_REQUIRED");
        List<Object> rules=new ArrayList<>();rules.add(Map.of("term",Map.of("domain",domain)));
        if(allowed!=null)rules.add(Map.of("terms",Map.of("root_key",allowed)));
        if(principal!=null)rules.add(accessFilter(principal));
        rules.add(Map.of("term",Map.of("kind",vector==null?"keyword":"vector")));
        for(var f:Map.of("sources","source","types","type").entrySet())if(filters.has(f.getKey()))rules.add(Map.of("terms",Map.of(f.getValue(),Contract.JSON.convertValue(filters.get(f.getKey()),List.class))));
        for(String f:List.of("category","status"))if(filters.has(f))rules.add(Map.of("term",Map.of(f,filters.get(f).asText())));
        Map<String,String> time=new HashMap<>();if(filters.has("updatedFrom"))time.put("gte",filters.get("updatedFrom").asText());if(filters.has("updatedTo"))time.put("lt",filters.get("updatedTo").asText());
        if(!time.isEmpty())rules.add(Map.of("range",Map.of("updated_at",time)));
        Map<String,Object> request=new HashMap<>();request.put("size",500);request.put("_source",List.of("root_key","guard","text","location","doc_id"));
        if(vector==null) {
            Object match=query.isBlank()?Map.of("match_all",Map.of()):Map.of("dis_max",Map.of("queries",List.of(
                Map.of("multi_match",Map.of("query",query,"fields",List.of("title^3","text"))),
                Map.of("multi_match",Map.of("query",SearchText.terms(query),"fields",List.of("title_terms^1.5","text_terms^0.5"))))));
            request.put("query",Map.of("bool",Map.of("filter",rules,"must",List.of(match))));
            request.put("sort",List.of(Map.of("_score","desc"),Map.of("updated_at","desc"),Map.of("doc_id","asc")));
            // Count candidate objects, not chunks: a long object must not exhaust the window.
            request.put("collapse",Map.of("field","root_key"));
        } else {
            rules.add(Map.of("term",Map.of("model",EmbeddingClient.MODEL)));rules.add(Map.of("term",Map.of("policy",EmbeddingClient.POLICY)));
            // Repeated vectors can exhaust a narrow HNSW traversal before enough neighbours are found.
            request.put("knn",Map.of("field","vector","query_vector",vector,"k",500,"num_candidates",4000,"filter",Map.of("bool",Map.of("filter",rules))));
        }
        var response=ok(call("POST","/"+name+"/_search",request));
        if(!response.path("hits").path("hits").isArray()||response.path("timed_out").asBoolean()||response.path("_shards").path("failed").asInt()>0)throw new IOException("PARTIAL_SEARCH");
        List<JsonNode> hits=new ArrayList<>();for(var hit:response.path("hits").path("hits"))if(vector==null||2*hit.path("_score").asDouble()-1>=minimum) {
            if(!hit.path("_source").isObject())throw new IOException("INVALID_SEARCH_HIT");
            var source=(com.fasterxml.jackson.databind.node.ObjectNode)hit.path("_source");
            if(vector!=null)source.put("_vectorCosine",2*hit.path("_score").asDouble()-1);
            hits.add(source);
        }return hits;
    }
}
