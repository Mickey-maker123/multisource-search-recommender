package local.federated.engine;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class FileImporter {
    private static final Map<String,String> EXT=Map.of("text/plain",".txt","text/markdown",".md","application/pdf",".pdf","application/vnd.openxmlformats-officedocument.wordprocessingml.document",".docx");
    private final ItemStore store;
    private final Path root,work;
    private final ItemStore.Principal principal;
    public FileImporter(ItemStore store,Path root,Path work,ItemStore.Principal principal)throws IOException {
        this.store=store;this.root=root.toRealPath();this.work=work;this.principal=principal;
        Files.createDirectories(work);
    }
    public String parse(String key,String relative)throws Exception {
        return parse(key,relative,null);
    }
    public String parse(String key,String relative,String expectedDigest)throws Exception {
        var ticket=store.begin(key,principal);
        Path staged=null,result=null;
        Process child=null;
        try {
            JsonNode item=store.item(key);String media=item.path("mediaType").asText();
            if(!Set.of("document","attachment").contains(item.path("type").asText()))throw new IllegalArgumentException("NOT_A_FILE");
            Path rel=Path.of(relative);
            if(rel.isAbsolute()||rel.toString().contains(":")||rel.startsWith(".."))throw new IllegalArgumentException("UNSAFE_FILE_PATH");
            Path file=root.resolve(rel).normalize();
            if(!file.startsWith(root))throw new IllegalArgumentException("UNSAFE_FILE_PATH");
            for(Path p=file;!p.equals(root);p=p.getParent())
                if(Files.isSymbolicLink(p))throw new IllegalArgumentException("UNSAFE_FILE_PATH");
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!file.toRealPath().startsWith(root))throw new IllegalArgumentException("UNSAFE_FILE_PATH");
            String extension=EXT.get(media);
            String filename=file.getFileName().toString().toLowerCase(Locale.ROOT);
            boolean html=media.equals("text/html")&&(filename.endsWith(".html")||filename.endsWith(".htm"));
            if(!html&&(extension==null||!filename.endsWith(extension)))throw new IllegalArgumentException("UNSUPPORTED_FORMAT");
            // Snapshot only approved, bounded bytes; parser never follows the source path or any URI.
            byte[] bytes;
            try(var in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){bytes=in.readNBytes(10*1024*1024+1);}
            if(bytes.length>10*1024*1024)throw new IllegalArgumentException("FILE_TOO_LARGE");
            if(expectedDigest!=null&&!expectedDigest.equals(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))))
                throw new IllegalArgumentException("SOURCE_CHANGED");
            store.bindFile(ticket,bytes);
            staged=Files.createTempFile(work,"parse-",".input");result=Files.createTempFile(work,"parse-",".json");
            Files.write(staged,bytes);
            String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
            child=new ProcessBuilder(java,"-Xmx256m","-cp",System.getProperty("java.class.path"),ParserWorker.class.getName(),staged.toString(),media,result.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if(!child.waitFor(30,TimeUnit.SECONDS)) {child.destroyForcibly();child.waitFor();throw new IllegalArgumentException("PARSE_TIMEOUT");}
            if(child.exitValue()!=0||Files.size(result)>64L*1024*1024)throw new IllegalArgumentException("PARSER_PROCESS_FAILED");
            var parsed=Contract.JSON.readTree(Files.readAllBytes(result));
            String state=parsed.path("state").asText();
            if(!Set.of("ready","failed","rejected","needs_ocr").contains(state))throw new IllegalArgumentException("PARSER_PROCESS_FAILED");
            var parts=parsed.get("parts");
            if(parts!=null)for(var part:parts) ((com.fasterxml.jackson.databind.node.ObjectNode)part.path("location")).put("uri",item.path("location").path("uri").asText());
            return store.finish(ticket,principal,state,parsed.path("code").asText("OK"),parts)?state:"superseded";
        }catch(Exception e){
            String code=e instanceof IllegalArgumentException&&e.getMessage()!=null&&e.getMessage().matches("[A-Z_]{1,64}")?e.getMessage():"PARSE_FAILED";
            store.finish(ticket,principal,code.equals("PARSE_FAILED")||code.equals("PARSE_TIMEOUT")||code.equals("PARSER_PROCESS_FAILED")?"failed":"rejected",code,null);
            if(e instanceof InterruptedException)Thread.currentThread().interrupt();
            return store.status(key).path("state").asText();
        }finally {
            if(child!=null&&child.isAlive()){child.destroyForcibly();child.waitFor();}
            if(staged!=null)Files.deleteIfExists(staged);if(result!=null)Files.deleteIfExists(result);
        }
    }
}
