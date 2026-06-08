package com.example.bluetechcloud.repository;

import com.example.bluetechcloud.entity.InspectionResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InspectionResultRepo extends JpaRepository<InspectionResultEntity, Long> {

    List<InspectionResultEntity> findBySiteId(Long siteId);

    Optional<InspectionResultEntity> findFirstBySiteIdAndItemIdAndCategoryGroupOrderByIdDesc(
            Long siteId, Long itemId, String categoryGroup
    );

    boolean existsBySiteIdAndItemIdAndCategoryGroup(Long siteId, Long itemId, String categoryGroup);

    void deleteBySiteId(Long siteId);

    Optional<InspectionResultEntity> findFirstBySiteIdAndItemIdOrderByIdDesc(Long siteId, Long itemId);

    Optional<InspectionResultEntity> findBySiteIdAndItemIdAndCategoryGroup(
            Long siteId, Long itemId, String categoryGroup
    );

    List<InspectionResultEntity> findBySiteIdAndCategoryGroup(Long siteId, String categoryGroup);

    void deleteBySiteIdAndCategoryGroup(Long siteId, String categoryGroup);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update InspectionResultEntity r
           set r.categoryGroup = :newCategoryGroup
         where r.siteId = :siteId
           and r.categoryGroup = :oldCategoryGroup
    """)
    int updateCategoryGroupBySiteId(
            @Param("siteId") Long siteId,
            @Param("oldCategoryGroup") String oldCategoryGroup,
            @Param("newCategoryGroup") String newCategoryGroup
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
    update inspection_result
       set category_group = concat(
           substring_index(category_group, '_', 1),
           '_',
           :newLocationName
       )
     where site_id = :siteId
       and substring_index(category_group, '_', -1) = :oldLocationName
""", nativeQuery = true)
    int updateLocationNameBySiteId(
            @Param("siteId") Long siteId,
            @Param("oldLocationName") String oldLocationName,
            @Param("newLocationName") String newLocationName
    );
}