package com.stgd.voice.entity;

import lombok.Data;

/**
 * 1. @description:
 * 2. @author: zrj
 * 3. @time: 2025/7/6
 */
@Data
public class Result {

    private String message;

    private Integer type;

    public  Result() {
    }

    public Result(String message, Integer type) {
        this.message = message;
        this.type = type;
    }
}
