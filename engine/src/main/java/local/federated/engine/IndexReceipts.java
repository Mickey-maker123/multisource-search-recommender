package local.federated.engine;

import java.sql.SQLException;
import java.util.Map;

/** Acknowledgements are written after ES refresh; a crash before ack safely repeats the write. */
final class IndexReceipts {
    private final ItemStore store;
    IndexReceipts(ItemStore store)throws SQLException {
        this.store=store;
        synchronized(store){try(var s=store.connection().createStatement()){
            s.execute("CREATE TABLE IF NOT EXISTS index_receipts (namespace VARCHAR(64) NOT NULL,item_key VARCHAR(194) NOT NULL,guard VARCHAR(64) NOT NULL,complete BOOLEAN NOT NULL,PRIMARY KEY(namespace,item_key))");
        }}
    }
    void load(String namespace,Map<String,String> keywords,Map<String,String> vectors)throws SQLException {
        synchronized(store){try(var p=store.connection().prepareStatement("SELECT item_key,guard,complete FROM index_receipts WHERE namespace=?")){
            p.setString(1,namespace);try(var rows=p.executeQuery()){while(rows.next()){
                keywords.put(rows.getString(1),rows.getString(2));
                if(rows.getBoolean(3))vectors.put(rows.getString(1),rows.getString(2));
            }}
        }}
    }
    boolean acknowledge(String namespace,ItemStore.Projection p,boolean complete)throws Exception {
        synchronized(store){
            var current=store.projection(p.key());
            if(current==null||!current.guard().equals(p.guard()))return false;
            try(var statement=store.connection().prepareStatement("MERGE INTO index_receipts (namespace,item_key,guard,complete) KEY(namespace,item_key) VALUES (?,?,?,?)")){
                statement.setString(1,namespace);statement.setString(2,p.key());statement.setString(3,p.guard());statement.setBoolean(4,complete);statement.executeUpdate();
            }
            return true;
        }
    }
    void forget(String namespace,String key)throws SQLException {
        synchronized(store){try(var p=store.connection().prepareStatement("DELETE FROM index_receipts WHERE namespace=? AND item_key=?")){
            p.setString(1,namespace);p.setString(2,key);p.executeUpdate();
        }}
    }
}
