package com.example.bluetechcloud.model;

import lombok.Data;

@Data
public class SyncResultItem {

    private String draftKey;
    private Long siteId;
    private Long itemId;
    private String categoryGroup;
    private String result;
    private String memo;

    public void setDraftKey(String draftKey) {
        this.draftKey = draftKey;
    }

    public void setSiteId(Long siteId) {
        this.siteId = siteId;
    }

    public void setItemId(Long itemId) {
        this.itemId = itemId;
    }

    public void setCategoryGroup(String categoryGroup) {
        this.categoryGroup = categoryGroup;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public void setMemo(String memo) {
        this.memo = memo;
    }
}