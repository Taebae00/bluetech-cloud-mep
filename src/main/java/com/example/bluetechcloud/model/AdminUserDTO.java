package com.example.bluetechcloud.model;

import java.time.LocalDateTime;

public interface AdminUserDTO {

    Long getId();

    String getUsername();

    String getName();

    String getRole();

    LocalDateTime getCreatedAt();

    Long getSiteCount();
}