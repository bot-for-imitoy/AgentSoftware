package com.agent.software.adapters.config;

import java.nio.file.Path;

/**
 * 所有磁盘路径的唯一来源：解析数据/配置目录并按需创建父目录（XDG/Windows/macOS）。
 */
public final class AppPaths {

    /** 由存储配置解析出跨平台路径集合。 */
    public static AppPaths resolve(AppConfig.Storage storage) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 数据根目录。 */
    public Path dataDir() {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 数据根目录下拼接出的文件路径。 */
    public Path dataFile(String... parts) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 配置目录下拼接出的文件路径。 */
    public Path configFile(String... parts) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 确保路径的父目录存在并返回该路径。 */
    public Path ensure(Path path) {
        throw new UnsupportedOperationException("skeleton");
    }
}
