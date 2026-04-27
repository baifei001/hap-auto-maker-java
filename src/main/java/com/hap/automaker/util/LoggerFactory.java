package com.hap.automaker.util;

import org.slf4j.Logger;

/**
 * 日志工厂
 * 提供统一的日志记录接口
 */
public final class LoggerFactory {

    private LoggerFactory() {}

    /**
     * 获取Logger实例
     */
    public static Logger getLogger(Class<?> clazz) {
        return org.slf4j.LoggerFactory.getLogger(clazz);
    }

    /**
     * 获取Logger实例（按名称）
     */
    public static Logger getLogger(String name) {
        return org.slf4j.LoggerFactory.getLogger(name);
    }
}
