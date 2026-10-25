package alpha.business.insight.core.service;

import alpha.business.insight.core.dto.CompanyContext;
import alpha.business.insight.core.dto.CompanyListItem;
import alpha.business.insight.core.dto.CompanyProfile;
import alpha.business.insight.core.dto.Concentration;
import alpha.business.insight.core.dto.CounterpartyPaymentTotals;
import alpha.business.insight.core.dto.GroupCompany;
import alpha.business.insight.core.dto.Counterparty;
import alpha.business.insight.core.model.CompanyProfileStatus;
import alpha.business.insight.core.repository.CompanyRepository;
import alpha.business.insight.core.repository.CompanyPaymentSummaryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Service
public class CompanyContextService {
    private final CompanyRepository companies;
    private final CompanyPaymentSummaryRepository payments;
    private final int topCounterparties;

    public CompanyContextService(CompanyRepository companies, CompanyPaymentSummaryRepository payments,
                                 @Value("${company-context.top-counterparties:5}") int topCounterparties) {
        if (topCounterparties < 1) {
            throw new IllegalArgumentException("Значение company-context.top-counterparties должно быть положительным");
        }
        this.companies = companies;
        this.payments = payments;
        this.topCounterparties = topCounterparties;
    }

    @Transactional(readOnly = true)
    public Page<CompanyListItem> getCompanies(String companyId, String city, String mainIndustryCode,
                                               Pageable pageable) {
        String id = normalizeFilter(companyId);
        if (id != null) {
            id = id.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        }
        return companies.findCompanies(id, normalizeFilter(city), normalizeFilter(mainIndustryCode), pageable);
    }

    private static String normalizeFilter(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Собирает профиль, группу и платёжное окружение из одного согласованного снимка БД.
     * Отсутствующие признаки перечисляет явно, не заменяя их предположениями.
     *
     * @throws ResponseStatusException если компания не найдена, со статусом 404
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CompanyContext getContext(String companyId) {
        CompanyProfile profile = companies.findById(companyId).map(CompanyProfile::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Компания не найдена"));
        List<String> missing = new ArrayList<>();
        if (profile.profileStatus() != CompanyProfileStatus.AVAILABLE) {
            missing.add("profile." + profile.profileStatus().name().toLowerCase(java.util.Locale.ROOT));
        }
        missing(missing, "profile.mainIndustryCode", profile.mainIndustryCode());
        missing(missing, "profile.mainIndustryName", profile.mainIndustryName());
        missing(missing, "profile.region", profile.region());
        missing(missing, "profile.revenue", profile.revenue());
        missing(missing, "profile.hasForeignTrade", profile.hasForeignTrade());
        if (Boolean.TRUE.equals(profile.hasForeignTrade())) {
            missing(missing, "profile.foreignTradeCountries", profile.foreignTradeCountries());
        }
        missing(missing, "profile.hasSettlementAccount", profile.hasSettlementAccount());
        missing(missing, "profile.hasBankGuarantee", profile.hasBankGuarantee());
        missing(missing, "profile.hasLoan", profile.hasLoan());
        missing(missing, "profile.hasLeasing", profile.hasLeasing());
        missing(missing, "profile.hasDeposit", profile.hasDeposit());
        missing(missing, "profile.hasPayrollProject", profile.hasPayrollProject());
        missing(missing, "profile.hasFactoring", profile.hasFactoring());

        List<GroupCompany> group = profile.groupId() == null || profile.groupId().isBlank()
                ? List.of() : companies.findGroup(profile.groupId());
        for (GroupCompany member : group) {
            missing(missing, "group." + member.companyId() + ".mainIndustryCode", member.mainIndustryCode());
        }

        List<CounterpartyPaymentTotals> rankedPayments = payments.findCounterparties(companyId, Math.max(topCounterparties, 3));
        BigDecimal total = rankedPayments.isEmpty() ? BigDecimal.ZERO : rankedPayments.getFirst().allPaymentAmount();
        if (rankedPayments.isEmpty()) {
            missing.add("payments");
        }
        if (total.signum() == 0) {
            missing.add("paymentShares");
        }
        List<Counterparty> counterparties = rankedPayments.stream().limit(topCounterparties).map(payment -> {
            missing(missing, "counterparties." + payment.companyId() + ".mainIndustryCode", payment.mainIndustryCode());
            return new Counterparty(payment.companyId(), CompanyProfileStatus.valueOf(payment.profileStatus()),
                    payment.mainIndustryCode(), payment.mainIndustryName(), payment.incomingPaymentAmount(),
                    payment.outgoingPaymentAmount(), payment.totalPaymentAmount(), share(payment.totalPaymentAmount(), total));
        }).toList();
        BigDecimal top1 = rankedPayments.isEmpty() ? BigDecimal.ZERO : rankedPayments.getFirst().totalPaymentAmount();
        BigDecimal top3 = rankedPayments.stream().limit(3).map(CounterpartyPaymentTotals::totalPaymentAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CompanyContext(profile, group, counterparties, total,
                new Concentration(share(top1, total), share(top3, total)), List.copyOf(missing));
    }

    private static void missing(List<String> missing, String path, Object value) {
        if (value == null || value instanceof String text && text.isBlank()) {
            missing.add(path);
        }
    }

    /**
     * Рассчитывает долю от полного оборота; при нулевом обороте доля неизвестна.
     */
    private static BigDecimal share(BigDecimal amount, BigDecimal total) {
        return total.signum() == 0 ? null : amount.divide(total, 8, RoundingMode.HALF_UP);
    }
}
