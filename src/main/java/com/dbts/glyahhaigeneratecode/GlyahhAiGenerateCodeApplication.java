package com.dbts.glyahhaigeneratecode;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@MapperScan("com.dbts.glyahhaigeneratecode.mapper")
@EnableCaching
public class GlyahhAiGenerateCodeApplication {

    public static void main(String[] args) {
        SpringApplication.run(GlyahhAiGenerateCodeApplication.class, args);
    }
}
