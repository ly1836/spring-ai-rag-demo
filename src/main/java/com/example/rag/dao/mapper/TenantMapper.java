package com.example.rag.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.rag.dao.entity.TenantEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 租户。Mapper。
 */
public interface TenantMapper extends BaseMapper<TenantEntity> {

	/**
	 * 锁定当前租户行，串行化默认知识库切换。
	 *
	 * @param entCode 租户编码
	 * @return 租户主键，不存在时为空
	 */
	@Select("SELECT id FROM a_tenant WHERE ent_code = #{entCode} FOR UPDATE")
	Long selectIdForUpdate(@Param("entCode") String entCode);
}
