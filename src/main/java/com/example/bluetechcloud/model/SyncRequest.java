package com.example.bluetechcloud.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SyncRequest {

    private List<SyncResultItem> results = new ArrayList<>();
    private List<SyncLocationItem> locations = new ArrayList<>();

    public List<SyncResultItem> getResults() {
        return results;
    }

    public void setResults(List<SyncResultItem> results) {
        this.results = results;
    }

    public List<SyncLocationItem> getLocations() {
        return locations;
    }

    public void setLocations(List<SyncLocationItem> locations) {
        this.locations = locations;
    }
}