package com.chiho.wuagentscope;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.chiho.wuagentscope.mapper")
public class WuAgentScopeApplication {

    public static void main(String[] args) {
        SpringApplication.run(WuAgentScopeApplication.class, args);
    }
}
