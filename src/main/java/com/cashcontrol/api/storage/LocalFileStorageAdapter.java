package com.cashcontrol.api.storage;

import com.cashcontrol.api.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Slf4j
@Component
public class LocalFileStorageAdapter implements StoragePort {

    private final Path storageRoot;

    public LocalFileStorageAdapter(AppProperties appProperties) {
        this.storageRoot = Paths.get(appProperties.getStorage().getLocalPath()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.storageRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create local storage directory: " + this.storageRoot, e);
        }
    }

    @Override
    public String store(MultipartFile file) {
        String storageKey = UUID.randomUUID().toString();
        Path target = storageRoot.resolve(storageKey);
        try {
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store file", e);
        }
        return storageKey;
    }

    @Override
    public void delete(String storageKey) {
        Path target = storageRoot.resolve(storageKey);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            log.warn("Failed to delete storage key={}", storageKey, e);
        }
    }
}
