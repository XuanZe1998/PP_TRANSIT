package com.transit;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.transit.mapper")
public class ApiTransitStationApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiTransitStationApplication.class, args);
    }

}
