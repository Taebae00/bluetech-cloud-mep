package com.example.bluetechcloud.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "site_inspection_item")
@Getter
@Setter
public class SiteInspectionItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false)
    private Long siteId;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}