package com.example.bluetechcloud.model;

import lombok.Data;

@Data
public class InspectionSubItemDTO {

    private Long id;
    private Long item_id;
    private String content;
    private int order_no;
}
