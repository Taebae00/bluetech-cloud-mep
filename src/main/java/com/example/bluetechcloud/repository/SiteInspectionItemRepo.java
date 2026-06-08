package com.example.bluetechcloud.repository;

import com.example.bluetechcloud.entity.SiteInspectionItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SiteInspectionItemRepo extends JpaRepository<SiteInspectionItemEntity, Long> {

    List<SiteInspectionItemEntity> findBySiteId(Long siteId);
    void deleteBySiteId(Long siteId);

    @Query("""
    select distinct i.workType
    from SiteInspectionItemEntity sii, InspectionItemEntity i
    where sii.itemId = i.id
      and sii.siteId = :siteId
""")
    List<String> findWorkTypesBySiteId(@Param("siteId") Long siteId);

    
}
