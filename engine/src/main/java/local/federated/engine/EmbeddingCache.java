package local.federated.engine;

import java.util.*;

/** Same-object, same-guard cache. It stores vectors, never source locations or user authorization. */
final class EmbeddingCache {
    private final ItemStore store;
    private final String modelPolicy;
    private final int capacity;
    private long hits,misses;
    EmbeddingCache(ItemStore store){this(store,EmbeddingClient.MODEL+":"+EmbeddingClient.POLICY,25000);}
    EmbeddingCache(ItemStore store,String modelPolicy,int capacity){
        if(capacity<1)throw new IllegalArgumentException("INVALID_CACHE_CAPACITY");
        this.store=store;this.modelPolicy=modelPolicy;this.capacity=capacity;
    }
    private String id(ItemStore.Projection p,String input){
        return Contract.digest(Contract.JSON.valueToTree(List.of("embedding-cache-v1",p.key(),p.guard(),modelPolicy,input)));
    }
    private boolean current(ItemStore.Projection p)throws Exception {
        var now=store.projection(p.key());return now!=null&&now.ready()&&now.guard().equals(p.guard());
    }
    private static boolean valid(List<Double> vector){
        if(vector.size()!=1024)return false;
        double sum=0;for(Double x:vector){if(x==null||!Double.isFinite(x))return false;sum+=x*x;}
        return Math.abs(Math.sqrt(sum)-1)<=1e-4;
    }
    List<Double> get(ItemStore.Projection p,String input)throws Exception {
        synchronized(store){
            if(!current(p))return null;
            String key=id(p,input);
            try(var statement=store.connection().prepareStatement("SELECT vector FROM embedding_cache WHERE cache_id=?")){
                statement.setString(1,key);
                try(var rows=statement.executeQuery()){
                    if(!rows.next()){misses++;return null;}
                    try{
                        var raw=Contract.JSON.readTree(rows.getString(1));List<Double> vector=new ArrayList<>();
                        if(raw.isArray())for(var x:raw)vector.add(x.isNumber()?x.asDouble():Double.NaN);
                        if(valid(vector)){hits++;return List.copyOf(vector);}
                    }catch(java.io.IOException ignored){ /* Corruption is a miss, not a vector. */ }
                }
            }
            try(var statement=store.connection().prepareStatement("DELETE FROM embedding_cache WHERE cache_id=?")){
                statement.setString(1,key);statement.executeUpdate();
            }
            misses++;return null;
        }
    }
    boolean put(ItemStore.Projection p,String input,List<Double> vector)throws Exception {
        if(!valid(vector))throw new IllegalArgumentException("INVALID_CACHED_VECTOR");
        synchronized(store){
            if(!current(p))return false;
            try(var statement=store.connection().prepareStatement("MERGE INTO embedding_cache (cache_id,item_key,vector) KEY(cache_id) VALUES (?,?,?)")){
                statement.setString(1,id(p,input));statement.setString(2,p.key());statement.setString(3,Contract.JSON.writeValueAsString(vector));statement.executeUpdate();
            }
            long count;try(var s=store.connection().createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM embedding_cache")){r.next();count=r.getLong(1);}
            if(count>capacity)try(var statement=store.connection().prepareStatement("DELETE FROM embedding_cache WHERE cache_id IN (SELECT cache_id FROM embedding_cache ORDER BY created_at,cache_id LIMIT ?)")){
                statement.setLong(1,count-capacity);statement.executeUpdate();
            }
            return true;
        }
    }
    com.fasterxml.jackson.databind.node.ObjectNode status()throws Exception {
        synchronized(store){
            try(var s=store.connection().createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM embedding_cache")){
                r.next();return Contract.JSON.createObjectNode().put("storedVectors",r.getLong(1)).put("capacity",capacity)
                    .put("processHits",hits).put("processMisses",misses);
            }
        }
    }
}
