package com.example.bluetechcloud.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InspectionResultDTO {

    private Long id;
    private Long site_id;
    private Long item_id;
    private String result;
    private String memo;
    private LocalDateTime created_at;
    private String category_group;
    private Long sub_item_id;
}
