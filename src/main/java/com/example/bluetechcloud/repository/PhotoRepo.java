package com.example.bluetechcloud.repository;

import com.example.bluetechcloud.entity.PhotoEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface PhotoRepo extends JpaRepository<PhotoEntity, Long> {

    List<PhotoEntity> findByResultId(Long resultId);

    void deleteByResultId(Long resultId);

    List<PhotoEntity> findAll();

    List<PhotoEntity> findByResultIdIn(Collection<Long> resultIds);

}
