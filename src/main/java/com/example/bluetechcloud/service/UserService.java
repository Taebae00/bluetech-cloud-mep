package com.example.bluetechcloud.service;

import com.example.bluetechcloud.entity.UserEntity;
import com.example.bluetechcloud.model.AdminUserDTO;
import com.example.bluetechcloud.model.UserDTO;
import com.example.bluetechcloud.repository.UserRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UserService {

    private final UserRepo userRepo;

    public UserService(UserRepo userRepo) {
        this.userRepo = userRepo;
    }

    public UserDTO loginCheck(String id, String password) {

        UserEntity dto = userRepo.findByUsername(id);

        if (dto == null) {
            return null;
        } else if (!dto.getPassword().equals(password)) {
            return null;
        } else {

            UserDTO userDTO = new UserDTO();

            userDTO.setId(dto.getId());
            userDTO.setUsername(dto.getUsername());
            userDTO.setPassword(dto.getPassword());
            userDTO.setName(dto.getName());
            userDTO.setRole(dto.getRole());
            userDTO.setCreated_at(dto.getCreatedAt());

            return userDTO;
        }
    }

    public long getUserCount() {
        return userRepo.count();
    }

    public List<AdminUserDTO> getAdminUserList() {
        return userRepo.findAdminUserList();
    }

    @Transactional
    public void createUser(String username, String password, String name, String role) {

        UserEntity checkUser = userRepo.findByUsername(username);

        if (checkUser != null) {
            throw new IllegalArgumentException("이미 존재하는 아이디입니다.");
        }

        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPassword(password);
        user.setName(name);
        user.setRole(role);

        userRepo.save(user);
    }

    @Transactional
    public void updateUser(Long id, String username, String name, String role) {

        UserEntity user = userRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("회원이 존재하지 않습니다."));

        UserEntity checkUser = userRepo.findByUsername(username);

        if (checkUser != null && !checkUser.getId().equals(id)) {
            throw new IllegalArgumentException("이미 존재하는 아이디입니다.");
        }

        user.setUsername(username);
        user.setName(name);
        user.setRole(role);
    }

    @Transactional
    public void updatePassword(Long id, String password) {

        UserEntity user = userRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("회원이 존재하지 않습니다."));

        user.setPassword(password);
    }

    @Transactional
    public void deleteUser(Long id) {

        UserEntity user = userRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("회원이 존재하지 않습니다."));

        if ("ADMIN".equalsIgnoreCase(user.getRole())) {
            throw new IllegalArgumentException("관리자는 삭제할 수 없습니다.");
        }

        userRepo.deletePhotosByUserId(id);
        userRepo.deleteResultsByUserId(id);
        userRepo.deleteSiteInspectionItemsByUserId(id);
        userRepo.deleteSitesByUserId(id);
        userRepo.deleteById(id);
    }
}