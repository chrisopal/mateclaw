package vip.mate.agent.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import vip.mate.agent.model.AgentEntity;

/**
 * Agent 数据访问层
 *
 * @author MateClaw Team
 */
@Mapper
public interface AgentMapper extends BaseMapper<AgentEntity> {
    @Select("SELECT id FROM mate_agent WHERE id = #{id} FOR UPDATE")
    Long lockForUpdate(@Param("id") Long id);
}
