package local.federated.engine;

import com.fasterxml.jackson.databind.node.ArrayNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;
import javax.xml.parsers.SAXParserFactory;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Invoked only against a staged byte snapshot, in a bounded separate JVM. */
public final class ParserWorker {
    private static final int FILE_LIMIT=10*1024*1024;
    private final ArrayNode parts=Contract.JSON.createArrayNode();
    private int chars;
    private void add(String text, String locator, int position) {
        if(text.isBlank()) return;
        int count=text.codePointCount(0,text.length());
        chars+=count;
        if(chars>10*1024*1024) throw new IllegalArgumentException("TEXT_LIMIT");
        for(int start=0;start<count;start+=20000) {
            int end=Math.min(start+20000,count);
            var part=Contract.JSON.createObjectNode().put("text",text.substring(text.offsetByCodePoints(0,start),text.offsetByCodePoints(0,end)));
            var location=part.putObject("location").put("charStart",start).put("charEnd",end);
            if(!locator.isBlank()) location.put(locator,position);
            parts.add(part);
        }
    }
    ArrayNode parse(Path path,String media) throws Exception {
        if(Files.size(path)>FILE_LIMIT) throw new IllegalArgumentException("FILE_TOO_LARGE");
        byte[] bytes=Files.readAllBytes(path);
        switch(media) {
            case "text/plain", "text/markdown" -> {
                if(bytes.length>=2&&bytes[0]=='M'&&bytes[1]=='Z'||bytes.length>=2&&bytes[0]=='P'&&bytes[1]=='K'
                    ||bytes.length>=2&&bytes[0]=='#'&&bytes[1]=='!'||bytes.length>=4&&bytes[0]==0x7f&&bytes[1]=='E'&&bytes[2]=='L'&&bytes[3]=='F'
                    ||bytes.length>=4&&new String(bytes,0,4,StandardCharsets.US_ASCII).equals("%PDF"))
                    throw new IllegalArgumentException("FORMAT_MISMATCH");
                String text;
                try {text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
                catch(CharacterCodingException e){throw new IllegalArgumentException("INVALID_ENCODING");}
                if(text.startsWith("\uFEFF")) text=text.substring(1);
                if(text.indexOf('\0')>=0) throw new IllegalArgumentException("BINARY_TEXT");
                add(text,"",0);
            }
            case "application/pdf" -> {
                if(bytes.length<5||!new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-")) throw new IllegalArgumentException("FORMAT_MISMATCH");
                try(var pdf=Loader.loadPDF(bytes)) {
                    if(pdf.isEncrypted()) throw new IllegalArgumentException("ENCRYPTED_PDF");
                    if(pdf.getNumberOfPages()>100) throw new IllegalArgumentException("PAGE_LIMIT");
                    if(!pdf.getCurrentAccessPermission().canExtractContent()) throw new IllegalArgumentException("EXTRACTION_FORBIDDEN");
                    var strip=new PDFTextStripper();
                    for(int page=1;page<=pdf.getNumberOfPages();page++) {strip.setStartPage(page);strip.setEndPage(page);add(strip.getText(pdf),"page",page);}
                    if(parts.isEmpty()) throw new IllegalArgumentException("NEEDS_OCR");
                } catch(org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e){throw new IllegalArgumentException("ENCRYPTED_PDF");}
            }
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> docx(bytes);
            case "text/html" -> html(bytes);
            default -> throw new IllegalArgumentException("UNSUPPORTED_FORMAT");
        }
        if(parts.isEmpty()) throw new IllegalArgumentException("EMPTY_TEXT");
        return parts;
    }
    private void html(byte[] bytes) throws Exception {
        String text;
        try {text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException e){throw new IllegalArgumentException("INVALID_ENCODING");}
        if(text.indexOf('\0')>=0||text.startsWith("MZ")||text.startsWith("PK")||text.startsWith("%PDF"))
            throw new IllegalArgumentException("FORMAT_MISMATCH");
        // Parse only bytes; jsoup never fetches referenced resources through this API.
        var document=org.jsoup.Jsoup.parse(text);
        document.select("script,style,noscript,template,iframe,object,embed,nav,footer,form,[hidden],[aria-hidden=true]").remove();
        for(var element:document.select("[style]")) {
            String style=element.attr("style").toLowerCase(Locale.ROOT).replaceAll("\\s+","");
            if(style.contains("display:none")||style.contains("visibility:hidden"))element.remove();
        }
        int paragraph=0;
        if(!document.title().isBlank())add(document.title(),"paragraph",++paragraph);
        var body=new StringBuilder();
        document.body().traverse(new org.jsoup.select.NodeVisitor(){
            @Override public void head(org.jsoup.nodes.Node node,int depth) {
                if(node instanceof org.jsoup.nodes.Element element&&(element.isBlock()||element.tagName().equals("br")))body.append('\n');
                if(node instanceof org.jsoup.nodes.TextNode textNode)body.append(textNode.getWholeText());
            }
            @Override public void tail(org.jsoup.nodes.Node node,int depth) {
                if(node instanceof org.jsoup.nodes.Element element&&element.isBlock())body.append('\n');
            }
        });
        for(String line:body.toString().split("\\R"))if(!line.isBlank())add(line.strip(),"paragraph",++paragraph);
    }
    static SAXParserFactory xmlFactory() throws Exception {
        var f=SAXParserFactory.newInstance();f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        f.setFeature("http://xml.org/sax/features/external-general-entities",false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false);
        return f;
    }
    private void docx(byte[] bytes) throws Exception {
        byte[] document=null;Set<String> names=new HashSet<>();long total=0;int entries=0;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while((e=zip.getNextEntry())!=null) {
                String name=e.getName();
                if(++entries>2000) throw new IllegalArgumentException("ZIP_ENTRY_LIMIT");
                if(!names.add(name)||name.contains("\\")||name.startsWith("/")||name.contains(":")||Arrays.asList(name.split("/")).contains(".."))
                    throw new IllegalArgumentException("UNSAFE_ZIP_PATH");
                if(name.toLowerCase(Locale.ROOT).contains("vbaproject")||name.startsWith("word/embeddings/")) throw new IllegalArgumentException("ACTIVE_CONTENT");
                var out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;
                while((n=zip.read(buf))!=-1) {
                    total+=n;if(total>50L*1024*1024) throw new IllegalArgumentException("ZIP_SIZE_LIMIT");
                    if(name.equals("word/document.xml")||name.endsWith(".rels")) out.write(buf,0,n);
                }
                if(name.equals("word/document.xml")) document=out.toByteArray();
                if(name.endsWith(".rels")) {
                    xmlFactory().newSAXParser().parse(new ByteArrayInputStream(out.toByteArray()),new DefaultHandler(){
                        @Override public void startElement(String uri,String local,String q,Attributes a)throws SAXException {
                            if("External".equalsIgnoreCase(a.getValue("TargetMode"))) throw new SAXException("EXTERNAL_RELATIONSHIP");
                        }
                    });
                }
            }
        }
        if(document==null||!names.contains("[Content_Types].xml")||!names.contains("_rels/.rels")) throw new IllegalArgumentException("INVALID_DOCX");
        var handler=new DefaultHandler(){
            final StringBuilder paragraph=new StringBuilder();boolean inText;int index;
            @Override public void startElement(String uri,String name,String q,Attributes a) {
                if(!"http://schemas.openxmlformats.org/wordprocessingml/2006/main".equals(uri)) return;
                if(name.equals("p")){paragraph.setLength(0);index++;}
                if(name.equals("t"))inText=true;
                if(name.equals("tab"))paragraph.append('\t');if(name.equals("br"))paragraph.append('\n');
            }
            @Override public void characters(char[] c,int start,int length){if(inText)paragraph.append(c,start,length);}
            @Override public void endElement(String uri,String name,String q){
                if(!"http://schemas.openxmlformats.org/wordprocessingml/2006/main".equals(uri))return;
                if(name.equals("t"))inText=false;
                if(name.equals("p"))add(paragraph.toString(),"paragraph",index);
            }
        };
        xmlFactory().newSAXParser().parse(new ByteArrayInputStream(document),handler);
    }
    public static void main(String[] args) throws Exception {
        var result=Contract.JSON.createObjectNode();
        try {result.put("state","ready");result.set("parts",new ParserWorker().parse(Path.of(args[0]),args[1]));}
        catch(Exception e) {
            String code=e instanceof IllegalArgumentException ? e.getMessage() : "PARSE_FAILED";
            if(code==null||!code.matches("[A-Z_]{1,64}")) code="PARSE_FAILED";
            result.removeAll();result.put("state",code.equals("NEEDS_OCR")?"needs_ocr":code.equals("PARSE_FAILED")?"failed":"rejected").put("code",code);
        }
        Files.write(Path.of(args[2]),Contract.JSON.writeValueAsBytes(result));
    }
}
