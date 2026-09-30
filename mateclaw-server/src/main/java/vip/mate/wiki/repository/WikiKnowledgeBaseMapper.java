package vip.mate.wiki.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;

/**
 * Wiki 知识库 Mapper
 *
 * @author MateClaw Team
 */
@Mapper
public interface WikiKnowledgeBaseMapper extends BaseMapper<WikiKnowledgeBaseEntity> {
    @Select("SELECT id FROM mate_wiki_knowledge_base WHERE id = #{id} FOR UPDATE")
    Long lockForUpdate(@Param("id") Long id);
}
