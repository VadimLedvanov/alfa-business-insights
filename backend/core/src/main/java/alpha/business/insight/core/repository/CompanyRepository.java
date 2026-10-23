package alpha.business.insight.core.repository;

import alpha.business.insight.core.dto.GroupCompany;
import alpha.business.insight.core.model.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CompanyRepository extends JpaRepository<Company, String> {
    /**
     * Возвращает все компании указанной группы с их отраслями, по возрастанию идентификатора.
     */
    @Query("""
            SELECT c.id, c.profileStatus, c.mainIndustryCode, c.mainIndustryName
            FROM Company c WHERE c.groupId = :groupId ORDER BY c.id
            """)
    List<GroupCompany> findGroup(@Param("groupId") String groupId);
}
