package alpha.business.insight.core.repository;

import alpha.business.insight.core.dto.CounterpartyPaymentTotals;
import alpha.business.insight.core.model.CompanyPaymentSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface CompanyPaymentSummaryRepository extends JpaRepository<CompanyPaymentSummary, UUID> {
    /**
     * Объединяет входящие и исходящие платежи по контрагенту и возвращает крупнейших по обороту.
     * Собственные платежи исключаются; общий оборот рассчитывается до ограничения выдачи.
     * При равных суммах контрагенты упорядочиваются по идентификатору.
     */
    @Query(value = """
                WITH payments AS (
                    SELECT recipient_company_id AS company_id, 0::numeric AS incoming_payment_amount,
                           total_payment_amount AS outgoing_payment_amount
                    FROM company_payment_summaries
                    WHERE payer_company_id = :companyId AND recipient_company_id <> :companyId
                    UNION ALL
                    SELECT payer_company_id AS company_id, total_payment_amount AS incoming_payment_amount,
                           0::numeric AS outgoing_payment_amount
                    FROM company_payment_summaries
                    WHERE recipient_company_id = :companyId AND payer_company_id <> :companyId
                ), totals AS (
                    SELECT company_id, SUM(incoming_payment_amount) AS incoming_payment_amount,
                           SUM(outgoing_payment_amount) AS outgoing_payment_amount,
                           SUM(incoming_payment_amount + outgoing_payment_amount) AS total_payment_amount
                    FROM payments GROUP BY company_id
                )
                SELECT c.company_id, c.profile_status, c.main_industry_code, c.main_industry_name,
                       t.incoming_payment_amount, t.outgoing_payment_amount, t.total_payment_amount,
                       SUM(t.total_payment_amount) OVER () AS all_payment_amount
                FROM totals t JOIN companies c ON c.company_id = t.company_id
                ORDER BY t.total_payment_amount DESC, c.company_id LIMIT :limit
                """, nativeQuery = true)
    List<CounterpartyPaymentTotals> findCounterparties(@Param("companyId") String companyId, @Param("limit") int limit);
}
