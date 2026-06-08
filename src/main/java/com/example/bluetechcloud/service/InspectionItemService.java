package com.example.bluetechcloud.service;

import com.example.bluetechcloud.entity.InspectionItemEntity;
import com.example.bluetechcloud.model.InspectionItemDTO;
import com.example.bluetechcloud.repository.InspectionItemRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class InspectionItemService {

    private final InspectionItemRepo inspectionItemRepo;

    public InspectionItemService(InspectionItemRepo inspectionItemRepo) {
        this.inspectionItemRepo = inspectionItemRepo;
    }

    public List<String> getAllCategoryNames() {
        return inspectionItemRepo.findDistinctCategoryNames();
    }

    @Transactional(readOnly = true)
    public Map<String, List<InspectionItemDTO>> getGroupedItems() {
        List<InspectionItemEntity> list =
                inspectionItemRepo.findAllByOrderByCategoryOrderAscOrderNoAscIdAsc();

        Map<String, List<InspectionItemDTO>> grouped = new LinkedHashMap<>();

        for (InspectionItemEntity entity : list) {
            InspectionItemDTO dto = new InspectionItemDTO();
            dto.setId(entity.getId());
            dto.setCategory(entity.getCategory());
            dto.setCategory_order(entity.getCategoryOrder());
            dto.setCode(entity.getCode());
            dto.setContent(entity.getContent());
            dto.setOrder_no(entity.getOrderNo());

            grouped.computeIfAbsent(entity.getCategory(), k -> new ArrayList<>()).add(dto);
        }

        return grouped;
    }

    @Transactional
    public InspectionItemDTO addCustomItem(String categoryName, String content) {
        if (categoryName == null || categoryName.isBlank()) {
            throw new IllegalArgumentException("카테고리명이 없습니다.");
        }

        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("세부점검사항 내용이 없습니다.");
        }

        List<InspectionItemEntity> sameCategoryItems =
                inspectionItemRepo.findByCategoryOrderByOrderNoAscIdAsc(categoryName);

        InspectionItemEntity entity = new InspectionItemEntity();
        entity.setCategory(categoryName.trim());
        entity.setContent(content.trim());

        int nextOrderNo = sameCategoryItems.isEmpty()
                ? 1
                : sameCategoryItems.get(sameCategoryItems.size() - 1).getOrderNo() + 1;
        entity.setOrderNo(nextOrderNo);

        if (!sameCategoryItems.isEmpty()) {
            entity.setCategoryOrder(sameCategoryItems.get(0).getCategoryOrder());
        } else {
            Integer maxCategoryOrder = inspectionItemRepo.findMaxCategoryOrder();
            entity.setCategoryOrder(maxCategoryOrder == null ? 1 : maxCategoryOrder + 1);
        }

        InspectionItemEntity saved = inspectionItemRepo.save(entity);

        InspectionItemDTO dto = new InspectionItemDTO();
        dto.setId(saved.getId());
        dto.setCategory(saved.getCategory());
        dto.setCategory_order(saved.getCategoryOrder());
        dto.setCode(saved.getCode());
        dto.setContent(saved.getContent());
        dto.setOrder_no(saved.getOrderNo());

        return dto;
    }

    @Transactional
    public void addCategoryByTemplate(Long siteId, String templateCategory, String newCategoryName) {
        List<InspectionItemEntity> templateItems =
                inspectionItemRepo.findByCategoryOrderByOrderNoAscIdAsc(templateCategory);

        if (templateItems == null || templateItems.isEmpty()) {
            throw new IllegalArgumentException("템플릿 카테고리를 찾을 수 없습니다.");
        }

        for (InspectionItemEntity template : templateItems) {
            InspectionItemEntity entity = new InspectionItemEntity();
            entity.setCategory(template.getCategory());
            entity.setCategoryOrder(template.getCategoryOrder());
            entity.setCode(template.getCode());
            entity.setContent(template.getContent());
            entity.setOrderNo(template.getOrderNo());

            inspectionItemRepo.save(entity);
        }
    }
}