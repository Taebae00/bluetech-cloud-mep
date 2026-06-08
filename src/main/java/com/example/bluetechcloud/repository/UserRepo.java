package com.example.bluetechcloud.repository;

import com.example.bluetechcloud.entity.UserEntity;
import com.example.bluetechcloud.model.AdminUserDTO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface UserRepo extends JpaRepository<UserEntity, Long> {

    UserEntity findByUsername(String username);

    UserEntity findByRememberToken(String rememberToken);

    @Query(value = """
        select 
            u.id as id,
            u.username as username,
            u.name as name,
            u.role as role,
            u.created_at as createdAt,
            count(s.id) as siteCount
        from user u
        left join site s on s.created_by = u.id
        group by u.id, u.username, u.name, u.role, u.created_at
        order by u.id desc
    """, nativeQuery = true)
    List<AdminUserDTO> findAdminUserList();

    @Modifying
    @Query(value = """
        delete p from photo p
        join inspection_result r on p.result_id = r.id
        join site s on r.site_id = s.id
        where s.created_by = :userId
    """, nativeQuery = true)
    void deletePhotosByUserId(Long userId);

    @Modifying
    @Query(value = """
        delete r from inspection_result r
        join site s on r.site_id = s.id
        where s.created_by = :userId
    """, nativeQuery = true)
    void deleteResultsByUserId(Long userId);

    @Modifying
    @Query(value = """
        delete sii from site_inspection_item sii
        join site s on sii.site_id = s.id
        where s.created_by = :userId
    """, nativeQuery = true)
    void deleteSiteInspectionItemsByUserId(Long userId);

    @Modifying
    @Query(value = "delete from site where created_by = :userId", nativeQuery = true)
    void deleteSitesByUserId(Long userId);
}