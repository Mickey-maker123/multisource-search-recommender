package local.federated.engine;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.io.*;
import java.util.zip.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.encryption.*;

class FileImporterTest {
    @TempDir Path temp;
    private ObjectNode file(String id,String media)throws Exception {
        return IngestionTest.item(id,1).put("type","document").put("mediaType",media).put("text","");
    }
    @Test void actualFourFormatsHaveTextAndLocations()throws Exception {
        try(var store=new ItemStore(temp.resolve("db"))) {
            var importer=new FileImporter(store,Path.of("../fixtures/d1/files"),temp.resolve("work"),IngestionTest.ALICE);
            for(var spec:new String[][]{{"txt","text/plain"},{"md","text/markdown"},{"pdf","application/pdf"},{"docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document"}}) {
                store.apply(file(spec[0],spec[1]));String key="demo:posts:"+spec[0];
                assertEquals("ready",importer.parse(key,"sample."+spec[0]));
                var part=store.status(key).path("derived").get(0);
                assertFalse(part.path("text").asText().isBlank());assertTrue(part.path("location").has("uri"));
                if(spec[0].equals("pdf")){assertEquals(1,part.path("location").path("page").asInt());assertTrue(part.path("text").asText().contains("Synthetic PDF"));}
                if(spec[0].equals("docx")){assertEquals(1,part.path("location").path("paragraph").asInt());assertTrue(part.path("text").asText().contains("跨源资料来源定位"));}
            }
        }
    }
    @Test void pathsEncodingSizeEmptyAndChangedFileVersion()throws Exception {
        Path root=temp.resolve("input");Files.createDirectories(root);
        try(var store=new ItemStore(temp.resolve("db"))) {
            var importer=new FileImporter(store,root,temp.resolve("work"),IngestionTest.ALICE);
            for(var spec:new String[][]{{"bad","bad.txt"},{"empty","empty.txt"},{"exe","payload.txt"},{"script","script.txt"},{"big","big.txt"},{"escape","../outside.txt"},{"unsupported","sample.exe"}}) {
                store.apply(file(spec[0],"text/plain"));
                if(spec[0].equals("bad"))Files.write(root.resolve(spec[1]),new byte[]{(byte)0xff});
                if(spec[0].equals("empty"))Files.writeString(root.resolve(spec[1]),"");
                if(spec[0].equals("exe"))Files.writeString(root.resolve(spec[1]),"MZpayload");
                if(spec[0].equals("script"))Files.writeString(root.resolve(spec[1]),"#!/bin/sh\necho sample");
                if(spec[0].equals("big"))Files.write(root.resolve(spec[1]),new byte[10*1024*1024+1]);
                if(spec[0].equals("unsupported"))Files.writeString(root.resolve(spec[1]),"unknown");
                assertEquals("rejected",importer.parse("demo:posts:"+spec[0],spec[1]));
                assertFalse(store.status("demo:posts:"+spec[0]).has("derived"));
            }
            Files.writeString(root.resolve("same.txt"),"first");store.apply(file("same","text/plain"));
            assertEquals("ready",importer.parse("demo:posts:same","same.txt"));Files.writeString(root.resolve("same.txt"),"changed");
            assertEquals("rejected",importer.parse("demo:posts:same","same.txt"));assertEquals("FILE_VERSION_CONFLICT",store.status("demo:posts:same").path("code").asText());
        }
    }
    @Test void blankEncryptedAndPageLimitPdf()throws Exception {
        Path blank=temp.resolve("blank.pdf"), encrypted=temp.resolve("encrypted.pdf"), many=temp.resolve("many.pdf");
        try(var pdf=new PDDocument()){pdf.addPage(new PDPage());pdf.save(blank.toFile());
            pdf.protect(new StandardProtectionPolicy("owner-secret","user-secret",new AccessPermission()));pdf.save(encrypted.toFile());}
        try(var pdf=new PDDocument()){for(int i=0;i<101;i++)pdf.addPage(new PDPage());pdf.save(many.toFile());}
        var worker=new ParserWorker();
        assertEquals("NEEDS_OCR",assertThrows(IllegalArgumentException.class,()->worker.parse(blank,"application/pdf")).getMessage());
        assertEquals("ENCRYPTED_PDF",assertThrows(IllegalArgumentException.class,()->worker.parse(encrypted,"application/pdf")).getMessage());
        assertEquals("PAGE_LIMIT",assertThrows(IllegalArgumentException.class,()->worker.parse(many,"application/pdf")).getMessage());
    }
    private Path zip(String name,String document,String extra,String content)throws Exception {
        Path file=temp.resolve(name);
        try(var zip=new ZipOutputStream(Files.newOutputStream(file))) {
            for(var pair:new String[][]{{"[Content_Types].xml","<Types/>"},{"_rels/.rels","<Relationships/>"},{"word/document.xml",document},{extra,content}}){
                zip.putNextEntry(new ZipEntry(pair[0]));zip.write(pair[1].getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
            }
        }return file;
    }
    @Test void docxTraversalExternalEntitiesAndExternalRelationshipsAreNotParsed()throws Exception {
        String media="application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        var unsafe=zip("unsafe.docx","<document/>","../payload","bad");
        assertEquals("UNSAFE_ZIP_PATH",assertThrows(IllegalArgumentException.class,()->new ParserWorker().parse(unsafe,media)).getMessage());
        var xxe=zip("xxe.docx","<!DOCTYPE d [<!ENTITY x SYSTEM 'file:///private'>]><d>&x;</d>","word/styles.xml","<styles/>");
        assertThrows(Exception.class,()->new ParserWorker().parse(xxe,media));
        var external=zip("external.docx","<document/>","word/_rels/document.xml.rels","<Relationships><Relationship TargetMode='External' Target='https://example.invalid'/></Relationships>");
        assertThrows(Exception.class,()->new ParserWorker().parse(external,media));
    }
    @Test void codepointChunksPreserveWholeText()throws Exception {
        String text="\uD83D\uDE00".repeat(20001);Path path=temp.resolve("large.txt");Files.writeString(path,"\uFEFF"+text);
        var parts=new ParserWorker().parse(path,"text/plain");assertEquals(2,parts.size());
        assertEquals(text,parts.get(0).path("text").asText()+parts.get(1).path("text").asText());
        assertEquals(20000,parts.get(1).path("location").path("charStart").asInt());
    }
    @Test void docxZipInflationAndEntryLimits()throws Exception {
        Path inflated=temp.resolve("inflated.docx"),entries=temp.resolve("entries.docx");
        try(var zip=new ZipOutputStream(Files.newOutputStream(inflated))){
            zip.putNextEntry(new ZipEntry("padding.bin"));byte[] bytes=new byte[8192];
            for(int i=0;i<=6400;i++)zip.write(bytes);zip.closeEntry();
        }
        try(var zip=new ZipOutputStream(Files.newOutputStream(entries))){for(int i=0;i<2001;i++){zip.putNextEntry(new ZipEntry("entry-"+i));zip.closeEntry();}}
        String media="application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        assertEquals("ZIP_SIZE_LIMIT",assertThrows(IllegalArgumentException.class,()->new ParserWorker().parse(inflated,media)).getMessage());
        assertEquals("ZIP_ENTRY_LIMIT",assertThrows(IllegalArgumentException.class,()->new ParserWorker().parse(entries,media)).getMessage());
    }
}
