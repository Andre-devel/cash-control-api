package com.cashcontrol.api.storage;

import org.springframework.web.multipart.MultipartFile;

public interface StoragePort {

    String store(MultipartFile file);

    void delete(String storageKey);
}
