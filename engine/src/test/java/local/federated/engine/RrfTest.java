package local.federated.engine;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RrfTest {
    @Test void weightedRoutesChangeRankingWithoutDuplicatingVotes() {
        var routes=List.of(List.of("lexical","lexical","semantic"),List.of("semantic","lexical"));
        var equal=Rrf.fuse(routes,Map.of());
        assertEquals("lexical",equal.get(0).itemId());
        var weighted=Rrf.fuse(routes,Map.of(),List.of(1.0,2.0));
        assertEquals("semantic",weighted.get(0).itemId());
        assertEquals(1.0/62+2.0/61,weighted.get(0).score(),1e-12);
        assertTrue(weighted.get(0).normalized()<=1);
    }
    @Test void singleRouteNormalizationAndStableTieBreakArePreserved() {
        assertEquals(1,Rrf.fuse(List.of(List.of("only")),Map.of(),List.of(2.0)).get(0).normalized(),1e-12);
        var routes=List.of(List.of("a","b"),List.of("b","a"));
        assertEquals("b",Rrf.fuse(routes,Map.of("a","2026-01-01T00:00:00Z","b","2026-02-01T00:00:00Z")).get(0).itemId());
        assertThrows(IllegalArgumentException.class,()->Rrf.fuse(routes,Map.of(),List.of(1.0,Double.NaN)));
    }
}
