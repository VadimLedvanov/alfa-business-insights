package alpha.business.insight.core.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Агрегированные платежи между компаниями.
 */
@Entity
@Table(name = "company_payment_summaries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CompanyPaymentSummary {
    /**
     * Идентификатор платёжного агрегата.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Компания-плательщик.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payer_company_id", nullable = false)
    private Company payerCompany;

    /**
     * Компания-получатель.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_company_id", nullable = false)
    private Company recipientCompany;

    /**
     * Общая сумма платежей между плательщиком и получателем.
     */
    @Column(name = "total_payment_amount", nullable = false, columnDefinition = "numeric")
    private BigDecimal totalPaymentAmount;

    /**
     * Количество платежей между плательщиком и получателем.
     */
    @Column(name = "payment_count", nullable = false)
    private Long paymentCount;
}
