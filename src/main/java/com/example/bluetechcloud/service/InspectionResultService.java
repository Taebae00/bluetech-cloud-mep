package com.example.bluetechcloud.service;

import com.example.bluetechcloud.entity.InspectionItemEntity;
import com.example.bluetechcloud.entity.InspectionResultEntity;
import com.example.bluetechcloud.entity.PhotoEntity;
import com.example.bluetechcloud.model.*;
import com.example.bluetechcloud.repository.InspectionItemRepo;
import com.example.bluetechcloud.repository.InspectionResultRepo;
import com.example.bluetechcloud.repository.PhotoRepo;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@Service
public class InspectionResultService {

    private final InspectionResultRepo inspectionResultRepo;
    private final InspectionItemRepo inspectionItemRepo;
    private final PhotoRepo photoRepo;
    private final FileService fileService;

    public InspectionResultService(InspectionResultRepo inspectionResultRepo, InspectionItemRepo inspectionItemRepo,
                                   PhotoRepo photoRepo,
                                   FileService fileService) {
        this.inspectionResultRepo = inspectionResultRepo;
        this.inspectionItemRepo = inspectionItemRepo;
        this.photoRepo = photoRepo;
        this.fileService = fileService;
    }

    @Transactional
    public void saveInspection(Long siteId,
                               Long itemId,
                               String categoryGroup,
                               String result,
                               String memo,
                               List<MultipartFile> photos) {

        InspectionResultEntity resultEntity = inspectionResultRepo
                .findFirstBySiteIdAndItemIdAndCategoryGroupOrderByIdDesc(siteId, itemId, categoryGroup)
                .orElseGet(() -> {
                    InspectionResultEntity newEntity = new InspectionResultEntity();
                    newEntity.setSiteId(siteId);
                    newEntity.setItemId(itemId);
                    newEntity.setCategoryGroup(categoryGroup);
                    return newEntity;
                });

        String safeResult = result == null ? "미작성" : result.trim();
        String safeMemo = memo == null ? "" : memo.trim();
        boolean hasPhoto = photos != null && photos.stream().anyMatch(p -> p != null && !p.isEmpty());

        if ("해당사항없음".equals(safeResult) && safeMemo.isBlank() && !hasPhoto) {
            resultEntity.setResult("해당사항없음");
            resultEntity.setMemo("");
        } else if ("미작성".equals(safeResult) && safeMemo.isBlank() && !hasPhoto) {
            resultEntity.setResult("미작성");
            resultEntity.setMemo("");
        } else if (!safeMemo.isBlank() || hasPhoto) {
            resultEntity.setResult("작성");
            resultEntity.setMemo(safeMemo);
        } else {
            resultEntity.setResult("미작성");
            resultEntity.setMemo("");
        }

        InspectionResultEntity savedResult = inspectionResultRepo.save(resultEntity);

        if (photos != null && !photos.isEmpty()) {
            for (MultipartFile photo : photos) {
                if (photo == null || photo.isEmpty()) {
                    continue;
                }

                String fileUrl = fileService.upload(photo);

                PhotoEntity photoEntity = new PhotoEntity();
                photoEntity.setResultId(savedResult.getId());
                photoEntity.setFileUrl(fileUrl);

                photoRepo.save(photoEntity);
            }
        }
    }

    @Transactional
    public void ensureDefaultCategoryGroups(Long siteId, Map<String, List<InspectionItemDTO>> baseGroupedItems) {
        for (Map.Entry<String, List<InspectionItemDTO>> entry : baseGroupedItems.entrySet()) {
            String baseCategory = entry.getKey();
            String defaultGroupName = baseCategory + "_기본";

            for (InspectionItemDTO item : entry.getValue()) {
                boolean exists = inspectionResultRepo.existsBySiteIdAndItemIdAndCategoryGroup(
                        siteId, item.getId(), defaultGroupName
                );

                if (!exists) {
                    InspectionResultEntity entity = new InspectionResultEntity();
                    entity.setSiteId(siteId);
                    entity.setItemId(item.getId());
                    entity.setCategoryGroup(defaultGroupName);
                    entity.setResult("미작성");
                    entity.setMemo("");
                    inspectionResultRepo.save(entity);
                }
            }
        }
    }

    public List<InspectionResultDTO> getResultsBySiteId(Long siteId) {
        List<InspectionResultEntity> entityList = inspectionResultRepo.findBySiteId(siteId);

        return entityList.stream()
                .map(entity -> InspectionResultDTO.builder()
                        .id(entity.getId())
                        .site_id(entity.getSiteId())
                        .item_id(entity.getItemId())
                        .category_group(entity.getCategoryGroup())
                        .result(entity.getResult())
                        .memo(entity.getMemo())
                        .created_at(entity.getCreatedAt())
                        .build())
                .toList();
    }

    @Transactional(readOnly = true)
    public InspectionResultEntity getInspectionResult(Long siteId,
                                                      Long itemId,
                                                      String categoryGroup,
                                                      Long subItemId) {

        Long normalizedSubItemId = (subItemId == null || subItemId == 0) ? null : subItemId;

        if (normalizedSubItemId == null) {
            return inspectionResultRepo
                    .findFirstBySiteIdAndItemIdAndCategoryGroupAndSubItemIdIsNullOrderByIdDesc(
                            siteId,
                            itemId,
                            categoryGroup
                    )
                    .orElse(null);
        }

        return inspectionResultRepo
                .findFirstBySiteIdAndItemIdAndCategoryGroupAndSubItemIdOrderByIdDesc(
                        siteId,
                        itemId,
                        categoryGroup,
                        normalizedSubItemId
                )
                .orElse(null);
    }

    @Transactional
    public void deletePhoto(Long photoId) {

        PhotoEntity photo = photoRepo.findById(photoId)
                .orElseThrow(() -> new RuntimeException("사진 없음"));

        fileService.delete(photo.getFileUrl()); // S3 삭제
        photoRepo.delete(photo);                // DB 삭제
    }

    @Transactional
    public void addCategoryGroup(Long siteId, String templateCategory, String newCategoryName) {

        if (templateCategory == null || templateCategory.isBlank()) {
            throw new RuntimeException("대주제가 없습니다.");
        }
        if (newCategoryName == null || newCategoryName.isBlank()) {
            throw new RuntimeException("위치명이 없습니다.");
        }

        templateCategory = templateCategory.trim();
        newCategoryName = newCategoryName.trim();

        if ("null".equalsIgnoreCase(newCategoryName)) {
            throw new RuntimeException("위치명이 올바르지 않습니다.");
        }

        String newGroupName = templateCategory + "_" + newCategoryName;

        List<InspectionItemEntity> items =
                inspectionItemRepo.findByCategoryOrderByOrderNoAsc(templateCategory);

        for (InspectionItemEntity item : items) {
            boolean exists = inspectionResultRepo.existsBySiteIdAndItemIdAndCategoryGroup(
                    siteId, item.getId(), newGroupName
            );

            if (!exists) {
                InspectionResultEntity entity = new InspectionResultEntity();
                entity.setSiteId(siteId);
                entity.setItemId(item.getId());
                entity.setCategoryGroup(newGroupName);
                entity.setResult(null);
                entity.setMemo(null);
                inspectionResultRepo.save(entity);
            }
        }
    }

    public List<PhotoDTO> getPhotosBySiteIdAndItemId(Long siteId, Long itemId, String categoryGroup) {

        Optional<InspectionResultEntity> resultOpt =
                inspectionResultRepo.findFirstBySiteIdAndItemIdAndCategoryGroupOrderByIdDesc(
                        siteId, itemId, categoryGroup
                );

        if (resultOpt.isEmpty()) {
            return new ArrayList<>();
        }

        List<PhotoEntity> list = photoRepo.findByResultId(resultOpt.get().getId());

        List<PhotoDTO> result = new ArrayList<>();

        for (PhotoEntity p : list) {
            PhotoDTO dto = new PhotoDTO();
            dto.setId(p.getId());
            dto.setFile_url(p.getFileUrl());
            result.add(dto);
        }

        return result;
    }

    public InspectionResultDTO getResult(Long siteId, Long itemId, String categoryGroup) {

        return inspectionResultRepo
                .findFirstBySiteIdAndItemIdAndCategoryGroupOrderByIdDesc(
                        siteId, itemId, categoryGroup
                )
                .map(this::toDTO)
                .orElse(null);
    }

    private InspectionResultDTO toDTO(InspectionResultEntity entity) {

        InspectionResultDTO dto = new InspectionResultDTO();

        dto.setId(entity.getId());
        dto.setSite_id(entity.getSiteId());
        dto.setItem_id(entity.getItemId());
        dto.setResult(entity.getResult());
        dto.setMemo(entity.getMemo());
        dto.setCategory_group(entity.getCategoryGroup());
        dto.setCreated_at(entity.getCreatedAt());

        return dto;
    }

    @Transactional
    public void deleteCategoryGroup(Long siteId, String categoryGroup) {
        List<InspectionResultEntity> results =
                inspectionResultRepo.findBySiteIdAndCategoryGroup(siteId, categoryGroup);

        for (InspectionResultEntity result : results) {
            List<PhotoEntity> photos = photoRepo.findByResultId(result.getId());

            for (PhotoEntity photo : photos) {
                fileService.delete(photo.getFileUrl());
            }

            photoRepo.deleteByResultId(result.getId());
        }

        inspectionResultRepo.deleteBySiteIdAndCategoryGroup(siteId, categoryGroup);
    }

    @Transactional
    public void resetInspection(Long siteId,
                                Long itemId,
                                String categoryGroup,
                                Long subItemId,
                                String targetResult) {

        Long normalizedSubItemId = normalizeSubItemId(subItemId);

        InspectionResultEntity resultEntity;

        if (normalizedSubItemId == null) {
            resultEntity = inspectionResultRepo
                    .findFirstBySiteIdAndItemIdAndCategoryGroupAndSubItemIdIsNullOrderByIdDesc(
                            siteId,
                            itemId,
                            categoryGroup
                    )
                    .orElseGet(() -> {
                        InspectionResultEntity newEntity = new InspectionResultEntity();
                        newEntity.setSiteId(siteId);
                        newEntity.setItemId(itemId);
                        newEntity.setCategoryGroup(categoryGroup);
                        newEntity.setSubItemId(null);
                        return newEntity;
                    });
        } else {
            resultEntity = inspectionResultRepo
                    .findFirstBySiteIdAndItemIdAndCategoryGroupAndSubItemIdOrderByIdDesc(
                            siteId,
                            itemId,
                            categoryGroup,
                            normalizedSubItemId
                    )
                    .orElseGet(() -> {
                        InspectionResultEntity newEntity = new InspectionResultEntity();
                        newEntity.setSiteId(siteId);
                        newEntity.setItemId(itemId);
                        newEntity.setCategoryGroup(categoryGroup);
                        newEntity.setSubItemId(normalizedSubItemId);
                        return newEntity;
                    });
        }

        if (resultEntity.getId() != null) {
            List<PhotoEntity> photos = photoRepo.findByResultId(resultEntity.getId());
            for (PhotoEntity photo : photos) {
                fileService.delete(photo.getFileUrl());
                photoRepo.delete(photo);
            }
        }

        resultEntity.setMemo("");

        if ("해당사항없음".equals(targetResult)) {
            resultEntity.setResult("해당사항없음");
        } else {
            resultEntity.setResult("미작성");
        }

        inspectionResultRepo.save(resultEntity);
    }

    private Long normalizeSubItemId(Long subItemId) {
        return (subItemId == null || subItemId == 0) ? null : subItemId;
    }

    @Transactional
    public Map<String, Long> syncOffline(Long siteId, SyncRequest request) {
        Map<String, Long> resultIdMap = new HashMap<>();

        if (request == null || request.getResults() == null) {
            return resultIdMap;
        }

        for (SyncResultItem item : request.getResults()) {
            if (item.getItemId() == null || item.getCategoryGroup() == null) {
                continue;
            }

            Long normalizedSubItemId =
                    (item.getSubItemId() == null || item.getSubItemId() == 0)
                            ? null
                            : item.getSubItemId();

            Optional<InspectionResultEntity> optional;

            if (normalizedSubItemId == null) {
                optional = inspectionResultRepo
                        .findFirstBySiteIdAndItemIdAndCategoryGroupAndSubItemIdIsNullOrderByIdDesc(
                                siteId,
                                item.getItemId(),
                                item.getCategoryGroup()
                        );
            } else {
                optional = inspectionResultRepo
                        .findFirstBySiteIdAndItemIdAndCategoryGroupAndSubItemIdOrderByIdDesc(
                                siteId,
                                item.getItemId(),
                                item.getCategoryGroup(),
                                normalizedSubItemId
                        );
            }

            InspectionResultEntity entity = optional.orElseGet(InspectionResultEntity::new);

            entity.setSiteId(siteId);
            entity.setItemId(item.getItemId());
            entity.setCategoryGroup(item.getCategoryGroup());
            entity.setSubItemId(normalizedSubItemId);
            entity.setResult(item.getResult());
            entity.setMemo(item.getMemo());

            InspectionResultEntity saved = inspectionResultRepo.save(entity);

            if (item.getDraftKey() != null) {
                resultIdMap.put(item.getDraftKey(), saved.getId());
            }
        }

        return resultIdMap;
    }

    @Transactional
    public String renameCategoryGroup(Long siteId, String oldCategoryGroup, String newLocationName) {
        if (oldCategoryGroup == null || oldCategoryGroup.isBlank()) {
            throw new IllegalArgumentException("기존 위치 정보가 없습니다.");
        }

        if (newLocationName == null || newLocationName.trim().isEmpty()) {
            throw new IllegalArgumentException("새 위치명을 입력해주세요.");
        }

        String cleanLocation = newLocationName.trim();

        if (cleanLocation.contains("_")) {
            throw new IllegalArgumentException("위치명에는 _ 문자를 사용할 수 없습니다.");
        }

        int idx = oldCategoryGroup.indexOf("_");
        if (idx < 0) {
            throw new IllegalArgumentException("위치 정보 형식이 올바르지 않습니다.");
        }

        String baseCategory = oldCategoryGroup.substring(0, idx).trim();
        String newCategoryGroup = baseCategory + "_" + cleanLocation;

        int updated = inspectionResultRepo.updateCategoryGroupBySiteId(
                siteId,
                oldCategoryGroup,
                newCategoryGroup
        );

        System.out.println("[CategoryGroup Rename] siteId=" + siteId
                + ", old=" + oldCategoryGroup
                + ", new=" + newCategoryGroup
                + ", updated=" + updated);

        return newCategoryGroup;
    }

    @Transactional
    public void saveSyncedPhoto(Long resultId, MultipartFile photo) {
        if (resultId == null || photo == null || photo.isEmpty()) {
            return;
        }

        String fileUrl = fileService.upload(photo);

        PhotoEntity entity = new PhotoEntity();
        entity.setResultId(resultId);
        entity.setFileUrl(fileUrl);

        photoRepo.save(entity);
    }
}