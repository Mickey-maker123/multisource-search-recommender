package local.federated.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class TableMappingTest {
    @TempDir Path temp;
    private com.fasterxml.jackson.databind.node.ObjectNode config(String id,String title,String phone,String amount) {
        var c=Contract.JSON.createObjectNode().put("schemaVersion","1.0").put("idColumn",id).put("titleColumn",title);
        var fields=c.putObject("fields");fields.putObject("phone").put("column",phone).put("type","string");
        fields.putObject("amount").put("column",amount).put("type","decimal").put("required",true);
        return c;
    }
    @Test void csvQuotedMultilineAndTwoRenamedSourcesKeepExactValues()throws Exception {
        Path a=temp.resolve("a.csv"),b=temp.resolve("b.csv");
        Files.writeString(a,"编号,名称,电话,金额\r\n001,\"部署,方案\n第二行\",01001234,12.50\r\n");
        Files.writeString(b,"id,title,phone,total\r\n001,\"部署,方案\n第二行\",01001234,12.50\r\n");
        var left=new TableMapping(config("编号","名称","电话","金额")).map(new TableWorker().parse(a,"csv"));
        var right=new TableMapping(config("id","title","phone","total")).map(new TableWorker().parse(b,"csv"));
        assertEquals(left,right);assertEquals(0,left.path("errors").asInt());var row=left.path("rows").get(0);
        assertEquals("001",row.path("nativeId").asText());assertEquals("01001234",row.path("typedFields").path("phone").path("value").asText());
        assertFalse(row.path("text").asText().contains("01001234"));assertTrue(row.path("title").asText().contains("\n"));
    }
    @Test void rowFailuresRemainVisibleAndDuplicateIdsNeverPartiallyWin()throws Exception {
        Path file=temp.resolve("bad.csv");Files.writeString(file,"id,title,phone,total\n001,one,010,5\n001,two,020,6\n002,three,030,no-number\n003,four,040\n");
        var mapped=new TableMapping(config("id","title","phone","total")).map(new TableWorker().parse(file,"csv"));
        assertEquals(4,mapped.path("errors").asInt());assertEquals("DUPLICATE_ROW_ID",mapped.path("rows").get(0).path("code").asText());
        assertEquals("DUPLICATE_ROW_ID",mapped.path("rows").get(1).path("code").asText());
        assertEquals("COLUMN_COUNT_MISMATCH",mapped.path("rows").get(3).path("code").asText());
        assertFalse(mapped.path("rows").get(2).has("typedFields"));
    }
    @Test void spreadsheetFormulaAndNumericIdentifiersAreNotGuessed()throws Exception {
        Path file=temp.resolve("a.xlsx");
        try(var book=new XSSFWorkbook()) {
            var sheet=book.createSheet("业务");var header=sheet.createRow(0);String[] names={"id","title","phone","total"};
            for(int i=0;i<names.length;i++)header.createCell(i).setCellValue(names[i]);
            var row=sheet.createRow(1);row.createCell(0).setCellValue("001");row.createCell(1).setCellValue("方案");row.createCell(2).setCellValue("010");row.createCell(3).setCellValue(12.5);
            row=sheet.createRow(2);row.createCell(0).setCellValue(2);row.createCell(1).setCellValue("数值编号");row.createCell(2).setCellValue("020");row.createCell(3).setCellValue(1);
            row=sheet.createRow(3);row.createCell(0).setCellValue("003");row.createCell(1).setCellValue("公式");row.createCell(2).setCellValue("030");row.createCell(3).setCellFormula("1+1");
            try(var out=Files.newOutputStream(file)){book.write(out);}
        }
        var mapped=new TableMapping(config("id","title","phone","total")).map(new TableWorker().parse(file,"xlsx"));
        assertEquals(2,mapped.path("errors").asInt());assertEquals("OK",mapped.path("rows").get(0).path("code").asText());
        assertEquals("STRING_CELL_REQUIRED",mapped.path("rows").get(1).path("code").asText());
        assertEquals("FORMULA_UNSUPPORTED",mapped.path("rows").get(2).path("code").asText());
    }
    @Test void rejectsSensitiveEmbeddingBadDatesAndMissingColumns()throws Exception {
        var config=config("id","title","phone","total");config.withObject("fields").withObject("phone").put("semantic",true);
        assertThrows(IllegalArgumentException.class,()->new TableMapping(config));
        assertThrows(IllegalArgumentException.class,()->TableMapping.value("date","2026-02-30"));
        assertThrows(IllegalArgumentException.class,()->TableMapping.value("boolean","yes"));
        Path file=temp.resolve("a.csv");Files.writeString(file,"id,title\n001,ok\n");
        assertThrows(IllegalArgumentException.class,()->new TableMapping(config("id","title","phone","total")).map(new TableWorker().parse(file,"csv")));
    }
    @Test void activeAndHiddenSpreadsheetContentIsRejectedWithoutEvaluation()throws Exception {
        Path file=temp.resolve("active.xlsx");
        try(var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("xl/_rels/workbook.xml.rels"));
            zip.write("<Relationships><Relationship TargetMode=\"External\" Target=\"http://127.0.0.1:1/private\"/></Relationships>".getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
        }
        assertEquals("EXTERNAL_RELATIONSHIP",assertThrows(IllegalArgumentException.class,()->new TableWorker().parse(file,"xlsx")).getMessage());
        try(var book=new XSSFWorkbook()) {
            var sheet=book.createSheet("rows");sheet.createRow(0).createCell(0).setCellValue("id");sheet.createRow(1).createCell(0).setCellValue("001");sheet.getRow(1).setZeroHeight(true);
            try(var out=Files.newOutputStream(file)){book.write(out);}
            assertEquals("HIDDEN_ROW_UNSUPPORTED",new TableWorker().parse(file,"xlsx").path("rows").get(0).path("code").asText());
            sheet.setColumnHidden(0,true);try(var out=Files.newOutputStream(file)){book.write(out);}
            assertEquals("HIDDEN_COLUMN_UNSUPPORTED",assertThrows(IllegalArgumentException.class,()->new TableWorker().parse(file,"xlsx")).getMessage());
            sheet.setColumnHidden(0,false);sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0,0,0,1));try(var out=Files.newOutputStream(file)){book.write(out);}
            assertEquals("MERGED_TABLE_UNSUPPORTED",assertThrows(IllegalArgumentException.class,()->new TableWorker().parse(file,"xlsx")).getMessage());
        }
    }
}
