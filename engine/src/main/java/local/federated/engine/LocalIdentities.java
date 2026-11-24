package local.federated.engine;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.io.*;

/** A private, operator-maintained token hash map. Reload on every request, including pagination. */
public final class LocalIdentities {
    public record Identity(String domain,ItemStore.Principal principal) {
        String fingerprint(){return domain+":"+principal.user()+":"+new TreeSet<>(principal.groups());}
    }
    private final Path file;
    public LocalIdentities(Path file){this.file=file;}
    public Identity authenticate(String authorization)throws IOException {
        if(authorization==null||!authorization.matches("Bearer [A-Za-z0-9_-]{32,256}"))return null;
        if(Files.size(file)>1024*1024)throw new IOException("INVALID_AUTH_CONFIG");
        var config=Contract.JSON.readTree(Files.readAllBytes(file));
        if(!config.path("principals").isArray()||config.path("principals").size()>1000)throw new IOException("INVALID_AUTH_CONFIG");
        String supplied;
        try{supplied=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(authorization.substring(7).getBytes(StandardCharsets.US_ASCII)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        Identity selected=null;Set<String> tokens=new HashSet<>();
        for(var entry:config.path("principals")) {
            String hash=entry.path("tokenSha256").asText(),domain=entry.path("domain").asText(),user=entry.path("userId").asText();
            if(!hash.matches("[a-f0-9]{64}")||!tokens.add(hash)||!domain.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")||!user.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")||!entry.path("groups").isArray()||!entry.path("active").isBoolean())throw new IOException("INVALID_AUTH_CONFIG");
            Set<String> groups=new HashSet<>();for(var group:entry.path("groups")){if(!group.isTextual()||!group.asText().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))throw new IOException("INVALID_AUTH_CONFIG");groups.add(group.asText());}
            if(MessageDigest.isEqual(supplied.getBytes(StandardCharsets.US_ASCII),hash.getBytes(StandardCharsets.US_ASCII))&&entry.path("active").asBoolean())selected=new Identity(domain,new ItemStore.Principal(user,Set.copyOf(groups)));
        }
        return selected;
    }
}
