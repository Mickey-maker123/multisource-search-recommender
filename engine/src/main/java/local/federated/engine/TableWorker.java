package local.federated.engine;

import com.fasterxml.jackson.databind.node.*;
import org.apache.commons.csv.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Bounded child-JVM reader of plain CSV or a single, regular XLSX worksheet. */
public final class TableWorker {
    private static final int ROWS=10000, COLS=64, TEXT=4096;
    private final ObjectNode result=Contract.JSON.createObjectNode();
    private final ArrayNode rows=result.putArray("rows");
    private List<String> headers;
    static ObjectNode snapshot(byte[] bytes,String format,Path work)throws Exception {
        Files.createDirectories(work);Path input=Files.createTempFile(work,"table-","."+format),output=Files.createTempFile(work,"table-",".json");
        Process process=null;
        try {
            if(bytes.length>10*1024*1024)throw new IllegalArgumentException("FILE_TOO_LARGE");
            Files.write(input,bytes);
            String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
            process=new ProcessBuilder(executable,"-Xmx256m","-cp",System.getProperty("java.class.path"),TableWorker.class.getName(),input.toString(),format,output.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if(!process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalArgumentException("TABLE_PARSE_TIMEOUT");
            if(process.exitValue()!=0||Files.size(output)>64L*1024*1024)throw new IllegalArgumentException("TABLE_PROCESS_FAILED");
            var result=(ObjectNode)Contract.JSON.readTree(Files.readAllBytes(output));
            if(!result.path("state").asText().equals("ready"))throw new IllegalArgumentException(result.path("code").asText("TABLE_PROCESS_FAILED"));
            return result;
        }finally {
            if(process!=null&&process.isAlive()){process.destroyForcibly();process.waitFor();}
            Files.deleteIfExists(input);Files.deleteIfExists(output);
        }
    }
    private void header(List<String> values) {
        if(values.isEmpty()||values.size()>COLS||values.stream().anyMatch(v->v.isBlank()||v.length()>128)||new HashSet<>(values).size()!=values.size())
            throw new IllegalArgumentException("INVALID_TABLE_HEADER");
        headers=List.copyOf(values);result.set("headers",Contract.JSON.valueToTree(headers));
    }
    private void row(int number,List<String> values,List<String> kinds,String error) {
        if(rows.size()>=ROWS)throw new IllegalArgumentException("TABLE_ROW_LIMIT");
        var row=rows.addObject().put("row",number);
        if(values.size()!=headers.size())error="COLUMN_COUNT_MISMATCH";
        if(values.stream().anyMatch(v->v.length()>TEXT))error="CELL_TEXT_LIMIT";
        if(error!=null){row.put("code",error);return;}
        row.put("code","OK");var cells=row.putObject("values");var types=row.putObject("cellTypes");
        for(int i=0;i<headers.size();i++){cells.put(headers.get(i),values.get(i));types.put(headers.get(i),kinds.get(i));}
    }
    ObjectNode parse(Path path,String format)throws Exception {
        if(Files.size(path)>10*1024*1024)throw new IllegalArgumentException("FILE_TOO_LARGE");
        if(format.equals("csv"))csv(path);else if(format.equals("xlsx"))xlsx(path);else throw new IllegalArgumentException("UNSUPPORTED_FORMAT");
        if(headers==null)throw new IllegalArgumentException("INVALID_TABLE_HEADER");
        result.put("state","ready");return result;
    }
    private void csv(Path path)throws Exception {
        var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try(var reader=new PushbackReader(new InputStreamReader(Files.newInputStream(path),decoder),1)) {
            int first=reader.read();if(first!=-1&&first!='\uFEFF')reader.unread(first);
            try(var csv=CSVFormat.RFC4180.builder().setIgnoreEmptyLines(false).get().parse(reader)) {
                for(var record:csv) {
                    List<String> values=record.toList();
                    if(headers==null)header(values);else row((int)record.getRecordNumber(),values,Collections.nCopies(values.size(),"string"),null);
                }
            }
        }
    }
    private void xlsx(Path path)throws Exception {
        // Reject active/embedded parts before a workbook is loaded. ZIP expansion is bounded.
        long expanded=0;int count=0;Set<String> names=new HashSet<>();
        try(var zip=new ZipInputStream(Files.newInputStream(path))) {
            ZipEntry e;byte[] buffer=new byte[8192];
            while((e=zip.getNextEntry())!=null) {
                String name=e.getName().toLowerCase(Locale.ROOT);
                if(++count>2000||!names.add(name)||name.startsWith("/")||name.contains("..")||name.contains("\\"))throw new IllegalArgumentException("UNSAFE_XLSX_ARCHIVE");
                if(name.contains("vbaproject")||name.contains("/embeddings/")||name.contains("/externallinks/")||name.endsWith("connections.xml"))
                    throw new IllegalArgumentException("ACTIVE_XLSX_CONTENT");
                var relation=new ByteArrayOutputStream();
                int n;while((n=zip.read(buffer))!=-1){expanded+=n;if(expanded>50L*1024*1024)throw new IllegalArgumentException("ZIP_SIZE_LIMIT");if(name.endsWith(".rels"))relation.write(buffer,0,n);}
                if(name.endsWith(".rels"))ParserWorker.xmlFactory().newSAXParser().parse(new ByteArrayInputStream(relation.toByteArray()),new org.xml.sax.helpers.DefaultHandler(){
                    @Override public void startElement(String uri,String local,String q,org.xml.sax.Attributes a){
                        if("External".equalsIgnoreCase(a.getValue("TargetMode")))throw new IllegalArgumentException("EXTERNAL_RELATIONSHIP");
                    }
                });
            }
        }
        try(var book=new XSSFWorkbook(path.toFile())) {
            if(book.getNumberOfSheets()!=1||book.isSheetHidden(0)||book.isSheetVeryHidden(0))throw new IllegalArgumentException("SINGLE_VISIBLE_SHEET_REQUIRED");
            var sheet=book.getSheetAt(0);
            if(sheet.getNumMergedRegions()>0)throw new IllegalArgumentException("MERGED_TABLE_UNSUPPORTED");
            if(sheet.getLastRowNum()>ROWS)throw new IllegalArgumentException("TABLE_ROW_LIMIT");
            for(int r=0;r<=sheet.getLastRowNum();r++) {
                var source=sheet.getRow(r);int width=headers==null?(source==null?0:source.getLastCellNum()):Math.max(headers.size(),source==null?0:source.getLastCellNum());
                if(width>COLS)throw new IllegalArgumentException("TABLE_COLUMN_LIMIT");
                List<String> values=new ArrayList<>(),kinds=new ArrayList<>();String error=null;
                if(source!=null&&source.getZeroHeight())error="HIDDEN_ROW_UNSUPPORTED";
                for(int c=0;c<width;c++) {
                    if(sheet.isColumnHidden(c))throw new IllegalArgumentException("HIDDEN_COLUMN_UNSUPPORTED");
                    var cell=source==null?null:source.getCell(c);String value="",kind="string";
                    if(cell!=null)switch(cell.getCellType()) {
                        case STRING -> value=cell.getStringCellValue();
                        case NUMERIC -> {kind="decimal";value=new java.math.BigDecimal(cell.getRawValue()).toPlainString();if(DateUtil.isCellDateFormatted(cell)){kind="date";var time=cell.getLocalDateTimeCellValue();value=time.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)?time.toLocalDate().toString():time.toString();}}
                        case BOOLEAN -> {kind="boolean";value=Boolean.toString(cell.getBooleanCellValue());}
                        case FORMULA -> error="FORMULA_UNSUPPORTED";
                        case ERROR -> error="CELL_ERROR";
                        default -> {}
                    }
                    values.add(value);kinds.add(kind);
                }
                if(r==0){if(error!=null||kinds.stream().anyMatch(k->!k.equals("string")))throw new IllegalArgumentException("INVALID_TABLE_HEADER");header(values);}
                else row(r+1,values,kinds,error);
            }
        }
    }
    public static void main(String[] args)throws Exception {
        ObjectNode out;
        try{out=new TableWorker().parse(Path.of(args[0]),args[1]);}
        catch(Exception e){String code=e instanceof IllegalArgumentException?e.getMessage():"TABLE_PARSE_FAILED";
            if(code==null||!code.matches("[A-Z_]{1,64}"))code="TABLE_PARSE_FAILED";
            out=Contract.JSON.createObjectNode().put("state","failed").put("code",code);}
        Files.write(Path.of(args[2]),Contract.JSON.writeValueAsBytes(out));
    }
}
