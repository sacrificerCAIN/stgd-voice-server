package com.stgd.voice.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 黑名单 IP 实体类。
 */
@Data
@TableName("ip_blacklist")
public class IpBlacklist {
    private Integer id;

    /** 黑名单 IP（支持 IPv4 / IPv6 / 带端口的远程地址字符串） */
    private String ip;

    /** 备注（添加原因） */
    private String remark;

    /** 创建时间（毫秒时间戳） */
    private Long createTime;
}