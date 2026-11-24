package local.federated.engine;

import java.util.*;
import java.sql.SQLException;
import java.io.IOException;

/** Small persistent retrieval metadata; bodies are loaded only for selected objects. Store lock required. */
final class ProjectionCatalog {
    private final ItemStore store;
    private final Map<String,ItemStore.Projection> entries=new TreeMap<>();
    private boolean loaded;
    private boolean dirtyKnown=true;
    private long revision;
    private final Map<String,Set<String>> postings=new HashMap<>();
    private final NavigableSet<String> recent=new TreeSet<>((a,b)->{
        int order=entries.get(b).item().path("updatedAt").asText().compareTo(entries.get(a).item().path("updatedAt").asText());
        return order==0?a.compareTo(b):order;
    });
    private Set<String> terms(ItemStore.Projection p) {
        Set<String> terms=new HashSet<>();var item=p.item();
        String title=RecommendationService.normalize(item.path("title").asText());
        RecommendationService.tokens(title).forEach(t->terms.add("token:"+t));
        title.codePoints().forEach(c->terms.add("char:"+c));
        String source=item.path("object").path("source").asText();
        terms.add("source:"+source);terms.add("category:"+source+":"+item.path("fields").path("category").asText());
        return terms;
    }
    private void index(ItemStore.Projection p,boolean add) {
        if(add)recent.add(p.key());else recent.remove(p.key());
        for(String term:terms(p)) {
            if(add)postings.computeIfAbsent(term,k->new HashSet<>()).add(p.key());
            else {var keys=postings.get(term);if(keys!=null){keys.remove(p.key());if(keys.isEmpty())postings.remove(term);}}
        }
    }
    interface Eligibility {boolean test(ItemStore.Projection projection)throws SQLException,IOException;}
    List<ItemStore.Projection> shortlist(String domain,Set<String> terms,Set<String> seeds,
            Eligibility eligible,
            java.util.function.ToDoubleFunction<ItemStore.Projection> score,int limit)throws SQLException,IOException {
        refresh();Set<String> keys=new HashSet<>(seeds);
        for(String term:terms)keys.addAll(postings.getOrDefault(term,Set.of()));
        Comparator<ItemStore.Projection> order=Comparator.comparingDouble(score).reversed()
            .thenComparing(p->p.item().path("updatedAt").asText(),Comparator.reverseOrder()).thenComparing(ItemStore.Projection::key);
        List<ItemStore.Projection> result=new ArrayList<>();
        for(String key:keys){var p=entries.get(key);if(p!=null&&domain.equals(p.item().path("object").path("domain").asText())&&score.applyAsDouble(p)>0&&eligible.test(p))result.add(p);}
        result.sort(order);if(result.size()>limit)return List.copyOf(result.subList(0,limit));
        Set<String> selected=new HashSet<>();result.forEach(p->selected.add(p.key()));
        for(String key:recent) {
            if(result.size()==limit)break;var p=entries.get(key);
            if(!selected.contains(key)&&domain.equals(p.item().path("object").path("domain").asText())&&eligible.test(p)){result.add(p);selected.add(key);}
        }
        result.sort(order);return List.copyOf(result);
    }
    ProjectionCatalog(ItemStore store)throws SQLException {
        this.store=store;
        try(var s=store.connection().createStatement()){
            s.execute("CREATE TABLE IF NOT EXISTS projection_catalog (item_key VARCHAR(194) PRIMARY KEY, guard VARCHAR(64) NOT NULL, ready BOOLEAN NOT NULL, metadata CLOB NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS projection_dirty (item_key VARCHAR(194) PRIMARY KEY)");
            s.executeUpdate("MERGE INTO projection_dirty (item_key) KEY(item_key) SELECT item_key FROM items WHERE item_key NOT IN (SELECT item_key FROM projection_catalog) OR state='parsing'");
        }
    }
    void dirty(String key)throws SQLException {
        revision++;
        dirtyKnown=true;
        try(var p=store.connection().prepareStatement("MERGE INTO projection_dirty (item_key) KEY(item_key) VALUES (?)")){p.setString(1,key);p.executeUpdate();}
    }
    long revision(){return revision;}
    Collection<ItemStore.Projection> entries()throws SQLException,IOException { refresh();return List.copyOf(entries.values()); }
    ItemStore.Projection get(String key)throws SQLException,IOException {refresh();return entries.get(key);}
    private void refresh()throws SQLException,IOException {
        if(loaded&&!dirtyKnown)return;
        if(!loaded){
            try(var s=store.connection().createStatement();var r=s.executeQuery("SELECT item_key,guard,ready,metadata FROM projection_catalog")){
                while(r.next())entries.put(r.getString(1),new ItemStore.Projection(r.getString(1),Contract.JSON.readTree(r.getString(4)),r.getString(2),List.of(),r.getBoolean(3)));
            }
            loaded=true;
            entries.values().forEach(p->index(p,true));
        }
        List<String> dirty=new ArrayList<>();
        try(var s=store.connection().createStatement();var r=s.executeQuery("SELECT item_key FROM projection_dirty ORDER BY item_key")){while(r.next())dirty.add(r.getString(1));}
        for(String key:dirty){
            var projection=store.projection(key);if(projection==null)continue;
            var metadata=projection.item().deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)metadata).remove("text");
            try(var p=store.connection().prepareStatement("MERGE INTO projection_catalog (item_key,guard,ready,metadata) KEY(item_key) VALUES (?,?,?,?)")){
                p.setString(1,key);p.setString(2,projection.guard());p.setBoolean(3,projection.ready());p.setString(4,Contract.JSON.writeValueAsString(metadata));p.executeUpdate();
            }
            try(var p=store.connection().prepareStatement("DELETE FROM projection_dirty WHERE item_key=?")){p.setString(1,key);p.executeUpdate();}
            var previous=entries.get(key);if(previous!=null)index(previous,false);
            var next=new ItemStore.Projection(key,metadata,projection.guard(),List.of(),projection.ready());
            entries.put(key,next);index(next,true);
        }
        dirtyKnown=false;
    }
}
