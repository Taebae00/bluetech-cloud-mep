package com.example.bluetechcloud.model;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class SiteCreateRequest {
    private String siteName;
    private String workType;
    private List<String> categories;
}