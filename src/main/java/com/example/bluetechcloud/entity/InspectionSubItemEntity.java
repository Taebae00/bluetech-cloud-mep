package com.example.bluetechcloud.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "inspection_sub_item")
public class InspectionSubItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "item_id")
    private Long itemId;

    @Column(name = "content")
    private String content;

    @Column(name = "order_no")
    private int orderNo;
}
