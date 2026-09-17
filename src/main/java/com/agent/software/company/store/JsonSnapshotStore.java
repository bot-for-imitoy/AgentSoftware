package com.agent.software.company.store;

import com.agent.software.infra.config.AppPaths;
import com.agent.software.infra.json.JsonCodec;
import com.agent.software.kernel.DomainError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * JSON 快照存储：原子写 state.json，并在读取时兼容旧档。
 */
public final class JsonSnapshotStore implements SnapshotStore {

    private static final Logger logger = LoggerFactory.getLogger(JsonSnapshotStore.class);

    /** 快照文件名（数据目录下）。 */
    public static final String FILE_NAME = "state.json";

    private final AppPaths paths;
    private final JsonCodec json;

    /** 绑定数据路径与 JSON 编解码器。 */
    public JsonSnapshotStore(AppPaths paths, JsonCodec json) {
        this.paths = paths;
        this.json = json;
    }

    /** 快照文件路径。 */
    public Path file() {
        return paths.dataFile(FILE_NAME);
    }

    @Override
    public Optional<CompanySnapshot> load() {
        Path file = file();
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            logger.warn("读取快照失败：{}", e.getMessage());
            return Optional.empty();
        }
        if (text.isBlank()) {
            return Optional.empty();
        }
        try {
            CompanySnapshot snapshot = json.read(text, CompanySnapshot.class);
            return Optional.ofNullable(snapshot);
        } catch (RuntimeException e) {
            // 旧档 / 损坏档一律降级为"从零开始"，不要让读档失败拖垮启动
            logger.warn("快照无法解析（按无档处理）：{}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void save(CompanySnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        Path file = paths.ensure(file());
        String text = json.write(snapshot);
        Path tmp = file.resolveSibling("." + file.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) {
            throw new DomainError("snapshot.write.failed", "写入快照失败: " + file, e);
        }
    }
}
