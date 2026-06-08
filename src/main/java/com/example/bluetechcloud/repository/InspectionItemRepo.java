package com.example.bluetechcloud.repository;

import com.example.bluetechcloud.entity.InspectionItemEntity;
import com.example.bluetechcloud.model.CategoryDTO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InspectionItemRepo extends JpaRepository<InspectionItemEntity,Long> {

    List<InspectionItemEntity> findByCategoryOrderByOrderNoAsc(String category);

    @Query("select distinct i.category from InspectionItemEntity i order by min(i.categoryOrder)")
    List<String> findDistinctCategoryNames();

    List<InspectionItemEntity> findAllByOrderByCategoryOrderAscOrderNoAscIdAsc();

    List<InspectionItemEntity> findByCategoryOrderByOrderNoAscIdAsc(String category);

    @Query("select max(i.categoryOrder) from InspectionItemEntity i")
    Integer findMaxCategoryOrder();

    @Query("""
        select new com.example.bluetechcloud.model.CategoryDTO(i.category, min(i.categoryOrder))
        from InspectionItemEntity i
        where (
            (:workType <> '현황표' and i.workType = :workType)
            or
            (:workType = '현황표' and (i.workType is null or trim(i.workType) = ''))
        )
        and i.category is not null
        group by i.category
        order by min(i.categoryOrder) asc
    """)
    List<CategoryDTO> findCategoryListByWorkType(@Param("workType") String workType);

    @Query("""
    select i
    from InspectionItemEntity i
    where i.workType = :workType
      and i.category in :categories
    order by i.categoryOrder asc, i.orderNo asc, i.id asc
""")
    List<InspectionItemEntity> findItemsByWorkTypeAndCategories(
            @Param("workType") String workType,
            @Param("categories") List<String> categories
    );

    @Query("""
        select i
        from InspectionItemEntity i
        where i.id in :itemIds
        order by i.categoryOrder asc, i.orderNo asc
    """)
    List<InspectionItemEntity> findAllByIdInOrderByCategoryOrderAscOrderNoAsc(
            @Param("itemIds") List<Long> itemIds
    );

    @Query("""
    select distinct i.workType
    from InspectionItemEntity i, SiteInspectionItemEntity s
    where s.itemId = i.id
      and s.siteId = :siteId
""")
    List<String> findWorkTypesBySiteId(@Param("siteId") Long siteId);

    @Query("""
    select new com.example.bluetechcloud.model.CategoryDTO(i.category, min(i.categoryOrder))
    from InspectionItemEntity i
    where i.category is not null
    group by i.category
    order by min(i.categoryOrder) asc
""")
    List<CategoryDTO> findCategoryListForStatusTable();

    @Query("""
    select i
    from InspectionItemEntity i
    where i.id in (
        select min(ii.id)
        from InspectionItemEntity ii
        where ii.category in :categories
        group by ii.category
    )
    order by i.categoryOrder asc, i.id asc
""")
    List<InspectionItemEntity> findRepresentativeItemsByCategories(
            @Param("categories") List<String> categories
    );

    @Query("""
    select new com.example.bluetechcloud.model.CategoryDTO(i.category, min(i.categoryOrder))
    from SiteInspectionItemEntity sii, InspectionItemEntity i
    where sii.itemId = i.id
      and sii.siteId = :siteId
      and i.category is not null
    group by i.category
    order by min(i.categoryOrder) asc
""")
    List<CategoryDTO> findSelectedCategoryListBySiteId(@Param("siteId") Long siteId);



}
