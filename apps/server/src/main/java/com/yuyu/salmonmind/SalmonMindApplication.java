package com.yuyu.salmonmind;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SalmonMind server bootstrap. Business behavior belongs to application modules.
 */
@SpringBootApplication
public class SalmonMindApplication {

    public static void main(String[] args) {
        SpringApplication.run(SalmonMindApplication.class, args);
    }
}
