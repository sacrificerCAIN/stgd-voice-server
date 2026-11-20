package com.stgd.voice.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 1. @description:
 * 2. @author: zrj
 * 3. @time: 2025/7/6
 */
@Data
@TableName("admin_user")
public class AdminUser {
    private Integer id;

    private String username;

    private String password;
}
