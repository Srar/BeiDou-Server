package org.gms.test;

import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.service.ConfigService;
import org.mockito.Mockito;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;
import org.springframework.context.support.StaticMessageSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bot 框架单元测试的基础设施（测试专用）。
 * <p>
 * gms-server 大量遗留类（Character/CommandsExecutor/I18nUtil/CharsetConstants/GameConfig 等）在
 * 静态初始化时通过 {@code ServerManager.getApplicationContext()} 获取 Spring bean——纯单元测试
 * 没有 Spring 容器，类一加载就会 ExceptionInInitializerError。本类通过反射把
 * applicationContext 替换为 Mockito mock：
 * <ul>
 *   <li>任何按类型/按名字取 bean 都返回对应类型的 mock（ServiceProperty 固定 zh-CN、
 *       ConfigService 返回空配置、三个 MessageSource 装载真实 i18n 资源文件——
 *       顺带保证生产代码引用的每个 bot.* i18n key 都存在且可解析）；</li>
 *   <li>初始化幂等：每个测试类的 @BeforeAll 调用即可，谁先跑谁初始化。</li>
 * </ul>
 * 注意：JVM 内一旦某个类在初始化完成前被加载（静态引用），补救无效——因此任何会触碰
 * 上述类的测试都必须在 @BeforeAll 里最先调用 {@link #initialize()}，且测试类的字段
 * 初始化不得提前触碰这些类。
 */
public final class BotTestSupport {

    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    private BotTestSupport() {
    }

    public static synchronized void initialize() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }

        ApplicationContext ctx = Mockito.mock(ApplicationContext.class);

        // 通用回答：任何未知 bean 都返回对应类型的 mock
        Mockito.when(ctx.getBean(Mockito.any(Class.class)))
                .thenAnswer(inv -> Mockito.mock((Class<?>) inv.getArgument(0)));
        Mockito.when(ctx.getBean(Mockito.anyString(), Mockito.any(Class.class)))
                .thenAnswer(inv -> Mockito.mock((Class<?>) inv.getArgument(1)));

        // ServiceProperty：语言 zh-CN（I18nUtil/CharsetConstants 静态初始化需要）
        ServiceProperty serviceProperty = Mockito.mock(ServiceProperty.class);
        Mockito.when(serviceProperty.getLanguage()).thenReturn("zh-CN");
        Mockito.when(ctx.getBean(ServiceProperty.class)).thenReturn(serviceProperty);

        // ConfigService：空配置（GameConfig 静态初始化会调用 loadGameConfigs；空表→所有取值回落默认）
        ConfigService configService = Mockito.mock(ConfigService.class);
        Mockito.when(configService.loadGameConfigs()).thenReturn(Collections.emptyList());
        Mockito.when(ctx.getBean(ConfigService.class)).thenReturn(configService);

        // MessageSource：装载真实 i18n 资源（zh-CN），bot.* key 缺一不可
        Mockito.when(ctx.getBean("messageSource", MessageSource.class))
                .thenReturn(loadSource("i18n/message_zh_CN.properties"));
        Mockito.when(ctx.getBean("logSource", MessageSource.class))
                .thenReturn(loadSource("i18n/log_zh_CN.properties"));
        Mockito.when(ctx.getBean("exceptionSource", MessageSource.class))
                .thenReturn(loadSource("i18n/exception_zh_CN.properties"));

        setStaticField(ServerManager.class, "applicationContext", ctx);
    }

    private static MessageSource loadSource(String resource) {
        StaticMessageSource source = new StaticMessageSource();
        Properties props = new Properties();
        try (InputStream is = BotTestSupport.class.getClassLoader().getResourceAsStream(resource)) {
            if (is == null) {
                throw new IllegalStateException("找不到类路径资源: " + resource);
            }
            props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("无法加载 " + resource, e);
        }
        for (String key : props.stringPropertyNames()) {
            String value = props.getProperty(key);
            source.addMessage(key, Locale.SIMPLIFIED_CHINESE, value);
            source.addMessage(key, Locale.ROOT, value);
        }
        return source;
    }

    private static void setStaticField(Class<?> clazz, String name, Object value) {
        try {
            Field field = clazz.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法设置静态字段 " + clazz.getName() + "." + name, e);
        }
    }
}
