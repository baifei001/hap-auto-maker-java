package com.hap.automaker.core.registry;

import java.util.List;
import java.util.Map;

/**
 * 类型注册中心接口
 */
public interface TypeRegistry<T extends TypedConfig> {

    /**
     * 通过ID获取类型配置
     */
    T getById(int id);

    /**
     * 通过名称获取类型配置
     */
    T getByName(String name);

    /**
     * 通过分类获取类型列表
     */
    List<T> getByCategory(String category);

    /**
     * 获取所有类型
     */
    List<T> getAll();

    /**
     * 验证配置是否合法
     */
    boolean validateConfig(String typeId, Map<String, Object> config);

    /**
     * 生成AI Prompt描述
     */
    String generatePrompt();

    /**
     * 获取类型数量
     */
    int size();
}
