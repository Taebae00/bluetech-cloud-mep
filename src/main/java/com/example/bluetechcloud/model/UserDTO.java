package com.example.bluetechcloud.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserDTO {

    private Long id;
    private String username;
    private String password;
    private String name;
    private String role;
    private LocalDateTime created_at;
    private String remember_token;
    private LocalDateTime remember_token_expiry;
}
