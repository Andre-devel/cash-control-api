package com.cashcontrol.api.storage;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface StoragePort {

    String store(MultipartFile file);

    Resource load(String storageKey);

    void delete(String storageKey);
}
