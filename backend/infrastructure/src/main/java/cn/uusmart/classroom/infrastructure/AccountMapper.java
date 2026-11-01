package cn.uusmart.classroom.infrastructure;

import cn.uusmart.classroom.domain.Account;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AccountMapper {
  @Select(
      """
        SELECT a.id, a.tenant_id, a.username, a.password_hash, a.role
        FROM school_account a JOIN tenant t ON t.id = a.tenant_id
        WHERE t.code = #{schoolCode} AND a.username = #{username}
          AND a.enabled = 1 AND t.enabled = 1
        """)
  Account findActive(@Param("schoolCode") String schoolCode, @Param("username") String username);

  @Select(
      """
        SELECT a.id, a.tenant_id, a.username, a.password_hash, a.role
        FROM school_account a JOIN tenant t ON t.id = a.tenant_id
        WHERE a.id = #{id} AND a.enabled = 1 AND t.enabled = 1
        """)
  Account findActiveById(@Param("id") long id);
}
