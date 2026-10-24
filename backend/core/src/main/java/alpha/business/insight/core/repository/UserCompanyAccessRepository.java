package alpha.business.insight.core.repository;

import alpha.business.insight.core.model.UserCompanyAccess;
import alpha.business.insight.core.model.UserCompanyAccessId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserCompanyAccessRepository extends JpaRepository<UserCompanyAccess, UserCompanyAccessId> {
    @Query(value = "SELECT EXISTS (SELECT 1 FROM user_company_access WHERE user_id = :userId AND company_id = :companyId)",
            nativeQuery = true)
    boolean hasAccess(@Param("userId") String userId, @Param("companyId") String companyId);
}
