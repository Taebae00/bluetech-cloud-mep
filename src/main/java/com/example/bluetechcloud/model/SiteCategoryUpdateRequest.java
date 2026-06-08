package com.example.bluetechcloud.model;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class SiteCategoryUpdateRequest {
    private Long siteId;
    private List<String> categories;
}