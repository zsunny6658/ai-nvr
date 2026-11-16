package com.invr.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring 组件构造器契约测试（v1.2.19 教训）。
 *
 * <p>v1.2.19 发布时 {@code NotifyService} 因存在多个构造器且无 {@code @Autowired}
 * 标注，Spring 回退寻找无参构造器失败，导致应用在 NAS 上启动即崩溃（本地单测直接
 * new 实例，没有暴露装配问题）。本测试扫描全部组件类，保证：</p>
 * <ul>
 *   <li>构造器只有一个 → 合法（Spring 唯一构造器自动注入）；</li>
 *   <li>存在无参构造器 → 合法（Spring 走无参 + 字段注入）；</li>
 *   <li>其余（多构造器且无无参、无 @Autowired）→ 禁止，必须显式标注注入入口。</li>
 * </ul>
 */
class SpringComponentContractTest {

    @Test
    void everySpringComponentHasResolvableConstructor() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        // @RestController / @Service / @Repository / @Configuration 都是 @Component 的派生注解
        Set<org.springframework.beans.factory.config.BeanDefinition> beans =
                scanner.findCandidateComponents("com.invr");

        List<String> violations = new ArrayList<>();
        List<String> checked = new ArrayList<>();
        for (org.springframework.beans.factory.config.BeanDefinition bd : beans) {
            String className = bd.getBeanClassName();
            Class<?> clazz = Class.forName(className);
            if (Modifier.isAbstract(clazz.getModifiers())) {
                continue;
            }
            checked.add(clazz.getSimpleName());
            Constructor<?>[] ctors = clazz.getDeclaredConstructors();
            if (ctors.length == 1) {
                continue;
            }
            boolean hasNoArg = false;
            boolean hasAutowired = false;
            for (Constructor<?> c : ctors) {
                if (c.getParameterCount() == 0) {
                    hasNoArg = true;
                }
                if (c.isAnnotationPresent(Autowired.class)) {
                    hasAutowired = true;
                }
            }
            if (!hasNoArg && !hasAutowired) {
                violations.add(clazz.getName() + "：多构造器但既无无参构造器也无 @Autowired 标注"
                        + "（v1.2.19 NotifyService 同款问题会导致启动即崩）");
            }
        }
        assertTrue(checked.size() > 10, "组件扫描应命中全部业务组件，实际 " + checked.size());
        assertTrue(violations.isEmpty(), "存在无法解析构造器的 Spring 组件：\n"
                + String.join("\n", violations));
    }
}
