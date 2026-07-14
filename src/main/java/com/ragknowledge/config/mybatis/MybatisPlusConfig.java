package com.ragknowledge.config.mybatis;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 拦截器与分页插件配置
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Configuration
@MapperScan(basePackages = {
        MybatisPlusConfig.KNOWLEDGE_MAPPER_PACKAGE,
        MybatisPlusConfig.DOCUMENT_MAPPER_PACKAGE,
        MybatisPlusConfig.CHAT_MAPPER_PACKAGE
})
public class MybatisPlusConfig {

    public static final String KNOWLEDGE_MAPPER_PACKAGE = "com.ragknowledge.knowledge.mapper";
    public static final String DOCUMENT_MAPPER_PACKAGE = "com.ragknowledge.document.mapper";
    public static final String CHAT_MAPPER_PACKAGE = "com.ragknowledge.chat.mapper";

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        // 统一注册 MyBatis-Plus 插件，后续需要乐观锁或多租户插件时从这里集中扩展。
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // PostgreSQL 分页 SQL 与其他数据库不同，必须显式指定 DbType，避免分页方言误判。
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }
}
