package alpha.business.insight.core.repository;

import alpha.business.insight.core.dto.GroupCompany;
import alpha.business.insight.core.dto.CompanyListItem;
import alpha.business.insight.core.model.Company;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CompanyRepository extends JpaRepository<Company, String> {
    @Query("""
            SELECT new alpha.business.insight.core.dto.CompanyListItem(
                c.id, c.profileStatus, c.mainIndustryCode, c.mainIndustryName, c.city, c.region)
            FROM Company c
            WHERE (:companyId IS NULL OR LOWER(c.id) LIKE LOWER(CONCAT('%', CAST(:companyId AS String), '%')) ESCAPE '!')
              AND (:userId IS NULL OR EXISTS (
                  SELECT 1 FROM UserCompanyAccess a WHERE a.id.userId = :userId AND a.id.companyId = c.id))
              AND (:city IS NULL OR LOWER(c.city) = LOWER(CAST(:city AS String)))
              AND (:mainIndustryCode IS NULL OR c.mainIndustryCode = :mainIndustryCode)
            """)
    Page<CompanyListItem> findCompanies(@Param("companyId") String companyId,
                                      @Param("city") String city,
                                      @Param("mainIndustryCode") String mainIndustryCode,
                                      @Param("userId") String userId,
                                      Pageable pageable);

    /**
     * Возвращает все компании указанной группы с их отраслями, по возрастанию идентификатора.
     */
    @Query("""
            SELECT c.id, c.profileStatus, c.mainIndustryCode, c.mainIndustryName
            FROM Company c WHERE c.groupId = :groupId ORDER BY c.id
            """)
    List<GroupCompany> findGroup(@Param("groupId") String groupId);
}
