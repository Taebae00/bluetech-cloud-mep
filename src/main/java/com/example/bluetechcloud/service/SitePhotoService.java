package com.example.bluetechcloud.service;

import com.example.bluetechcloud.entity.InspectionItemEntity;
import com.example.bluetechcloud.entity.InspectionResultEntity;
import com.example.bluetechcloud.entity.PhotoEntity;
import com.example.bluetechcloud.model.SiteDTO;
import com.example.bluetechcloud.repository.InspectionItemRepo;
import com.example.bluetechcloud.repository.InspectionResultRepo;
import com.example.bluetechcloud.repository.PhotoRepo;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
public class SitePhotoService {

    private final PhotoRepo photoRepo;
    private final InspectionResultRepo resultRepo;
    private final InspectionItemRepo itemRepo;
    private final SiteService siteService;
    private final S3Client s3Client;

    @Value("${cloud.aws.s3.bucket}")
    private String bucket;

    public void downloadZip(Long siteId, HttpServletResponse response) throws IOException {
        List<InspectionResultEntity> results = resultRepo.findBySiteId(siteId);
        writeZip(siteId, results, response);
    }

    public void downloadSelectedZip(Long siteId,
                                    List<Long> itemIds,
                                    List<String> categoryGroups,
                                    List<Long> subItemIds,
                                    HttpServletResponse response) throws IOException {

        List<InspectionResultEntity> results = resultRepo.findBySiteId(siteId);

        Set<String> selectedKeys = new HashSet<>();

        for (int i = 0; i < itemIds.size(); i++) {
            Long itemId = itemIds.get(i);
            String categoryGroup = categoryGroups.get(i);
            Long subItemId = normalizeSubItemId(subItemIds.get(i));

            selectedKeys.add(itemId + "::" + categoryGroup + "::" + subItemId);
        }

        List<InspectionResultEntity> filteredResults = results.stream()
                .filter(result -> {
                    Long subItemId = normalizeSubItemId(result.getSubItemId());
                    String key = result.getItemId() + "::" + result.getCategoryGroup() + "::" + subItemId;
                    return selectedKeys.contains(key);
                })
                .toList();

        writeZip(siteId, filteredResults, response);
    }

    private Long normalizeSubItemId(Long subItemId) {
        return (subItemId == null || subItemId == 0) ? 0L : subItemId;
    }

    private void writeZip(Long siteId,
                          List<InspectionResultEntity> targetResults,
                          HttpServletResponse response) throws IOException {

        SiteDTO site = siteService.getSite(siteId);
        String zipName = safeZipFileName(site.getSite_name() + "_현장사진.zip");

        response.setContentType("application/zip");
        response.setHeader(
                "Content-Disposition",
                "attachment; filename*=UTF-8''" +
                        URLEncoder.encode(zipName, StandardCharsets.UTF_8).replaceAll("\\+", "%20")
        );

        Map<Long, InspectionResultEntity> resultMap = new HashMap<>();

        for (InspectionResultEntity result : targetResults) {
            if (result.getId() != null) {
                resultMap.put(result.getId(), result);
            }
        }

        Set<Long> resultIds = resultMap.keySet();

        List<PhotoEntity> allPhotos = resultIds.isEmpty()
                ? Collections.emptyList()
                : photoRepo.findByResultIdIn(resultIds);

        Map<Long, InspectionItemEntity> itemCache = new HashMap<>();
        Set<String> createdDirs = new HashSet<>();
        Map<Long, Integer> photoSeqMap = new HashMap<>();

        try (ZipOutputStream zos = new ZipOutputStream(response.getOutputStream())) {

            for (InspectionResultEntity result : targetResults) {

                InspectionItemEntity item = itemCache.computeIfAbsent(
                        result.getItemId(),
                        id -> itemRepo.findById(id)
                                .orElseThrow(() -> new IllegalArgumentException("점검항목 없음: " + id))
                );

                String locationName = extractLocationName(result.getCategoryGroup());
                String resultDir = buildResultDir(item, result);

                String itemContent = safeZipEntryName(item.getContent());
                String itemLabel = item.getOrderNo() + "." + itemContent;

                String memo = result.getMemo() == null ? "" : result.getMemo().trim();
                String resultValue = result.getResult() == null ? "" : result.getResult().trim();

                boolean isNotApplicable = "해당사항없음".equals(resultValue);
                boolean isNotWritten = "미작성".equals(resultValue);

                String categoryLabel = item.getCategoryOrder() + "." + safeZipEntryName(item.getCategory());

                if (isNotApplicable) {
                    String naDir = resultDir + "/해당사항없음/";

                    if (createdDirs.add(naDir)) {
                        zos.putNextEntry(new ZipEntry(naDir));
                        zos.closeEntry();
                    }

                    continue;
                }

                if (isNotWritten) continue;
                if (memo.isBlank()) continue;

                if (isAirflowSheet(memo)) {
                    byte[] excelBytes = createAirflowSheetExcel(
                            item.getContent(),
                            locationName,
                            resultValue,
                            memo
                    );

                    String excelFileName = safeZipEntryName(
                            categoryLabel + "_" +
                                    itemLabel + "_" +
                                    locationName + "_풍량측정표.xlsx"
                    );

                    zos.putNextEntry(new ZipEntry(resultDir + "/" + excelFileName));
                    zos.write(excelBytes);
                    zos.closeEntry();

                } else if (isEfficiencySheet(memo)) {
                    byte[] excelBytes = createEfficiencySheetExcel(
                            item.getContent(),
                            locationName,
                            resultValue,
                            memo
                    );

                    String excelFileName = safeZipEntryName(
                            categoryLabel + "_" +
                                    itemLabel + "_" +
                                    locationName + "_효율시트.xlsx"
                    );

                    zos.putNextEntry(new ZipEntry(resultDir + "/" + excelFileName));
                    zos.write(excelBytes);
                    zos.closeEntry();

                } else if (isFanControlSheet(memo)) {
                    byte[] excelBytes = createFanControlSheetExcel(
                            item.getContent(),
                            locationName,
                            resultValue,
                            memo
                    );

                    String excelFileName = safeZipEntryName(
                            categoryLabel + "_" +
                                    itemLabel + "_" +
                                    locationName + "_풍량조절표.xlsx"
                    );

                    zos.putNextEntry(new ZipEntry(resultDir + "/" + excelFileName));
                    zos.write(excelBytes);
                    zos.closeEntry();

                } else {
                    String txtFileName = safeZipEntryName(
                            categoryLabel + "_" +
                                    itemLabel + "_" +
                                    locationName + "_메모.txt"
                    );

                    String textContent = ""
                            + "대주제: " + item.getCategory() + System.lineSeparator()
                            + "세부점검사항: " + item.getContent() + System.lineSeparator()
                            + "위치명: " + locationName + System.lineSeparator()
                            + "점검결과: " + resultValue + System.lineSeparator()
                            + "메모: " + memo + System.lineSeparator();

                    zos.putNextEntry(new ZipEntry(resultDir + "/" + txtFileName));
                    zos.write(textContent.getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                }
            }

            for (PhotoEntity photo : allPhotos) {

                InspectionResultEntity result = resultMap.get(photo.getResultId());
                if (result == null) continue;

                InspectionItemEntity item = itemCache.computeIfAbsent(
                        result.getItemId(),
                        id -> itemRepo.findById(id)
                                .orElseThrow(() -> new IllegalArgumentException("점검항목 없음: " + id))
                );

                String locationName = extractLocationName(result.getCategoryGroup());
                String resultDir = buildResultDir(item, result);

                String itemContent = safeZipEntryName(item.getContent());
                String itemLabel = item.getOrderNo() + "." + itemContent;

                int seq = photoSeqMap.getOrDefault(result.getId(), 0) + 1;
                photoSeqMap.put(result.getId(), seq);

                String fileUrl = photo.getFileUrl();
                String s3Key = extractKeyFromUrl(fileUrl);
                String ext = extractExtension(fileUrl);

                String categoryLabel = item.getCategoryOrder() + "." + safeZipEntryName(item.getCategory());

                String photoFileName = safeZipEntryName(
                        categoryLabel + "_" +
                                itemLabel + "_" +
                                locationName + "_" +
                                seq + ext
                );

                String zipPath = resultDir + "/" + photoFileName;

                GetObjectRequest req = GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(s3Key)
                        .build();

                try (InputStream is = s3Client.getObject(req)) {
                    zos.putNextEntry(new ZipEntry(zipPath));
                    is.transferTo(zos);
                    zos.closeEntry();
                } catch (Exception e) {
                    System.out.println("S3 download fail: " + s3Key);
                    e.printStackTrace();
                }
            }

            zos.finish();
        }
    }

    private String buildCategoryDir(InspectionItemEntity item) {
        int categoryOrder = item.getCategoryOrder();
        if (categoryOrder <= 0) {
            categoryOrder = 999;
        }

        String categoryName = safeZipEntryName(item.getCategory());
        return categoryOrder + "." + categoryName;
    }

    private String buildResultDir(InspectionItemEntity item, InspectionResultEntity result) {
        String categoryDir = buildCategoryDir(item);

        String itemContent = safeZipEntryName(item.getContent());
        String itemLabel = item.getOrderNo() + "." + itemContent;

        String locationName = extractLocationName(result.getCategoryGroup());

        return categoryDir + "/" + itemLabel + "/" + locationName;
    }

    private String extractLocationName(String categoryGroup) {
        if (categoryGroup == null || categoryGroup.isBlank()) {
            return "위치미정";
        }

        int idx = categoryGroup.indexOf("_");
        if (idx < 0 || idx >= categoryGroup.length() - 1) {
            return "위치미정";
        }

        String location = categoryGroup.substring(idx + 1).trim();
        return location.isBlank() ? "위치미정" : safeZipEntryName(location);
    }

    private String extractExtension(String path) {
        String fileName = path.substring(path.lastIndexOf("/") + 1);
        int dotIdx = fileName.lastIndexOf(".");
        if (dotIdx == -1) {
            return ".jpg";
        }
        return fileName.substring(dotIdx);
    }

    private String extractKeyFromUrl(String fileUrl) {
        String marker = ".amazonaws.com/";
        int idx = fileUrl.indexOf(marker);

        if (idx != -1) {
            return fileUrl.substring(idx + marker.length());
        }

        return fileUrl;
    }

    private String safeZipEntryName(String name) {
        if (name == null || name.isBlank()) {
            return "이름없음";
        }

        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private String safeZipFileName(String fileName) {
        return fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private boolean isEfficiencySheet(String memo) {
        return memo != null && memo.contains("\"type\":\"efficiencySheet\"");
    }

    private boolean isAirflowSheet(String memo) {
        return memo != null && memo.contains("\"type\":\"airflowSheet\"");
    }

    private boolean isFanControlSheet(String memo) {
        return memo != null && memo.contains("\"type\":\"fanControlSheet\"");
    }

    private byte[] createEfficiencySheetExcel(String title,
                                              String locationName,
                                              String resultValue,
                                              String memo) {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("효율시트");

            CellStyle titleStyle = createTitleStyle(wb);
            CellStyle headerStyle = createHeaderStyle(wb);
            CellStyle cellStyle = createCellStyle(wb, HorizontalAlignment.CENTER);

            JsonNode root = new ObjectMapper().readTree(memo);
            JsonNode rows = root.get("rows");
            JsonNode summary = root.get("summary");

            int rowIdx = 0;

            Row titleRow = sheet.createRow(rowIdx++);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue(title == null ? "효율시트" : title);
            titleCell.setCellStyle(titleStyle);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 5));

            Row infoRow = sheet.createRow(rowIdx++);
            createExcelCell(infoRow, 0, "위치명", headerStyle);
            createExcelCell(infoRow, 1, locationName, cellStyle);
            createExcelCell(infoRow, 2, "점검결과", headerStyle);
            createExcelCell(infoRow, 3, resultValue, cellStyle);

            rowIdx++;

            Row header = sheet.createRow(rowIdx++);
            String[] headers = {"순번", "고온℃", "저온℃", "유량\nLPM", "열량\nkcal/h", "에너지\n사용량"};

            for (int i = 0; i < headers.length; i++) {
                createExcelCell(header, i, headers[i], headerStyle);
            }

            for (int i = 1; i <= 30; i++) {
                Row row = sheet.createRow(rowIdx++);
                JsonNode r = rows == null ? null : rows.get(String.valueOf(i));

                createExcelCell(row, 0, String.valueOf(i), cellStyle);
                createExcelCell(row, 1, getJsonVal(r, "highTemp"), cellStyle);
                createExcelCell(row, 2, getJsonVal(r, "lowTemp"), cellStyle);
                createExcelCell(row, 3, getJsonVal(r, "flow"), cellStyle);
                createExcelCell(row, 4, getJsonVal(r, "heat"), cellStyle);
                createExcelCell(row, 5, getJsonVal(r, "energy"), cellStyle);
            }

            Row sumRow = sheet.createRow(rowIdx++);
            createExcelCell(sumRow, 0, "합계", headerStyle);
            createExcelCell(sumRow, 1, getJsonVal(summary, "avgHighTemp"), cellStyle);
            createExcelCell(sumRow, 2, getJsonVal(summary, "avgLowTemp"), cellStyle);
            createExcelCell(sumRow, 3, getJsonVal(summary, "totalFlow"), cellStyle);
            createExcelCell(sumRow, 4, getJsonVal(summary, "totalHeat"), cellStyle);
            createExcelCell(sumRow, 5, getJsonVal(summary, "totalEnergy"), cellStyle);

            for (int i = 0; i <= 5; i++) {
                sheet.setColumnWidth(i, 3500);
            }

            sheet.setDisplayGridlines(false);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            wb.write(baos);
            return baos.toByteArray();

        } catch (Exception e) {
            throw new RuntimeException("효율시트 엑셀 생성 실패", e);
        }
    }

    private byte[] createAirflowSheetExcel(String title,
                                           String locationName,
                                           String resultValue,
                                           String memo) {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("풍량측정표");

            CellStyle titleStyle = createTitleStyle(wb);
            CellStyle headerStyle = createHeaderStyle(wb);
            CellStyle cellStyle = createCellStyle(wb, HorizontalAlignment.CENTER);

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(memo);
            JsonNode tables = root.get("tables");

            int rowIdx = 0;

            Row titleRow = sheet.createRow(rowIdx++);
            titleRow.setHeightInPoints(24);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue(title == null ? "풍량측정표" : title);
            titleCell.setCellStyle(titleStyle);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 10));

            Row infoRow = sheet.createRow(rowIdx++);
            createExcelCell(infoRow, 0, "위치명", headerStyle);
            createExcelCell(infoRow, 1, locationName, cellStyle);
            createExcelCell(infoRow, 2, "점검결과", headerStyle);
            createExcelCell(infoRow, 3, resultValue, cellStyle);

            rowIdx++;

            if (tables != null && tables.has("supply")) {
                rowIdx = createSingleAirflowExcelTable(
                        sheet,
                        rowIdx,
                        "풍량측정표 (급기)",
                        tables.get("supply"),
                        headerStyle,
                        cellStyle
                );
                rowIdx += 2;
            }

            if (tables != null && tables.has("return")) {
                rowIdx = createSingleAirflowExcelTable(
                        sheet,
                        rowIdx,
                        "풍량측정표 (환기)",
                        tables.get("return"),
                        headerStyle,
                        cellStyle
                );
                rowIdx += 2;
            }

            if (tables != null && tables.has("ventilation")) {
                rowIdx = createSingleAirflowExcelTable(
                        sheet,
                        rowIdx,
                        "풍량측정표",
                        tables.get("ventilation"),
                        headerStyle,
                        cellStyle
                );
            }

            for (int i = 0; i <= 10; i++) {
                sheet.setColumnWidth(i, 2600);
            }

            sheet.setColumnWidth(0, 3200);
            sheet.setDisplayGridlines(false);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            wb.write(baos);
            return baos.toByteArray();

        } catch (Exception e) {
            throw new RuntimeException("풍량측정표 엑셀 생성 실패", e);
        }
    }

    private byte[] createFanControlSheetExcel(String title,
                                              String locationName,
                                              String resultValue,
                                              String memo) {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("풍량조절표");

            CellStyle titleStyle = createTitleStyle(wb);
            CellStyle headerStyle = createHeaderStyle(wb);
            CellStyle cellStyle = createCellStyle(wb, HorizontalAlignment.CENTER);

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(memo);
            JsonNode rows = root.get("rows");

            int rowIdx = 0;

            Row titleRow = sheet.createRow(rowIdx++);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue(title == null ? "풍량조절표" : title);
            titleCell.setCellStyle(titleStyle);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 5));

            Row infoRow = sheet.createRow(rowIdx++);
            createExcelCell(infoRow, 0, "위치명", headerStyle);
            createExcelCell(infoRow, 1, locationName, cellStyle);
            createExcelCell(infoRow, 2, "점검결과", headerStyle);
            createExcelCell(infoRow, 3, resultValue, cellStyle);

            rowIdx++;

            Row header = sheet.createRow(rowIdx++);
            String[] headers = {"장비번호, 실명", "강", "중", "약", "출구온도", "점검결과"};

            for (int i = 0; i < headers.length; i++) {
                createExcelCell(header, i, headers[i], headerStyle);
            }

            for (int i = 1; i <= 20; i++) {
                Row row = sheet.createRow(rowIdx++);
                JsonNode r = rows == null ? null : rows.get(String.valueOf(i));

                createExcelCell(row, 0, getJsonVal(r, "roomName"), cellStyle);
                createExcelCell(row, 1, getJsonVal(r, "strong"), cellStyle);
                createExcelCell(row, 2, getJsonVal(r, "middle"), cellStyle);
                createExcelCell(row, 3, getJsonVal(r, "weak"), cellStyle);
                createExcelCell(row, 4, getJsonVal(r, "outletTemp"), cellStyle);
                createExcelCell(row, 5, getJsonVal(r, "checkResult"), cellStyle);
            }

            sheet.setColumnWidth(0, 6000);
            for (int i = 1; i <= 5; i++) {
                sheet.setColumnWidth(i, 3500);
            }

            sheet.setDisplayGridlines(false);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            wb.write(baos);
            return baos.toByteArray();

        } catch (Exception e) {
            throw new RuntimeException("풍량조절표 엑셀 생성 실패", e);
        }
    }

    private int createSingleAirflowExcelTable(Sheet sheet,
                                              int rowIdx,
                                              String tableTitle,
                                              JsonNode tableNode,
                                              CellStyle headerStyle,
                                              CellStyle cellStyle) {

        JsonNode rows = tableNode == null ? null : tableNode.get("rows");
        JsonNode summary = tableNode == null ? null : tableNode.get("summary");

        Row titleRow = sheet.createRow(rowIdx++);

        for (int c = 0; c <= 6; c++) {
            createExcelCell(titleRow, c, "", headerStyle);
        }

        titleRow.getCell(0).setCellValue(tableTitle);

        sheet.addMergedRegion(new CellRangeAddress(
                rowIdx - 1,
                rowIdx - 1,
                0,
                6
        ));

        rowIdx++;

        Row header = sheet.createRow(rowIdx++);
        createExcelCell(header, 0, "측정 위치", headerStyle);

        String[] cols = {"A", "B", "C", "D", "E", "F"};
        for (int c = 0; c < cols.length; c++) {
            createExcelCell(header, c + 1, cols[c], headerStyle);
        }

        for (int i = 1; i <= 8; i++) {
            Row row = sheet.createRow(rowIdx++);
            createExcelCell(row, 0, String.valueOf(i), cellStyle);

            JsonNode rowNode = rows == null ? null : rows.get(String.valueOf(i));

            for (int c = 0; c < cols.length; c++) {
                createExcelCell(row, c + 1, getJsonVal(rowNode, cols[c]), cellStyle);
            }
        }

        Row avgRow = sheet.createRow(rowIdx++);

        createExcelCell(avgRow, 0, "평균풍속", headerStyle);

        for (int c = 1; c <= 6; c++) {
            createExcelCell(avgRow, c, "", cellStyle);
        }

        avgRow.getCell(1).setCellValue(getJsonVal(summary, "avgSpeed") + " m/s");

        sheet.addMergedRegion(new CellRangeAddress(
                avgRow.getRowNum(),
                avgRow.getRowNum(),
                1,
                6
        ));

        rowIdx++;

        Row detailHeader = sheet.createRow(rowIdx++);
        createExcelCell(detailHeader, 0, "항 목", headerStyle);
        createExcelCell(detailHeader, 1, "단위", headerStyle);
        createExcelCell(detailHeader, 2, "설계값", headerStyle);
        createExcelCell(detailHeader, 3, "측정값", headerStyle);

        Row duct = sheet.createRow(rowIdx++);
        createExcelCell(duct, 0, "덕트사이즈", headerStyle);
        createExcelCell(duct, 1, "m", cellStyle);
        createExcelCell(duct, 2, getJsonVal(summary, "ductWidth"), cellStyle);
        createExcelCell(duct, 3, getJsonVal(summary, "ductHeight"), cellStyle);

        Row area = sheet.createRow(rowIdx++);
        createExcelCell(area, 0, "단 면 적", headerStyle);
        createExcelCell(area, 1, "㎡", cellStyle);
        createExcelCell(area, 2, getJsonVal(summary, "area"), cellStyle);
        createExcelCell(area, 3, "", cellStyle);

        Row airflow = sheet.createRow(rowIdx++);
        createExcelCell(airflow, 0, "풍 량", headerStyle);
        createExcelCell(airflow, 1, "CMH", cellStyle);
        createExcelCell(airflow, 2, getJsonVal(summary, "designAirflow"), cellStyle);
        createExcelCell(airflow, 3, getJsonVal(summary, "measuredAirflow"), cellStyle);

        Row rate = sheet.createRow(rowIdx++);
        createExcelCell(rate, 0, "설계 대비", headerStyle);
        createExcelCell(rate, 1, "%", cellStyle);
        createExcelCell(rate, 2, getJsonVal(summary, "designRate"), cellStyle);
        createExcelCell(rate, 3, "", cellStyle);

        Row current = sheet.createRow(rowIdx++);
        createExcelCell(current, 0, "운전 전류", headerStyle);
        createExcelCell(current, 1, "A", cellStyle);
        createExcelCell(current, 2, getJsonVal(summary, "current"), cellStyle);
        createExcelCell(current, 3, "", cellStyle);

        Row hz = sheet.createRow(rowIdx++);
        createExcelCell(hz, 0, "인버터 제어시", headerStyle);
        createExcelCell(hz, 1, "Hz", cellStyle);
        createExcelCell(hz, 2, getJsonVal(summary, "inverterHz"), cellStyle);
        createExcelCell(hz, 3, "", cellStyle);

        return rowIdx;
    }

    private CellStyle createTitleStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();

        Font font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 14);

        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);

        return style;
    }

    private CellStyle createHeaderStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();

        Font font = wb.createFont();
        font.setBold(true);

        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        return style;
    }

    private CellStyle createCellStyle(Workbook wb, HorizontalAlignment alignment) {
        CellStyle style = wb.createCellStyle();

        style.setAlignment(alignment);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);

        return style;
    }

    private void createExcelCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    private String getJsonVal(JsonNode node, String key) {
        if (node == null || node.get(key) == null) {
            return "";
        }

        return node.get(key).asText("");
    }
}