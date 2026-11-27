package com.travelassistant.server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication(exclude = org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class)
public class TravelServerApplication {
 public static void main(String[] args) { SpringApplication.run(TravelServerApplication.class, args); }
}
