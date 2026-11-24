package local.federated.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AccessProjectionTest {
    @TempDir Path temp;
    @Test void allAncestorScopesMustGrantAndStaleProjectionCannotExposeRevokedChild()throws Exception {
        try(var store=new ItemStore(temp);var index=new SearchTest.IndexFixture();var search=new SearchService(store,index.client(),null,"demo",.4)) {
            var parent=IngestionTest.item("parent",1);store.apply(parent);
            var child=IngestionTest.item("child",1);child.set("parent",parent.path("object"));
            child.withObject("acl").put("public",true);child.withObject("acl").putArray("groups");store.apply(child);
            search.sync();
            assertEquals(2,search.search(SearchTest.request(10),SearchTest.ALICE).path("items").size());
            var outsider=new LocalIdentities.Identity("demo",new ItemStore.Principal("outsider",Set.of()));
            assertEquals(0,search.search(SearchTest.request(10),outsider).path("items").size());
            parent.put("version",2);parent.withObject("acl").putArray("groups");store.apply(parent);
            // No sync: old ES scopes still grant, but the final current-state check must deny both.
            assertEquals(0,search.search(SearchTest.request(10),SearchTest.ALICE).path("items").size());
            search.sync();assertEquals(0,search.search(SearchTest.request(10),SearchTest.ALICE).path("items").size());
            parent.put("version",3);parent.withObject("acl").putArray("users").add("alice");store.apply(parent);
            var ticket=store.begin("demo:posts:child",IngestionTest.ALICE);
            var part=Contract.JSON.createObjectNode();part.set("text",child.path("text"));part.set("location",child.path("location"));
            assertTrue(store.finish(ticket,IngestionTest.ALICE,"ready","OK",Contract.JSON.createArrayNode().add(part)));
            search.sync();
            assertEquals(2,search.search(SearchTest.request(10),SearchTest.ALICE).path("items").size());
        }
    }
    @Test void missingScopeIsDeniedAndWithinScopeUserOrGroupOrPublicCanGrant() {
        var filter=Contract.JSON.valueToTree(SearchIndex.accessFilter(IngestionTest.ALICE));
        assertFalse(SearchTest.IndexFixture.matchesClause(Contract.JSON.createObjectNode(),filter,""));
        for(var acl:List.of(Map.of("public",true),Map.of("users",List.of("alice")),Map.of("groups",List.of("research")))) {
            var document=Contract.JSON.valueToTree(Map.of("access_scopes",List.of(acl)));
            assertTrue(SearchTest.IndexFixture.matchesClause(document,filter,""));
        }
        var document=Contract.JSON.valueToTree(Map.of("access_scopes",List.of(Map.of("public",true),Map.of("groups",List.of("secret")))));
        assertFalse(SearchTest.IndexFixture.matchesClause(document,filter,""));
    }
}
