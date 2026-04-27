package com.hap.automaker.core.executor;

import com.hap.automaker.api.HapApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AppCreator 测试类
 */
class AppCreatorTest {

    private static final Logger logger = LoggerFactory.getLogger(AppCreatorTest.class);

    private AppCreator appCreator;
    private HapApiClient apiClient;

    @BeforeEach
    void setUp() {
        apiClient = new HapApiClient();
        appCreator = new AppCreator(apiClient);
    }

    @Test
    void testColorPoolSize() {
        // 验证主题色池有50种颜色（完整的 DEFAULT_COLOR_POOL）
        assertEquals(50, AppCreator.COLOR_POOL.length,
            "Color pool should have 50 theme colors matching Python DEFAULT_COLOR_POOL");
    }

    @Test
    void testGetRandomColor() {
        String color1 = appCreator.getRandomColor();
        String color2 = appCreator.getRandomColor();

        // 验证返回有效的 HEX 颜色格式
        assertNotNull(color1);
        assertTrue(color1.startsWith("#"), "Color should start with #");
        assertEquals(7, color1.length(), "Color should be 7 chars (e.g., #FF9800)");

        // 随机性验证（两次调用可能不同，但概率极低相同）
        // 这里只验证格式正确
    }

    @Test
    void testInputCreation() {
        AppCreator.Input input = new AppCreator.Input(
            "测试应用",
            "这是一个测试应用",
            "sys_0_lego",
            "#2196F3",
            "默认分组"
        );

        assertEquals("测试应用", input.getName());
        assertEquals("这是一个测试应用", input.getDescription());
        assertEquals("sys_0_lego", input.getIcon());
        assertEquals("#2196F3", input.getIconColor());
        assertEquals("默认分组", input.getGroupName());
    }

    @Test
    void testInputWithMinimalParams() {
        AppCreator.Input input = new AppCreator.Input(
            "测试应用",
            "这是一个测试应用"
        );

        assertEquals("测试应用", input.getName());
        assertEquals("这是一个测试应用", input.getDescription());
        assertNull(input.getIcon());
        assertNull(input.getIconColor());
        assertNull(input.getGroupName());
    }

    @Test
    void testSaveAppAuthorization(@TempDir Path tempDir) throws Exception {
        // 准备测试数据
        String appId = "test-app-id-12345";
        String appName = "测试应用";
        String appKey = "testAppKey";
        String sign = "testSign";
        String groupId = "group-123";
        String groupName = "测试分组";

        // 调用保存方法
        Path authFile = appCreator.saveAppAuthorization(
            appId, appName, appKey, sign, tempDir, groupId, groupName);

        // 验证文件创建
        assertTrue(Files.exists(authFile), "Authorization file should be created");
        assertTrue(authFile.getFileName().toString().startsWith("app_authorize_"),
            "File name should start with app_authorize_");
        assertTrue(authFile.getFileName().toString().endsWith(".json"),
            "File should have .json extension");

        // 验证文件内容
        String content = Files.readString(authFile);
        assertTrue(content.contains(appId), "Content should contain appId");
        assertTrue(content.contains(appName), "Content should contain appName");
        assertTrue(content.contains(appKey), "Content should contain appKey");
        assertTrue(content.contains(sign), "Content should contain sign");
        assertTrue(content.contains(groupId), "Content should contain groupId");
        assertTrue(content.contains(groupName), "Content should contain groupName");
        assertTrue(content.contains("createdAt"), "Content should contain createdAt");

        logger.info("✓ App authorization file saved: {}", authFile);
    }

    @Test
    void testOutputCreation() {
        AppCreator.Output output = new AppCreator.Output(
            "app-123",
            "测试应用",
            "appKey_xyz",
            "sign_abc",
            true,
            null,
            java.util.Map.of("id", "app-123", "name", "测试应用"),
            Path.of("data/outputs/test.json")
        );

        assertEquals("app-123", output.getAppId());
        assertEquals("测试应用", output.getAppName());
        assertEquals("appKey_xyz", output.getAppKey());
        assertEquals("sign_abc", output.getSign());
        assertTrue(output.isSuccess());
        assertNull(output.getErrorMessage());
        assertNotNull(output.getRawResponse());
        assertNotNull(output.getAuthFile());
    }

    @Test
    void testOutputFailure() {
        AppCreator.Output output = new AppCreator.Output(
            null,
            null,
            null,
            null,
            false,
            "创建失败：缺少 projectId",
            null,
            null
        );

        assertFalse(output.isSuccess());
        assertEquals("创建失败：缺少 projectId", output.getErrorMessage());
        assertNull(output.getAppId());
    }

    @Test
    void testExecuteWithoutAuth() {
        // 测试未配置组织认证时的异常
        AppCreator.Input input = new AppCreator.Input(
            "测试应用", "描述");

        Exception exception = assertThrows(ExecutorException.class, () -> {
            appCreator.execute(input);
        });

        assertTrue(exception.getMessage().contains("Failed to create app") ||
                   exception.getCause() != null);
    }

    @Test
    void testGetName() {
        assertEquals("AppCreator", appCreator.getName());
    }

    @Test
    void testGetRandomIcon() {
        String icon = appCreator.getRandomIcon();
        assertNotNull(icon);
        assertTrue(icon.startsWith("sys_"));
    }
}
