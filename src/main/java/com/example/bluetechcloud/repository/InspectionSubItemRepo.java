package com.example.bluetechcloud.repository;

import com.example.bluetechcloud.entity.InspectionSubItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InspectionSubItemRepo extends JpaRepository<InspectionSubItemEntity, Long> {

    List<InspectionSubItemEntity> findByItemIdOrderByOrderNoAsc(Long itemId);
}
