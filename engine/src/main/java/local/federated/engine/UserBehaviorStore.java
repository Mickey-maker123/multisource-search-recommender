package local.federated.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Shares the source store's transaction lock and lifetime. */
public final class UserBehaviorStore {
    public record EventFact(JsonNode event, boolean profileAllowed, long objectVersion, String objectGuard) {}
    private final ItemStore store;
    private final Connection db;
    private final Contract contract = new Contract();
    private final Clock clock;
    private final BehaviorPolicy policy;

    public UserBehaviorStore(ItemStore store, BehaviorPolicy policy) throws SQLException, IOException {
        this(store, policy, Clock.systemUTC());
    }

    UserBehaviorStore(ItemStore store, BehaviorPolicy policy, Clock clock) throws SQLException, IOException {
        this.store = store;
        this.db = store.connection();
        this.policy = policy;
        this.clock = clock;
        synchronized (store) {
            try (var statement = db.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS engine_users (domain VARCHAR(64) NOT NULL, user_id VARCHAR(64) NOT NULL, version BIGINT NOT NULL, digest VARCHAR(64) NOT NULL, payload CLOB NOT NULL, PRIMARY KEY(domain,user_id))");
                statement.execute("CREATE TABLE IF NOT EXISTS engine_identities (domain VARCHAR(64) NOT NULL, source VARCHAR(64) NOT NULL, local_user_id VARCHAR(64) NOT NULL, user_id VARCHAR(64) NOT NULL, PRIMARY KEY(domain,source,local_user_id))");
                statement.execute("CREATE TABLE IF NOT EXISTS engine_events (domain VARCHAR(64) NOT NULL, source VARCHAR(64) NOT NULL, event_id VARCHAR(64) NOT NULL, owner_user VARCHAR(64), owner_session VARCHAR(64), object_key VARCHAR(194) NOT NULL, action VARCHAR(32) NOT NULL, occurred_ms BIGINT NOT NULL, digest VARCHAR(64) NOT NULL, payload CLOB NOT NULL, profile_allowed BOOLEAN NOT NULL, PRIMARY KEY(domain,source,event_id))");
                statement.execute("ALTER TABLE engine_events ADD COLUMN IF NOT EXISTS object_version BIGINT DEFAULT 0");
                statement.execute("ALTER TABLE engine_events ADD COLUMN IF NOT EXISTS object_guard VARCHAR(64)");
                statement.execute("CREATE INDEX IF NOT EXISTS engine_events_user_time ON engine_events(domain,owner_user,occurred_ms)");
            }
        }
    }

    public Clock clock() { return clock; }
    public BehaviorPolicy policy() { return policy; }

    public JsonNode user(String domain, String userId) throws SQLException, IOException {
        synchronized (store) {
            try (var query = db.prepareStatement("SELECT payload FROM engine_users WHERE domain=? AND user_id=?")) {
                query.setString(1, domain);
                query.setString(2, userId);
                try (var rows = query.executeQuery()) {
                    return rows.next() ? Contract.JSON.readTree(rows.getString(1)) : null;
                }
            }
        }
    }

    public LocalIdentities.Identity resolve(LocalIdentities.Identity authenticated) throws SQLException, IOException {
        synchronized (store) {
            JsonNode user = user(authenticated.domain(), authenticated.principal().user());
            if (user == null) return authenticated;
            if (!user.path("active").asBoolean()) return null;
            Set<String> groups = new HashSet<>();
            user.path("groups").forEach(group -> groups.add(group.asText()));
            return new LocalIdentities.Identity(authenticated.domain(), new ItemStore.Principal(authenticated.principal().user(), Set.copyOf(groups)));
        }
    }

    public String resolveLocalUser(String domain, String source, String localId) throws SQLException, IOException {
        synchronized (store) {
            try (var query = db.prepareStatement("SELECT user_id FROM engine_identities WHERE domain=? AND source=? AND local_user_id=?")) {
                query.setString(1, domain);
                query.setString(2, source);
                query.setString(3, localId);
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) return null;
                    String id = rows.getString(1);
                    JsonNode user = user(domain, id);
                    return user != null && user.path("active").asBoolean() ? id : null;
                }
            }
        }
    }

    public ItemStore.Outcome applyUser(String domain, JsonNode input) throws SQLException, IOException {
        synchronized (store) {
            try {
                if (domain == null || !domain.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) throw new IllegalArgumentException("INVALID_DOMAIN");
                contract.validateUser(input);
                String id = input.path("userId").asText();
                JsonNode previous = user(domain, id);
                if (previous != null) {
                    long version = input.path("version").asLong();
                    if (version < previous.path("version").asLong()) return new ItemStore.Outcome("stale", "OLDER_VERSION");
                    if (version == previous.path("version").asLong()) {
                        return Contract.digest(previous).equals(Contract.digest(input))
                                ? new ItemStore.Outcome("duplicate", "SAME_VERSION")
                                : new ItemStore.Outcome("rejected", "VERSION_CONFLICT");
                    }
                }
                for (JsonNode identity : input.path("identities")) {
                    if (!domain.equals(identity.path("domain").asText())) throw new IllegalArgumentException("IDENTITY_DOMAIN_MISMATCH");
                    try (var query = db.prepareStatement("SELECT user_id FROM engine_identities WHERE domain=? AND source=? AND local_user_id=?")) {
                        query.setString(1, domain);
                        query.setString(2, identity.path("source").asText());
                        query.setString(3, identity.path("localUserId").asText());
                        try (var rows = query.executeQuery()) {
                            if (rows.next() && !id.equals(rows.getString(1))) throw new IllegalArgumentException("IDENTITY_CONFLICT");
                        }
                    }
                }
                db.setAutoCommit(false);
                try {
                    try (var delete = db.prepareStatement("DELETE FROM engine_identities WHERE domain=? AND user_id=?")) {
                        delete.setString(1, domain);
                        delete.setString(2, id);
                        delete.executeUpdate();
                    }
                    try (var insert = db.prepareStatement("INSERT INTO engine_identities VALUES (?,?,?,?)")) {
                        for (JsonNode identity : input.path("identities")) {
                            insert.setString(1, domain);
                            insert.setString(2, identity.path("source").asText());
                            insert.setString(3, identity.path("localUserId").asText());
                            insert.setString(4, id);
                            insert.executeUpdate();
                        }
                    }
                    try (var update = db.prepareStatement("MERGE INTO engine_users KEY(domain,user_id) VALUES (?,?,?,?,?)")) {
                        update.setString(1, domain);
                        update.setString(2, id);
                        update.setLong(3, input.path("version").asLong());
                        update.setString(4, Contract.digest(input));
                        update.setString(5, Contract.JSON.writeValueAsString(input));
                        update.executeUpdate();
                    }
                    db.commit();
                } catch (SQLException | IOException | RuntimeException error) {
                    db.rollback();
                    throw error;
                } finally {
                    db.setAutoCommit(true);
                }
                return new ItemStore.Outcome("applied", "OK");
            } catch (IllegalArgumentException error) {
                return rejected(error);
            }
        }
    }

    private JsonNode event(String domain, String source, String eventId) throws SQLException, IOException {
        try (var query = db.prepareStatement("SELECT payload FROM engine_events WHERE domain=? AND source=? AND event_id=?")) {
            query.setString(1, domain);
            query.setString(2, source);
            query.setString(3, eventId);
            try (var rows = query.executeQuery()) {
                return rows.next() ? Contract.JSON.readTree(rows.getString(1)) : null;
            }
        }
    }

    private boolean mappedSource(JsonNode user, String domain, String source) {
        for (JsonNode identity : user.path("identities")) {
            if (domain.equals(identity.path("domain").asText()) && source.equals(identity.path("source").asText())) return true;
        }
        return false;
    }

    public ItemStore.Outcome applyEvent(String domain, JsonNode input, LocalIdentities.Identity actor) throws SQLException, IOException {
        synchronized (store) {
            try {
                if (domain == null || !domain.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) throw new IllegalArgumentException("INVALID_DOMAIN");
                contract.validateEvent(input);
                if (!domain.equals(input.path("domain").asText()) || !domain.equals(input.path("object").path("domain").asText())) throw new IllegalArgumentException("DOMAIN_FORBIDDEN");
                if (actor != null && (!domain.equals(actor.domain()) || !input.has("userId") || !actor.principal().user().equals(input.path("userId").asText()))) throw new IllegalArgumentException("ACTOR_MISMATCH");
                long time;
                try { time = Instant.parse(input.path("occurredAt").asText()).toEpochMilli(); }
                catch (ArithmeticException error) { throw new IllegalArgumentException("INVALID_TIME"); }
                if (time > clock.millis() + 300000) throw new IllegalArgumentException("FUTURE_EVENT");
                String source = input.path("source").asText();
                String eventId = input.path("eventId").asText();
                JsonNode previous = event(domain, source, eventId);
                if (previous != null) {
                    return Contract.digest(previous).equals(Contract.digest(input))
                            ? new ItemStore.Outcome("duplicate", "SAME_EVENT")
                            : new ItemStore.Outcome("rejected", "EVENT_ID_CONFLICT");
                }
                JsonNode user = input.has("userId") ? user(domain, input.path("userId").asText()) : null;
                if (input.has("userId") && (user == null || !user.path("active").asBoolean())) throw new IllegalArgumentException("USER_UNAVAILABLE");
                String objectKey = Contract.key(input.path("object"));
                boolean undo = input.path("action").asText().equals("undo");
                if (undo) {
                    JsonNode target = event(domain, source, input.path("undoOf").asText());
                    if (target == null || target.path("action").asText().equals("undo")
                            || !target.path("object").equals(input.path("object"))
                            || !sameOwner(target, input)
                            || Instant.parse(input.path("occurredAt").asText()).isBefore(Instant.parse(target.path("occurredAt").asText()))) {
                        throw new IllegalArgumentException("INVALID_UNDO");
                    }
                } else {
                    ItemStore.Principal principal;
                    if (user != null) {
                        if (!mappedSource(user, domain, source)) throw new IllegalArgumentException("SOURCE_IDENTITY_UNMAPPED");
                        Set<String> groups = new HashSet<>();
                        user.path("groups").forEach(group -> groups.add(group.asText()));
                        principal = new ItemStore.Principal(input.path("userId").asText(), groups);
                    } else {
                        principal = new ItemStore.Principal("\u0000anonymous", Set.of());
                    }
                    if (!store.canAccess(objectKey, principal, false)) throw new IllegalArgumentException("OBJECT_UNAVAILABLE");
                }
                ItemStore.Projection attributed = store.projection(objectKey);
                try (var insert = db.prepareStatement("INSERT INTO engine_events (domain,source,event_id,owner_user,owner_session,object_key,action,occurred_ms,digest,payload,profile_allowed,object_version,object_guard) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    insert.setString(1, domain);
                    insert.setString(2, source);
                    insert.setString(3, eventId);
                    insert.setString(4, input.has("userId") ? input.path("userId").asText() : null);
                    insert.setString(5, input.has("sessionId") ? input.path("sessionId").asText() : null);
                    insert.setString(6, objectKey);
                    insert.setString(7, input.path("action").asText());
                    insert.setLong(8, time);
                    insert.setString(9, Contract.digest(input));
                    insert.setString(10, Contract.JSON.writeValueAsString(input));
                    insert.setBoolean(11, user != null && user.path("allowBehaviorProfile").asBoolean());
                    insert.setLong(12, attributed == null ? 0 : attributed.item().path("version").asLong());
                    insert.setString(13, attributed != null && attributed.ready() ? attributed.guard() : null);
                    insert.executeUpdate();
                }
                return new ItemStore.Outcome("applied", "OK");
            } catch (IllegalArgumentException error) {
                return rejected(error);
            }
        }
    }

    private boolean sameOwner(JsonNode original, JsonNode undo) {
        if (original.has("userId")) return undo.has("userId") && original.path("userId").asText().equals(undo.path("userId").asText());
        return !undo.has("userId") && original.path("sessionId").asText().equals(undo.path("sessionId").asText());
    }

    public List<EventFact> recentEvents(String domain, String userId) throws SQLException, IOException {
        synchronized (store) {
            long from = clock.millis() - policy.historyDays() * 86400000L;
            List<EventFact> events = new ArrayList<>();
            try (var query = db.prepareStatement("SELECT payload,profile_allowed,object_version,object_guard FROM engine_events WHERE domain=? AND owner_user=? AND occurred_ms>=? AND occurred_ms<=? ORDER BY occurred_ms,source,event_id LIMIT ?")) {
                query.setString(1, domain);
                query.setString(2, userId);
                query.setLong(3, from);
                query.setLong(4, clock.millis());
                query.setInt(5, policy.maxHistoryEvents() + 1);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) events.add(new EventFact(Contract.JSON.readTree(rows.getString(1)), rows.getBoolean(2), rows.getLong(3), rows.getString(4)));
                }
            }
            if (events.size() > policy.maxHistoryEvents()) throw new SearchService.Failure(503, "PROFILE_WINDOW_LIMIT");
            return List.copyOf(events);
        }
    }

    private ItemStore.Outcome rejected(IllegalArgumentException error) {
        String code = error.getMessage();
        if (code == null || !code.matches("[A-Z_]{1,64}")) code = "INVALID_INPUT";
        return new ItemStore.Outcome("rejected", code);
    }
}
