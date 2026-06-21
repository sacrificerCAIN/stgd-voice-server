package com.stgd.voice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stgd.voice.entity.IpBlacklist;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface IpBlacklistMapper extends BaseMapper<IpBlacklist> {

    /** 查询所有黑名单记录 */
    List<IpBlacklist> selectAll();

    /** 按 IP 查询记录（用于唯一性检查） */
    List<IpBlacklist> selectByIp(String ip);
}