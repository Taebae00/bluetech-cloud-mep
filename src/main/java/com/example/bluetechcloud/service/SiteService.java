package com.example.bluetechcloud.service;

import com.example.bluetechcloud.entity.InspectionResultEntity;
import com.example.bluetechcloud.entity.PhotoEntity;
import com.example.bluetechcloud.entity.SiteEntity;
import com.example.bluetechcloud.model.SiteDTO;
import com.example.bluetechcloud.repository.InspectionResultRepo;
import com.example.bluetechcloud.repository.PhotoRepo;
import com.example.bluetechcloud.repository.SiteInspectionItemRepo;
import com.example.bluetechcloud.repository.SiteRepo;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
public class SiteService {

    private final SiteRepo siteRepo;
    private final InspectionResultRepo inspectionResultRepo;
    private final PhotoRepo photoRepo;
    private final FileService fileService;
    private final SiteInspectionItemRepo siteInspectionItemRepo;

    public SiteService(SiteRepo siteRepo,
                       InspectionResultRepo inspectionResultRepo,
                       PhotoRepo photoRepo,
                       FileService fileService, SiteInspectionItemRepo siteInspectionItemRepo) {
        this.siteRepo = siteRepo;
        this.inspectionResultRepo = inspectionResultRepo;
        this.photoRepo = photoRepo;
        this.fileService = fileService;
        this.siteInspectionItemRepo = siteInspectionItemRepo;
    }

    public List<SiteDTO> getList(Long userId){

        List<SiteEntity> list = siteRepo.findByCreatedByOrderByIdDesc(userId);
        List<SiteDTO> siteList = new ArrayList<>();

        for (SiteEntity entity : list) {
            SiteDTO dto = new SiteDTO();

            dto.setId(entity.getId());
            dto.setSite_name(entity.getSiteName());
            dto.setWork_date(entity.getWork_date());
            dto.setCreated_by(entity.getCreatedBy());
            dto.setCreated_at(entity.getCreatedAt());

            siteList.add(dto);
        }

        return siteList;
    }

    public SiteDTO addSite(String siteName, Long userId) {
        SiteEntity entity = new SiteEntity();
        entity.setSiteName(siteName);
        entity.setCreatedBy(userId);
        entity.setWork_date(LocalDate.now().atStartOfDay());

        SiteEntity saved = siteRepo.save(entity);

        SiteDTO dto = new SiteDTO();
        dto.setId(saved.getId());
        dto.setSite_name(saved.getSiteName());
        dto.setWork_date(saved.getWork_date());
        dto.setCreated_by(saved.getCreatedBy());
        dto.setCreated_at(saved.getCreatedAt());

        return dto;
    }

    public SiteDTO getSite(Long siteId) {
        SiteEntity entity = siteRepo.findById(siteId)
                .orElseThrow(() -> new RuntimeException("현장을 찾을 수 없습니다."));

        SiteDTO dto = new SiteDTO();
        dto.setId(entity.getId());
        dto.setSite_name(entity.getSiteName());
        dto.setWork_date(entity.getWork_date());
        dto.setCreated_by(entity.getCreatedBy());
        dto.setCreated_at(entity.getCreatedAt());

        return dto;
    }

    @Transactional
    public void deleteSite(Long siteId) {
        List<InspectionResultEntity> results = inspectionResultRepo.findBySiteId(siteId);

        for (InspectionResultEntity result : results) {
            List<PhotoEntity> photos = photoRepo.findByResultId(result.getId());

            for (PhotoEntity photo : photos) {
                fileService.delete(photo.getFileUrl());
            }

            photoRepo.deleteByResultId(result.getId());
        }

        inspectionResultRepo.deleteBySiteId(siteId);
        siteInspectionItemRepo.deleteBySiteId(siteId);
        siteRepo.deleteById(siteId);
    }
}