package com.stgd.voice.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 1. @description:
 * 2. @author: zrj
 * 3. @time: 2025/7/6
 */
@Data
@TableName("admin_id")
public class AdminUser {
    private Integer id;

    private String username;

    private String password;
}
