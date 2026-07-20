package com.example.bluetechcloud.service;

import com.example.bluetechcloud.entity.InspectionItemEntity;
import com.example.bluetechcloud.entity.InspectionResultEntity;
import com.example.bluetechcloud.entity.PhotoEntity;
import com.example.bluetechcloud.model.SiteDTO;
import com.example.bluetechcloud.repository.InspectionItemRepo;
import com.example.bluetechcloud.repository.InspectionResultRepo;
import com.example.bluetechcloud.repository.InspectionSubItemRepo;
import com.example.bluetechcloud.repository.PhotoRepo;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ExcelReportService {

    private final S3Client s3Client;

    @Value("${cloud.aws.s3.bucket}")
    private String bucket;

    private final SiteService siteService;
    private final InspectionResultRepo inspectionResultRepo;
    private final InspectionItemRepo inspectionItemRepo;
    private final PhotoRepo photoRepo;
    private final ObjectMapper objectMapper;
    private final InspectionSubItemRepo inspectionSubItemRepo;

    private static final int EXCEL_PHOTO_MAX_WIDTH = 1200;
    private static final int EXCEL_PHOTO_MAX_HEIGHT = 800;
    private static final float EXCEL_PHOTO_QUALITY = 0.86f;

    private static final int NORMAL_PHOTO_COL_WIDTH = 2330;
    private static final int NORMAL_PHOTO_ROW_COUNT = 9;
    private static final float NORMAL_PHOTO_ROW_HEIGHT = 15.8f;

    private static final int SEOUL_PHOTO_COL_WIDTH = 2170;
    private static final int SEOUL_PHOTO_ROW_COUNT = 10;
    private static final float SEOUL_PHOTO_ROW_HEIGHT = 14.9f;
    public void downloadPerformanceCheckExcel(Long siteId, HttpServletResponse response) throws IOException {
        long totalStart = System.currentTimeMillis();

        SiteDTO site = siteService.getSite(siteId);

        List<InspectionResultEntity> results = inspectionResultRepo.findBySiteId(siteId);

        List<Long> resultIds = results.stream()
                .map(InspectionResultEntity::getId)
                .filter(Objects::nonNull)
                .toList();

        Map<Long, List<PhotoEntity>> photosByResultId = resultIds.isEmpty()
                ? new HashMap<>()
                : photoRepo.findByResultIdIn(resultIds)
                .stream()
                .collect(Collectors.groupingBy(PhotoEntity::getResultId));

        List<InspectionItemEntity> allItems =
                inspectionItemRepo.findAllByOrderByCategoryOrderAscOrderNoAscIdAsc();

        Set<Long> writtenItemIdSet = results.stream()
                .filter(result -> {
                    List<PhotoEntity> photos = photosByResultId.getOrDefault(
                            result.getId(),
                            Collections.emptyList()
                    );
                    return shouldRenderItemBlock(result, photos);
                })
                .map(InspectionResultEntity::getItemId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<InspectionItemEntity> items = allItems.stream()
                .filter(item -> writtenItemIdSet.contains(item.getId()))
                .toList();

        List<PhotoEntity> renderPhotos = photosByResultId.values()
                .stream()
                .flatMap(List::stream)
                .filter(photo -> photo.getId() != null)
                .toList();

        System.out.println("[Excel] 결과 수: " + results.size());
        System.out.println("[Excel] 사진 수: " + renderPhotos.size());

        long photoStart = System.currentTimeMillis();
        Map<Long, byte[]> excelPhotoBytesMap = prepareExcelPhotoBytesMap(renderPhotos);
        System.out.println("[Excel] 사진 다운로드/리사이즈: " + (System.currentTimeMillis() - photoStart) + "ms");

        long totalPhotoBytes = excelPhotoBytesMap.values()
                .stream()
                .mapToLong(bytes -> bytes == null ? 0 : bytes.length)
                .sum();

        System.out.println("[Excel] 엑셀 사진 총 용량: " + (totalPhotoBytes / 1024 / 1024) + "MB");

        String fileName = safeFileName(site.getSite_name() + "_성능점검보고서.xlsx");

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader(
                "Content-Disposition",
                "attachment; filename*=UTF-8''" +
                        URLEncoder.encode(fileName, StandardCharsets.UTF_8).replaceAll("\\+", "%20")
        );

        long workbookStart = System.currentTimeMillis();

        try (Workbook wb = new XSSFWorkbook()) {
            Styles styles = createStyles(wb);

            createCoverSheet(wb, styles, site);
            createWorkSummarySheet(wb, styles, site);
            createTargetFacilitySheet(wb, styles, site, items, results);
            createCategorySheets(wb, styles, site, items, results, photosByResultId, excelPhotoBytesMap);

            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet sheet = wb.getSheetAt(i);
                String name = sheet.getSheetName();

                if (!name.equals("표지") && !name.equals("업무현황") && !name.equals("대상설비현황")) {
                    continue;
                }

                autoSizeSafe(sheet, 0, 6);
            }

            System.out.println("[Excel] 엑셀 생성: " + (System.currentTimeMillis() - workbookStart) + "ms");

            long writeStart = System.currentTimeMillis();
            wb.write(response.getOutputStream());
            System.out.println("[Excel] 응답 쓰기: " + (System.currentTimeMillis() - writeStart) + "ms");
        }

        System.out.println("[Excel] 전체 시간: " + (System.currentTimeMillis() - totalStart) + "ms");
    }

    private String makeResultGroupKey(InspectionResultEntity result) {
        return safe(result.getCategoryGroup()) + "::" + (result.getSubItemId() == null ? 0 : result.getSubItemId());
    }

    private boolean isSeoulType(SiteDTO site) {
        String name = safe(site.getSite_name());
        return name.contains("서울형");
    }

    private Map<Long, byte[]> prepareExcelPhotoBytesMap(List<PhotoEntity> photos) {
        if (photos == null || photos.isEmpty()) {
            return new HashMap<>();
        }

        int threadCount = 2;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        Map<Long, byte[]> resultMap = new ConcurrentHashMap<>();

        try {
            List<Future<?>> futures = new ArrayList<>();

            for (PhotoEntity photo : photos) {
                if (photo == null || photo.getId() == null) continue;
                if (photo.getFileUrl() == null || photo.getFileUrl().isBlank()) continue;

                futures.add(executor.submit(() -> {
                    try {
                        String s3Key = extractKeyFromUrl(photo.getFileUrl());

                        GetObjectRequest req = GetObjectRequest.builder()
                                .bucket(bucket)
                                .key(s3Key)
                                .build();

                        try (InputStream is = s3Client.getObject(req)) {
                            byte[] bytes = resizeImageKeepRatioForExcel(
                                    is,
                                    EXCEL_PHOTO_MAX_WIDTH,
                                    EXCEL_PHOTO_MAX_HEIGHT,
                                    EXCEL_PHOTO_QUALITY
                            );

                            resultMap.put(photo.getId(), bytes);
                        }

                    } catch (Exception e) {
                        System.out.println("엑셀용 사진 준비 실패: " + photo.getFileUrl());
                    }
                }));
            }

            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception ignored) {
                }
            }

        } finally {
            executor.shutdown();
        }

        return resultMap;
    }

    private byte[] resizeImageKeepRatioForExcel(InputStream inputStream,
                                                int maxWidth,
                                                int maxHeight,
                                                float quality) throws IOException {
        BufferedImage original = ImageIO.read(inputStream);

        if (original == null) {
            throw new IOException("이미지 읽기 실패");
        }

        int originalWidth = original.getWidth();
        int originalHeight = original.getHeight();

        double scale = Math.min(
                (double) maxWidth / originalWidth,
                (double) maxHeight / originalHeight
        );

        if (scale > 1.0) {
            scale = 1.0;
        }

        int newWidth = Math.max(1, (int) Math.round(originalWidth * scale));
        int newHeight = Math.max(1, (int) Math.round(originalHeight * scale));

        BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();

        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, newWidth, newHeight);

        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g.drawImage(original, 0, 0, newWidth, newHeight, java.awt.Color.WHITE, null);
        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IOException("JPG writer 없음");
        }

        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);

        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(resized, null, null), param);
        } finally {
            writer.dispose();
        }

        return baos.toByteArray();
    }

    private String buildItemTitle(InspectionItemEntity item, InspectionResultEntity result) {
        String baseTitle = item.getOrderNo() + ". " + safe(item.getContent());

        if (result == null || result.getSubItemId() == null || result.getSubItemId() == 0) {
            return baseTitle;
        }

        return inspectionSubItemRepo.findById(result.getSubItemId())
                .map(sub -> baseTitle + "  " + safe(sub.getContent()))
                .orElse(baseTitle);
    }

    private int createItemBlock(
            Sheet sheet,
            Workbook wb,
            Styles styles,
            SiteDTO site,
            int startRow,
            InspectionItemEntity item,
            InspectionResultEntity result,
            List<PhotoEntity> photos,
            Map<Long, byte[]> excelPhotoBytesMap
    ) {
        String itemTitle = buildItemTitle(item, result);
        String locationName = result == null ? "" : extractLocationName(result.getCategoryGroup());
        String resultValue = result == null ? "" : safe(result.getResult());
        String memo = result == null ? "" : safe(result.getMemo());
        String userMemo = extractUserMemo(memo);

        boolean seoulType = isSeoulType(site);
        int photoRowCount = seoulType ? SEOUL_PHOTO_ROW_COUNT : NORMAL_PHOTO_ROW_COUNT;
        float photoRowHeight = seoulType ? SEOUL_PHOTO_ROW_HEIGHT : NORMAL_PHOTO_ROW_HEIGHT;

        mergeAndSet(sheet, startRow, startRow + 1, 0, 8, itemTitle, styles.header);

        Row titleRow = sheet.getRow(startRow);
        if (titleRow != null) {
            titleRow.setHeightInPoints(42);
        }

        Row titleRow2 = sheet.getRow(startRow + 1);
        if (titleRow2 == null) {
            titleRow2 = sheet.createRow(startRow + 1);
        }
        titleRow2.setHeightInPoints(42);

        startRow += 2;

        Row row1 = sheet.createRow(startRow++);
        row1.setHeightInPoints(22);
        createCell(row1, 0, "위치명", styles.label);
        mergeCellsAndSet(sheet, row1, row1.getRowNum(), 1, 3, locationName, styles.value);
        createCell(row1, 4, "점검결과", styles.label);
        mergeCellsAndSet(sheet, row1, row1.getRowNum(), 5, 8, resultValue, styles.value);

        if (isAirflowSheet(memo)) {
            if (!userMemo.isBlank()) {
                Row memoRow = sheet.createRow(startRow++);
                createCell(memoRow, 0, "메모", styles.label);
                mergeCellsAndSet(sheet, memoRow, memoRow.getRowNum(), 1, 8, userMemo, styles.value);
            }
            startRow = createAirflowTable(sheet, styles, startRow, memo);
        } else if (isEfficiencySheet(memo)) {
            if (!userMemo.isBlank()) {
                Row memoRow = sheet.createRow(startRow++);
                createCell(memoRow, 0, "메모", styles.label);
                mergeCellsAndSet(sheet, memoRow, memoRow.getRowNum(), 1, 8, userMemo, styles.value);
            }
            startRow = createEfficiencyTable(sheet, styles, startRow, memo);
        } else if (isFanControlSheet(memo)) {
            if (!userMemo.isBlank()) {
                Row memoRow = sheet.createRow(startRow++);
                createCell(memoRow, 0, "메모", styles.label);
                mergeCellsAndSet(sheet, memoRow, memoRow.getRowNum(), 1, 8, userMemo, styles.value);
            }
            startRow = createFanControlTable(sheet, styles, startRow, memo);
        } else {
            Row row2 = sheet.createRow(startRow++);
            createCell(row2, 0, "메모", styles.label);
            mergeCellsAndSet(sheet, row2, row2.getRowNum(), 1, 8, memo, styles.value);
        }

        mergeAndSet(sheet, startRow, startRow, 0, 8, "점검 사진", styles.tableHeader);
        sheet.getRow(startRow).setHeightInPoints(22);
        startRow++;

        if (photos == null || photos.isEmpty()) {
            mergeAndSet(sheet, startRow, startRow, 0, 8, "첨부된 사진 없음", styles.note);
            sheet.getRow(startRow).setHeightInPoints(24);
            startRow += 2;
            return startRow;
        }

        int photoIndex = 0;

        while (photoIndex < photos.size()) {
            for (int r = 0; r < photoRowCount; r++) {
                Row imgRow = sheet.getRow(startRow + r);
                if (imgRow == null) imgRow = sheet.createRow(startRow + r);
                imgRow.setHeightInPoints(photoRowHeight);
            }

            insertPhoto(
                    sheet,
                    wb,
                    photos.get(photoIndex),
                    startRow,
                    startRow + photoRowCount - 1,
                    0,
                    4,
                    excelPhotoBytesMap
            );
            photoIndex++;

            if (photoIndex < photos.size()) {
                insertPhoto(
                        sheet,
                        wb,
                        photos.get(photoIndex),
                        startRow,
                        startRow + photoRowCount - 1,
                        5,
                        9,
                        excelPhotoBytesMap
                );
                photoIndex++;
            }

            startRow += photoRowCount + 1;
        }

        startRow += 2;
        return startRow;
    }

    private String extractUserMemo(String memo) {
        if (memo == null || memo.isBlank()) return "";

        try {
            JsonNode root = objectMapper.readTree(memo);
            JsonNode userMemo = root.get("userMemo");

            if (userMemo == null || userMemo.isNull()) {
                return "";
            }

            return userMemo.asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private int createEfficiencyTable(Sheet sheet, Styles styles, int startRow, String memo) {
        try {
            JsonNode root = objectMapper.readTree(memo);
            JsonNode rows = root.get("rows");
            JsonNode summary = root.get("summary");

            mergeAndSet(sheet, startRow, startRow, 0, 5, "효율시트", styles.tableHeader);
            startRow++;

            Row header = sheet.createRow(startRow++);
            String[] headers = {"순번", "고온℃", "저온℃", "유량\nLPM", "열량\nkcal/h", "에너지\n사용량"};

            for (int i = 0; i < headers.length; i++) {
                createCell(header, i, headers[i], styles.tableHeader);
            }

            for (int i = 1; i <= 30; i++) {
                Row row = sheet.createRow(startRow++);
                JsonNode r = rows == null ? null : rows.get(String.valueOf(i));

                createCell(row, 0, String.valueOf(i), styles.tableCellCenter);
                createCell(row, 1, getVal(r, "highTemp"), styles.tableCellCenter);
                createCell(row, 2, getVal(r, "lowTemp"), styles.tableCellCenter);
                createCell(row, 3, getVal(r, "flow"), styles.tableCellCenter);
                createCell(row, 4, getVal(r, "heat"), styles.tableCellCenter);
                createCell(row, 5, getVal(r, "energy"), styles.tableCellCenter);
            }

            Row sumRow = sheet.createRow(startRow++);
            createCell(sumRow, 0, "합계", styles.tableHeader);
            createCell(sumRow, 1, getVal(summary, "avgHighTemp"), styles.tableCellCenter);
            createCell(sumRow, 2, getVal(summary, "avgLowTemp"), styles.tableCellCenter);
            createCell(sumRow, 3, getVal(summary, "totalFlow"), styles.tableCellCenter);
            createCell(sumRow, 4, getVal(summary, "totalHeat"), styles.tableCellCenter);
            createCell(sumRow, 5, getVal(summary, "totalEnergy"), styles.tableCellCenter);

            startRow += 2;
        } catch (Exception e) {
            e.printStackTrace();
            Row row = sheet.createRow(startRow++);
            createCell(row, 0, "효율시트 오류", styles.label);
            mergeCellsAndSet(sheet, row, row.getRowNum(), 1, 8, "효율시트 JSON 파싱 실패", styles.value);
        }

        return startRow;
    }

    private int createAirflowTable(Sheet sheet, Styles styles, int startRow, String memo) {
        try {
            JsonNode root = objectMapper.readTree(memo);
            JsonNode tables = root.get("tables");

            if (tables == null || tables.isNull()) {
                return startRow;
            }

            if (tables.has("supply")) {
                startRow = createSingleAirflowTable(sheet, styles, startRow, "풍량측정표 (급기)", tables.get("supply"));
                startRow += 2;
            }

            if (tables.has("return")) {
                startRow = createSingleAirflowTable(sheet, styles, startRow, "풍량측정표 (환기)", tables.get("return"));
                startRow += 2;
            }

            if (tables.has("ventilation")) {
                startRow = createSingleAirflowTable(sheet, styles, startRow, "풍량측정표", tables.get("ventilation"));
                startRow += 2;
            }

        } catch (Exception e) {
            e.printStackTrace();

            Row row = sheet.createRow(startRow++);
            createCell(row, 0, "풍량측정표 오류", styles.label);
            mergeCellsAndSet(sheet, row, row.getRowNum(), 1, 8, "풍량측정표 JSON 파싱 실패", styles.value);
        }

        return startRow;
    }

    private int createSingleAirflowTable(Sheet sheet, Styles styles, int startRow, String title, JsonNode tableNode) {
        JsonNode rows = tableNode == null ? null : tableNode.get("rows");
        JsonNode summary = tableNode == null ? null : tableNode.get("summary");

        // 제목
        mergeAndSet(sheet, startRow, startRow, 0, 6, title, styles.tableHeader);
        Row titleRow = sheet.getRow(startRow);
        if (titleRow != null) {
            titleRow.setHeightInPoints(22);
        }
        startRow++;

        // 빈 줄 느낌
        startRow++;

        // 측정 위치 / A~F
        Row header = sheet.createRow(startRow++);
        createCell(header, 0, "측정 위치", styles.tableHeader);

        String[] cols = {"A", "B", "C", "D", "E", "F"};
        for (int c = 0; c < cols.length; c++) {
            createCell(header, c + 1, cols[c], styles.tableHeader);
        }

        // 1~8행
        for (int i = 1; i <= 8; i++) {
            Row row = sheet.createRow(startRow++);
            createCell(row, 0, String.valueOf(i), styles.tableCellCenter);

            JsonNode rowNode = rows == null ? null : rows.get(String.valueOf(i));

            for (int c = 0; c < cols.length; c++) {
                createCell(row, c + 1, getVal(rowNode, cols[c]), styles.tableCellCenter);
            }
        }

        // 평균풍속
        Row avgRow = sheet.createRow(startRow++);
        createCell(avgRow, 0, "평균풍속", styles.tableHeader);
        mergeCellsAndSet(
                sheet,
                avgRow,
                avgRow.getRowNum(),
                1,
                6,
                getVal(summary, "avgSpeed"),
                styles.tableCellCenter
        );

        // 빈 줄
        startRow++;

        // 상세표 헤더
        Row detailHeader = sheet.createRow(startRow++);
        createCell(detailHeader, 0, "항 목", styles.tableHeader);
        createCell(detailHeader, 1, "단위", styles.tableHeader);
        createCell(detailHeader, 2, "설계값", styles.tableHeader);
        createCell(detailHeader, 3, "측정값", styles.tableHeader);

        // 덕트사이즈
        Row ductRow = sheet.createRow(startRow++);
        createCell(ductRow, 0, "덕트사이즈", styles.tableHeader);
        createCell(ductRow, 1, "m", styles.tableCellCenter);
        createCell(ductRow, 2, getVal(summary, "ductWidth"), styles.tableCellCenter);
        createCell(ductRow, 3, getVal(summary, "ductHeight"), styles.tableCellCenter);

        // 단면적
        Row areaRow = sheet.createRow(startRow++);
        createCell(areaRow, 0, "단 면 적", styles.tableHeader);
        createCell(areaRow, 1, "㎡", styles.tableCellCenter);
        mergeCellsAndSet(
                sheet,
                areaRow,
                areaRow.getRowNum(),
                2,
                3,
                getVal(summary, "area"),
                styles.tableCellCenter
        );

        // 풍량
        Row airflowRow = sheet.createRow(startRow++);
        createCell(airflowRow, 0, "풍 량", styles.tableHeader);
        createCell(airflowRow, 1, "CMH", styles.tableCellCenter);
        createCell(airflowRow, 2, getVal(summary, "designAirflow"), styles.tableCellCenter);
        createCell(airflowRow, 3, getVal(summary, "measuredAirflow"), styles.tableCellCenter);

        // 설계 대비
        Row rateRow = sheet.createRow(startRow++);
        createCell(rateRow, 0, "설계 대비", styles.tableHeader);
        createCell(rateRow, 1, "%", styles.tableCellCenter);
        mergeCellsAndSet(
                sheet,
                rateRow,
                rateRow.getRowNum(),
                2,
                3,
                getVal(summary, "designRate"),
                styles.tableCellCenter
        );

        // 운전 전류
        Row currentRow = sheet.createRow(startRow++);
        createCell(currentRow, 0, "운전 전류", styles.tableHeader);
        createCell(currentRow, 1, "A", styles.tableCellCenter);
        mergeCellsAndSet(
                sheet,
                currentRow,
                currentRow.getRowNum(),
                2,
                3,
                getVal(summary, "current"),
                styles.tableCellCenter
        );

        // 인버터 제어시
        Row hzRow = sheet.createRow(startRow++);
        createCell(hzRow, 0, "인버터 제어시", styles.tableHeader);
        createCell(hzRow, 1, "Hz", styles.tableCellCenter);
        mergeCellsAndSet(
                sheet,
                hzRow,
                hzRow.getRowNum(),
                2,
                3,
                getVal(summary, "inverterHz"),
                styles.tableCellCenter
        );

        return startRow;
    }

    private int createFanControlTable(Sheet sheet, Styles styles, int startRow, String memo) {
        try {
            JsonNode root = objectMapper.readTree(memo);
            JsonNode rows = root.get("rows");

            mergeAndSet(sheet, startRow, startRow, 0, 5, "풍량조절표", styles.tableHeader);
            startRow++;

            Row header = sheet.createRow(startRow++);
            createCell(header, 0, "장비번호, 실명", styles.tableHeader);
            createCell(header, 1, "강", styles.tableHeader);
            createCell(header, 2, "중", styles.tableHeader);
            createCell(header, 3, "약", styles.tableHeader);
            createCell(header, 4, "출구온도", styles.tableHeader);
            createCell(header, 5, "점검결과", styles.tableHeader);

            for (int i = 1; i <= 20; i++) {
                Row row = sheet.createRow(startRow++);
                JsonNode r = rows == null ? null : rows.get(String.valueOf(i));

                createCell(row, 0, getVal(r, "roomName"), styles.tableCellCenter);
                createCell(row, 1, getVal(r, "strong"), styles.tableCellCenter);
                createCell(row, 2, getVal(r, "middle"), styles.tableCellCenter);
                createCell(row, 3, getVal(r, "weak"), styles.tableCellCenter);
                createCell(row, 4, getVal(r, "outletTemp"), styles.tableCellCenter);
                createCell(row, 5, getVal(r, "checkResult"), styles.tableCellCenter);
            }

            startRow += 2;

        } catch (Exception e) {
            e.printStackTrace();

            Row row = sheet.createRow(startRow++);
            createCell(row, 0, "풍량조절표 오류", styles.label);
            mergeCellsAndSet(sheet, row, row.getRowNum(), 1, 8, "풍량조절표 JSON 파싱 실패", styles.value);
        }

        return startRow;
    }

    public byte[] createMemoExcelFile(String memo) throws IOException {
        if (isAirflowSheet(memo)) {
            return createAirflowSheetExcel(memo);
        }

        if (isEfficiencySheet(memo)) {
            return createEfficiencySheetExcel(memo);
        }

        if (isFanControlSheet(memo)) {
            return createFanControlSheetExcel(memo);
        }

        return null;
    }

    public boolean isExcelMemo(String memo) {
        return isAirflowSheet(memo) || isEfficiencySheet(memo) || isFanControlSheet(memo);
    }

    private byte[] createAirflowSheetExcel(String memo) throws IOException {
        try (Workbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Styles styles = createStyles(wb);
            Sheet sheet = wb.createSheet("풍량측정표");

            for (int i = 0; i <= 10; i++) {
                sheet.setColumnWidth(i, 2300);
            }
            sheet.setColumnWidth(0, 2800);
            sheet.setDisplayGridlines(false);

            createAirflowTable(sheet, styles, 0, memo);

            wb.write(out);
            return out.toByteArray();
        }
    }

    private byte[] createEfficiencySheetExcel(String memo) throws IOException {
        try (Workbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Styles styles = createStyles(wb);
            Sheet sheet = wb.createSheet("효율시트");

            for (int i = 0; i <= 5; i++) {
                sheet.setColumnWidth(i, 3500);
            }
            sheet.setDisplayGridlines(false);

            createEfficiencyTable(sheet, styles, 0, memo);

            wb.write(out);
            return out.toByteArray();
        }
    }

    private byte[] createFanControlSheetExcel(String memo) throws IOException {
        try (Workbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Styles styles = createStyles(wb);
            Sheet sheet = wb.createSheet("풍량조절표");

            sheet.setColumnWidth(0, 6000);
            for (int i = 1; i <= 5; i++) {
                sheet.setColumnWidth(i, 3000);
            }
            sheet.setDisplayGridlines(false);

            createFanControlTable(sheet, styles, 0, memo);

            wb.write(out);
            return out.toByteArray();
        }
    }

    private void createCategorySheets(
            Workbook wb,
            Styles styles,
            SiteDTO site,
            List<InspectionItemEntity> items,
            List<InspectionResultEntity> results,
            Map<Long, List<PhotoEntity>> photosByResultId,
            Map<Long, byte[]> excelPhotoBytesMap
    ) {
        Map<String, List<InspectionItemEntity>> grouped = new LinkedHashMap<>();

        for (InspectionItemEntity item : items) {
            grouped.computeIfAbsent(item.getCategory(), k -> new ArrayList<>()).add(item);
        }

        Map<Long, List<InspectionResultEntity>> resultsByItemId = new HashMap<>();

        for (InspectionResultEntity result : results) {
            resultsByItemId
                    .computeIfAbsent(result.getItemId(), k -> new ArrayList<>())
                    .add(result);
        }

        for (Map.Entry<String, List<InspectionItemEntity>> entry : grouped.entrySet()) {
            String category = entry.getKey();
            List<InspectionItemEntity> categoryItems = entry.getValue();

            Sheet sheet = wb.createSheet(trimSheetName(category));
            applyCategorySheetLayout(sheet, site);

            int rowIdx = 0;
            boolean hasRenderedAnyBlock = false;

            mergeAndSet(sheet, rowIdx, rowIdx, 0, 8, "<" + category + " 성능점검표>", styles.header);
            rowIdx += 2;

            for (InspectionItemEntity item : categoryItems) {
                List<InspectionResultEntity> itemResults =
                        resultsByItemId.getOrDefault(item.getId(), Collections.emptyList());

                if (itemResults.isEmpty()) {
                    continue;
                }

                Map<String, List<InspectionResultEntity>> byGroup = new LinkedHashMap<>();

                for (InspectionResultEntity result : itemResults) {
                    String key = makeResultGroupKey(result);
                    byGroup.computeIfAbsent(key, k -> new ArrayList<>()).add(result);
                }

                for (Map.Entry<String, List<InspectionResultEntity>> groupEntry : byGroup.entrySet()) {
                    InspectionResultEntity latest = groupEntry.getValue()
                            .stream()
                            .max(Comparator.comparing(InspectionResultEntity::getId))
                            .orElse(null);

                    if (latest == null) {
                        continue;
                    }

                    List<PhotoEntity> photos = photosByResultId.getOrDefault(
                            latest.getId(),
                            Collections.emptyList()
                    );

                    if (!shouldRenderItemBlock(latest, photos)) {
                        continue;
                    }

                    rowIdx = createItemBlock(
                            sheet,
                            wb,
                            styles,
                            site,
                            rowIdx,
                            item,
                            latest,
                            photos,
                            excelPhotoBytesMap
                    );

                    hasRenderedAnyBlock = true;
                }
            }

            if (!hasRenderedAnyBlock) {
                mergeAndSet(sheet, rowIdx, rowIdx, 0, 8, "출력할 점검 내용이 없습니다.", styles.note);
            }
        }
    }

    private void applyCategorySheetLayout(Sheet sheet, SiteDTO site) {
        int photoColWidth = isSeoulType(site)
                ? SEOUL_PHOTO_COL_WIDTH
                : NORMAL_PHOTO_COL_WIDTH;

        sheet.setColumnWidth(0, photoColWidth);
        sheet.setColumnWidth(1, photoColWidth);
        sheet.setColumnWidth(2, photoColWidth);
        sheet.setColumnWidth(3, photoColWidth);

        sheet.setColumnWidth(4, 900);

        sheet.setColumnWidth(5, photoColWidth);
        sheet.setColumnWidth(6, photoColWidth);
        sheet.setColumnWidth(7, photoColWidth);
        sheet.setColumnWidth(8, photoColWidth);

        sheet.setDisplayGridlines(false);
        sheet.setFitToPage(true);
        sheet.setHorizontallyCenter(true);

        PrintSetup printSetup = sheet.getPrintSetup();
        printSetup.setLandscape(true);
        printSetup.setFitWidth((short) 1);
        printSetup.setFitHeight((short) 0);

        sheet.setAutobreaks(true);
    }

    private boolean shouldRenderItemBlock(InspectionResultEntity result, List<PhotoEntity> photos) {
        if (result == null) {
            return photos != null && !photos.isEmpty();
        }

        String resultValue = safe(result.getResult()).trim();
        String memo = safe(result.getMemo()).trim();
        boolean hasPhotos = photos != null && !photos.isEmpty();

        return hasMeaningfulResult(resultValue) || hasMeaningfulMemo(memo) || hasPhotos;
    }

    private boolean hasMeaningfulResult(String resultValue) {
        String value = safe(resultValue).trim();
        return !value.isEmpty() && !"미작성".equals(value);
    }

    private boolean hasMeaningfulMemo(String memo) {
        return !isBlank(memo);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
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

    private String getVal(JsonNode node, String key) {
        if (node == null || node.get(key) == null) return "";
        return node.get(key).asText("");
    }

    private void createCoverSheet(Workbook wb, Styles styles, SiteDTO site) {
        Sheet sheet = wb.createSheet("표지");
        setColumnWidths(sheet, 0, 0, 6000);
        setColumnWidths(sheet, 1, 1, 6000);
        setColumnWidths(sheet, 2, 2, 6000);
        setColumnWidths(sheet, 3, 3, 6000);

        mergeAndSet(sheet, 1, 1, 0, 3, "기계설비 성능점검 보고서", styles.title);
        mergeAndSet(sheet, 3, 3, 0, 3, site.getSite_name(), styles.subtitle);
        mergeAndSet(sheet, 5, 5, 0, 3, "Bluetech-Cloud 자동 생성본", styles.normalCenter);
    }

    private void createWorkSummarySheet(Workbook wb, Styles styles, SiteDTO site) {
        Sheet sheet = wb.createSheet("업무현황");
        setColumnWidths(sheet, 0, 0, 5000);
        setColumnWidths(sheet, 1, 1, 9000);
        setColumnWidths(sheet, 2, 2, 5000);
        setColumnWidths(sheet, 3, 3, 9000);

        int rowIdx = 0;

        mergeAndSet(sheet, rowIdx, rowIdx, 0, 3, "기계설비 성능점검 업무 현황", styles.header);
        rowIdx += 2;

        createKeyValueRow(sheet, rowIdx++, styles, "현장명", safe(site.getSite_name()), "점검유형", "성능점검");
        createKeyValueRow(sheet, rowIdx++, styles, "현장주소", "", "점검일", safe(site.getWork_date() == null ? "" : site.getWork_date().toString()));
        createKeyValueRow(sheet, rowIdx++, styles, "관리주체", "", "연락처", "");
        createKeyValueRow(sheet, rowIdx++, styles, "점검업체", "㈜푸른기술플러스", "비고", "");
    }

    private void createTargetFacilitySheet(
            Workbook wb,
            Styles styles,
            SiteDTO site,
            List<InspectionItemEntity> items,
            List<InspectionResultEntity> results
    ) {
        Sheet sheet = wb.createSheet("대상설비현황");

        setColumnWidths(sheet, 0, 0, 8000);
        setColumnWidths(sheet, 1, 1, 4000);
        setColumnWidths(sheet, 2, 2, 4000);
        setColumnWidths(sheet, 3, 3, 8000);
        setColumnWidths(sheet, 4, 4, 4000);
        setColumnWidths(sheet, 5, 5, 4000);

        int rowIdx = 0;
        mergeAndSet(sheet, rowIdx, rowIdx, 0, 5, "기계설비 성능점검 대상 현황표", styles.header);
        rowIdx += 2;

        Row header = sheet.createRow(rowIdx++);
        createCell(header, 0, "대상설비", styles.tableHeader);
        createCell(header, 1, "대상", styles.tableHeader);
        createCell(header, 2, "점검결과", styles.tableHeader);
        createCell(header, 3, "대상설비", styles.tableHeader);
        createCell(header, 4, "대상", styles.tableHeader);
        createCell(header, 5, "점검결과", styles.tableHeader);

        List<String> categories = extractOrderedCategories(items);
        List<String> left = new ArrayList<>();
        List<String> right = new ArrayList<>();

        for (int i = 0; i < categories.size(); i++) {
            if (i % 2 == 0) left.add(categories.get(i));
            else right.add(categories.get(i));
        }

        int max = Math.max(left.size(), right.size());
        for (int i = 0; i < max; i++) {
            Row row = sheet.createRow(rowIdx++);

            if (i < left.size()) {
                String category = left.get(i);
                createCell(row, 0, category, styles.tableCell);
                createCell(row, 1, isTargetCategory(items, category) ? "[○]" : "[ ]", styles.tableCellCenter);
                createCell(row, 2, summarizeCategoryResult(results, items, category), styles.tableCellCenter);
            } else {
                createCell(row, 0, "", styles.tableCell);
                createCell(row, 1, "", styles.tableCellCenter);
                createCell(row, 2, "", styles.tableCellCenter);
            }

            if (i < right.size()) {
                String category = right.get(i);
                createCell(row, 3, category, styles.tableCell);
                createCell(row, 4, isTargetCategory(items, category) ? "[○]" : "[ ]", styles.tableCellCenter);
                createCell(row, 5, summarizeCategoryResult(results, items, category), styles.tableCellCenter);
            } else {
                createCell(row, 3, "", styles.tableCell);
                createCell(row, 4, "", styles.tableCellCenter);
                createCell(row, 5, "", styles.tableCellCenter);
            }
        }
    }

    private List<String> extractOrderedCategories(List<InspectionItemEntity> items) {
        LinkedHashMap<String, Integer> ordered = new LinkedHashMap<>();
        for (InspectionItemEntity item : items) {
            ordered.putIfAbsent(item.getCategory(), item.getCategoryOrder());
        }
        return new ArrayList<>(ordered.keySet());
    }

    private boolean isTargetCategory(List<InspectionItemEntity> items, String category) {
        return items.stream().anyMatch(i -> Objects.equals(i.getCategory(), category));
    }

    private String summarizeCategoryResult(
            List<InspectionResultEntity> results,
            List<InspectionItemEntity> items,
            String category
    ) {
        Set<Long> itemIds = new HashSet<>();
        for (InspectionItemEntity item : items) {
            if (Objects.equals(item.getCategory(), category)) {
                itemIds.add(item.getId());
            }
        }

        boolean hasWritten = false;
        boolean hasNA = false;
        boolean hasFail = false;

        for (InspectionResultEntity result : results) {
            if (!itemIds.contains(result.getItemId())) continue;

            String value = safe(result.getResult()).trim();
            if ("작성".equals(value)) hasWritten = true;
            if ("해당사항없음".equals(value)) hasNA = true;
            if ("부적합".equals(value) || "이상".equals(value)) hasFail = true;
        }

        if (hasFail) return "X";
        if (hasWritten) return "○";
        if (hasNA) return "-";
        return "";
    }

    private void insertPhoto(Sheet sheet,
                             Workbook wb,
                             PhotoEntity photo,
                             int row1,
                             int row2,
                             int col1,
                             int col2,
                             Map<Long, byte[]> excelPhotoBytesMap) {
        try {
            if (photo == null || photo.getId() == null) {
                return;
            }

            byte[] imageBytes = excelPhotoBytesMap.get(photo.getId());

            if (imageBytes == null || imageBytes.length == 0) {
                return;
            }

            int pictureIdx = wb.addPicture(imageBytes, Workbook.PICTURE_TYPE_JPEG);
            Drawing<?> drawing = sheet.createDrawingPatriarch();

            XSSFClientAnchor anchor = new XSSFClientAnchor(
                    0, 0, 0, 0,
                    col1, row1,
                    col2, row2 + 1
            );

            anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_AND_RESIZE);
            drawing.createPicture(anchor, pictureIdx);

        } catch (Exception e) {
            System.out.println("엑셀 사진 삽입 실패: " + (photo == null ? "" : photo.getFileUrl()));
        }
    }

    private void createKeyValueRow(Sheet sheet, int rowIndex, Styles styles,
                                   String key1, String value1, String key2, String value2) {
        Row row = sheet.createRow(rowIndex);
        createCell(row, 0, key1, styles.label);
        createCell(row, 1, value1, styles.value);
        createCell(row, 2, key2, styles.label);
        createCell(row, 3, value2, styles.value);
    }

    private void mergeAndSet(Sheet sheet, int firstRow, int lastRow, int firstCol, int lastCol,
                             String text, CellStyle style) {
        sheet.addMergedRegion(new CellRangeAddress(firstRow, lastRow, firstCol, lastCol));
        Row row = sheet.getRow(firstRow);
        if (row == null) row = sheet.createRow(firstRow);
        Cell cell = row.createCell(firstCol);
        cell.setCellValue(text == null ? "" : text);
        cell.setCellStyle(style);

        for (int c = firstCol + 1; c <= lastCol; c++) {
            Cell extra = row.getCell(c);
            if (extra == null) extra = row.createCell(c);
            extra.setCellStyle(style);
        }
    }

    private void mergeCellsAndSet(Sheet sheet, Row row, int rowIndex, int firstCol, int lastCol, String text, CellStyle style) {
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, firstCol, lastCol));
        Cell cell = row.createCell(firstCol);
        cell.setCellValue(text == null ? "" : text);
        cell.setCellStyle(style);

        for (int i = firstCol + 1; i <= lastCol; i++) {
            Cell extra = row.createCell(i);
            extra.setCellStyle(style);
        }
    }

    private void createCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    private void setColumnWidths(Sheet sheet, int fromCol, int toCol, int width) {
        for (int i = fromCol; i <= toCol; i++) {
            sheet.setColumnWidth(i, width);
        }
    }

    private void autoSizeSafe(Sheet sheet, int fromCol, int toCol) {
        for (int i = fromCol; i <= toCol; i++) {
            try {
                sheet.autoSizeColumn(i);
                int current = sheet.getColumnWidth(i);
                sheet.setColumnWidth(i, Math.min(current + 800, 20000));
            } catch (Exception ignored) {
            }
        }
    }

    private String extractKeyFromUrl(String fileUrl) {
        String marker = ".amazonaws.com/";
        int idx = fileUrl.indexOf(marker);

        if (idx != -1) {
            return fileUrl.substring(idx + marker.length()).trim();
        }

        return fileUrl.trim();
    }

    private String extractLocationName(String categoryGroup) {
        if (categoryGroup == null || !categoryGroup.contains("_")) {
            return "";
        }
        String[] parts = categoryGroup.split("_", 2);
        return parts.length > 1 ? parts[1] : "";
    }

    private String trimSheetName(String name) {
        String safe = safe(name);
        safe = safe.replaceAll("[\\\\/*\\[\\]:?]", "_");
        return safe.length() > 31 ? safe.substring(0, 31) : safe;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String safeFileName(String fileName) {
        return fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private Styles createStyles(Workbook wb) {
        Styles s = new Styles();

        Font titleFont = wb.createFont();
        titleFont.setFontHeightInPoints((short) 20);
        titleFont.setBold(true);

        Font headerFont = wb.createFont();
        headerFont.setFontHeightInPoints((short) 12);
        headerFont.setBold(true);

        Font normalFont = wb.createFont();
        normalFont.setFontHeightInPoints((short) 10);

        s.title = wb.createCellStyle();
        s.title.setAlignment(HorizontalAlignment.CENTER);
        s.title.setVerticalAlignment(VerticalAlignment.CENTER);
        s.title.setFont(titleFont);

        s.subtitle = wb.createCellStyle();
        s.subtitle.setAlignment(HorizontalAlignment.CENTER);
        s.subtitle.setVerticalAlignment(VerticalAlignment.CENTER);
        s.subtitle.setFont(headerFont);

        s.header = createBorderStyle(wb, headerFont, IndexedColors.GREY_25_PERCENT.getIndex(), HorizontalAlignment.CENTER);
        s.label = createBorderStyle(wb, headerFont, IndexedColors.LEMON_CHIFFON.getIndex(), HorizontalAlignment.CENTER);
        s.value = createBorderStyle(wb, normalFont, IndexedColors.WHITE.getIndex(), HorizontalAlignment.LEFT);
        s.tableHeader = createBorderStyle(wb, headerFont, IndexedColors.GREY_25_PERCENT.getIndex(), HorizontalAlignment.CENTER);
        s.tableCell = createBorderStyle(wb, normalFont, IndexedColors.WHITE.getIndex(), HorizontalAlignment.LEFT);
        s.tableCellCenter = createBorderStyle(wb, normalFont, IndexedColors.WHITE.getIndex(), HorizontalAlignment.CENTER);
        s.note = createBorderStyle(wb, normalFont, IndexedColors.WHITE.getIndex(), HorizontalAlignment.LEFT);
        s.normalCenter = createBorderStyle(wb, normalFont, IndexedColors.WHITE.getIndex(), HorizontalAlignment.CENTER);

        s.header.setWrapText(true);
        s.value.setWrapText(true);
        s.tableCell.setWrapText(true);
        s.tableCellCenter.setWrapText(true);
        s.note.setWrapText(true);

        return s;
    }

    private CellStyle createBorderStyle(Workbook wb, Font font, short bgColor, HorizontalAlignment align) {
        CellStyle style = wb.createCellStyle();
        style.setFont(font);
        style.setAlignment(align);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setFillForegroundColor(bgColor);
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private static class Styles {
        CellStyle title;
        CellStyle subtitle;
        CellStyle header;
        CellStyle label;
        CellStyle value;
        CellStyle tableHeader;
        CellStyle tableCell;
        CellStyle tableCellCenter;
        CellStyle note;
        CellStyle normalCenter;
    }
}