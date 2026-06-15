package com.chiho.wuagentscope;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@MapperScan("com.chiho.wuagentscope.mapper")
@EnableAsync
public class WuAgentScopeApplication {

    public static void main(String[] args) {
        SpringApplication.run(WuAgentScopeApplication.class, args);
    }
}
