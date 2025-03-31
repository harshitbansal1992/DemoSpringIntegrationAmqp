package com.example.demospringintegrationamqp.controller;

import com.example.demospringintegrationamqp.intflow.MyPojo;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DemoController {

    @Autowired
    RabbitTemplate rabbitTemplate;

    @GetMapping("/get")
    public ResponseEntity<String> getMessage() {
        rabbitTemplate.convertAndSend("migration.rxo", "", new MyPojo("101"));
        return new ResponseEntity(HttpStatus.OK);
    }

}
