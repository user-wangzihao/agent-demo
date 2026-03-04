package com.wzh.entity.dto;

import lombok.Data;

@Data
public class LoginResponse {
    private Long userId;
    private String username;
    private String nickname;
    private String role;
    private String token;
}