package com.dbts.glyahhaigeneratecode;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 只在显式外部环境任务中执行，不能进入默认 PR 测试。 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Test
@Tag("external")
public @interface ExternalTest { }
