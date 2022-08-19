package org.meveo.model.customEntities;

import org.meveo.model.CustomEntity;
import java.util.List;
import org.meveo.model.persistence.DBStorageType;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import com.fasterxml.jackson.annotation.JsonIgnore;

public class WebApplication implements CustomEntity {

    public WebApplication() {
    }

    public WebApplication(String uuid) {
        this.uuid = uuid;
    }

    private String uuid;

    @JsonIgnore()
    private DBStorageType storages;

    @JsonProperty(required = true)
    private String code;

    private List<String> entities = new ArrayList<>();

    private String ROOT_PATH;

    private String label;

    private String BASE_URL;

    @Override()
    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public DBStorageType getStorages() {
        return storages;
    }

    public void setStorages(DBStorageType storages) {
        this.storages = storages;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public List<String> getEntities() {
        return entities;
    }

    public void setEntities(List<String> entities) {
        this.entities = entities;
    }

    public String getROOT_PATH() {
        return ROOT_PATH;
    }

    public void setROOT_PATH(String ROOT_PATH) {
        this.ROOT_PATH = ROOT_PATH;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getBASE_URL() {
        return BASE_URL;
    }

    public void setBASE_URL(String BASE_URL) {
        this.BASE_URL = BASE_URL;
    }

    @Override()
    public String getCetCode() {
        return "WebApplication";
    }
}
