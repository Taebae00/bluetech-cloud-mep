package com.example.bluetechcloud.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "inspection_result")
public class InspectionResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "site_id")
    private Long siteId;

    @Column(name = "item_id")
    private Long itemId;

    @Column(name = "result")
    private String result;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String memo;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "category_group")
    private String categoryGroup;

    @Column(name = "sub_item_id")
    private Long subItemId;
}
