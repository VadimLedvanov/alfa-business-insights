// Демо-данные в формате DTO бэкенда. Используются, пока у фронта нет живого API
// (список компаний отсутствует как ручка; инсайты показываем из заготовленных по контракту).
// Все компании и тексты вымышленные; значения приближены к сценарию кейса (ставка ЦБ 14%).
// Источник «Позиция банка» ссылается на реальное название материала ЦМИ из папки аналитики.

import type { CompanyContext, EventRead, Insight, Me } from './types';

export const DEMO_USERS: Record<string, Me> = {
  director: { id: 'u-director', username: 'Андрей · руководитель казначейства', roles: ['COMPANY_DIRECTOR'] },
  sysadmin: { id: 'u-admin', username: 'Администратор материалов', roles: ['SYSTEM_ADMIN'] },
};

// Идентификаторы компаний (обезличенные, как в датасете кейса).
export const DEMO_COMPANY_IDS = ['U-104233', 'U-215096', 'U-087512'] as const;

const ctx = (c: CompanyContext): CompanyContext => c;

export const DEMO_COMPANIES: Record<string, CompanyContext> = {
  // Грузоперевозки: есть лизинг, плотная сеть связей. Влияние ставки прямое.
  'U-104233': ctx({
    profile: {
      companyId: 'U-104233', profileStatus: 'AVAILABLE',
      organizationType: 'ООО', ownershipType: 'Частная собственность',
      ageRange: '10+ лет', businessLifecycleStage: 'Зрелый бизнес',
      mainIndustryCode: '49.41', mainIndustryName: 'Грузоперевозки',
      negativeMark: false, employeeCountRangeFns: '250–500', employeeCountRangeSpark: '250–500',
      clientType: 'CLIENT',
      hasSettlementAccount: true, hasBankGuarantee: false, hasLoan: false,
      hasLeasing: true, hasDeposit: true, hasPayrollProject: true,
      hasForeignTrade: false, foreignTradeCountries: null, hasFactoring: false,
      revenue: 24_800_000_000, groupId: 'G-3307', city: 'Санкт-Петербург', region: 'Санкт-Петербург',
    },
    group: [
      { companyId: 'U-104233', profileStatus: 'AVAILABLE', mainIndustryCode: '49.41', mainIndustryName: 'Грузоперевозки' },
      { companyId: 'U-331902', profileStatus: 'AVAILABLE', mainIndustryCode: '52.10', mainIndustryName: 'Складское хозяйство' },
    ],
    counterparties: [
      { companyId: 'U-215096', profileStatus: 'AVAILABLE', mainIndustryCode: '41.20', mainIndustryName: 'Строительство', incomingPaymentAmount: 48_200_000, outgoingPaymentAmount: 3_100_000, totalPaymentAmount: 51_300_000, paymentShare: 0.21 },
      { companyId: 'U-087512', profileStatus: 'AVAILABLE', mainIndustryCode: '20.15', mainIndustryName: 'Производство удобрений', incomingPaymentAmount: 36_400_000, outgoingPaymentAmount: 1_900_000, totalPaymentAmount: 38_300_000, paymentShare: 0.16 },
      { companyId: 'U-412007', profileStatus: 'AVAILABLE', mainIndustryCode: '46.71', mainIndustryName: 'Оптовая торговля топливом', incomingPaymentAmount: 2_200_000, outgoingPaymentAmount: 29_800_000, totalPaymentAmount: 32_000_000, paymentShare: 0.13 },
    ],
    totalPaymentAmount: 243_000_000, concentration: { top1Share: 0.21, top3Share: 0.50 }, missingData: [],
  }),
  // Строительство: есть кредит. Влияние ставки прямое.
  'U-215096': ctx({
    profile: {
      companyId: 'U-215096', profileStatus: 'AVAILABLE',
      organizationType: 'АО', ownershipType: 'Частная собственность',
      ageRange: '10+ лет', businessLifecycleStage: 'Зрелый бизнес',
      mainIndustryCode: '41.20', mainIndustryName: 'Строительство зданий',
      negativeMark: false, employeeCountRangeFns: '500–1000', employeeCountRangeSpark: '500–1000',
      clientType: 'CLIENT',
      hasSettlementAccount: true, hasBankGuarantee: true, hasLoan: true,
      hasLeasing: false, hasDeposit: false, hasPayrollProject: true,
      hasForeignTrade: false, foreignTradeCountries: null, hasFactoring: false,
      revenue: 31_500_000_000, groupId: null, city: 'Екатеринбург', region: 'Свердловская область',
    },
    group: [],
    counterparties: [
      { companyId: 'U-104233', profileStatus: 'AVAILABLE', mainIndustryCode: '49.41', mainIndustryName: 'Грузоперевозки', incomingPaymentAmount: 3_100_000, outgoingPaymentAmount: 48_200_000, totalPaymentAmount: 51_300_000, paymentShare: 0.19 },
      { companyId: 'U-551020', profileStatus: 'AVAILABLE', mainIndustryCode: '23.63', mainIndustryName: 'Производство бетона', incomingPaymentAmount: 1_200_000, outgoingPaymentAmount: 41_000_000, totalPaymentAmount: 42_200_000, paymentShare: 0.16 },
    ],
    totalPaymentAmount: 268_000_000, concentration: { top1Share: 0.19, top3Share: 0.44 }, missingData: [],
  }),
  // Удобрения: ВЭД, своего кредита нет. Влияние ставки косвенное — через связанную компанию по платежам.
  'U-087512': ctx({
    profile: {
      companyId: 'U-087512', profileStatus: 'AVAILABLE',
      organizationType: 'ООО', ownershipType: 'Частная собственность',
      ageRange: '5–10 лет', businessLifecycleStage: 'Рост',
      mainIndustryCode: '20.15', mainIndustryName: 'Производство удобрений',
      negativeMark: false, employeeCountRangeFns: '100–250', employeeCountRangeSpark: '100–250',
      clientType: 'CLIENT',
      hasSettlementAccount: true, hasBankGuarantee: false, hasLoan: false,
      hasLeasing: false, hasDeposit: false, hasPayrollProject: false,
      hasForeignTrade: true, foreignTradeCountries: 'Казахстан, Беларусь', hasFactoring: false,
      revenue: 18_300_000_000, groupId: 'G-8821', city: 'Казань', region: 'Республика Татарстан',
    },
    group: [
      { companyId: 'U-087512', profileStatus: 'AVAILABLE', mainIndustryCode: '20.15', mainIndustryName: 'Производство удобрений' },
      { companyId: 'U-090418', profileStatus: 'AVAILABLE', mainIndustryCode: '46.75', mainIndustryName: 'Оптовая торговля химической продукцией' },
    ],
    counterparties: [
      { companyId: 'U-104233', profileStatus: 'AVAILABLE', mainIndustryCode: '49.41', mainIndustryName: 'Грузоперевозки', incomingPaymentAmount: 1_900_000, outgoingPaymentAmount: 36_400_000, totalPaymentAmount: 38_300_000, paymentShare: 0.24 },
      { companyId: 'U-215096', profileStatus: 'AVAILABLE', mainIndustryCode: '41.20', mainIndustryName: 'Строительство', incomingPaymentAmount: 22_600_000, outgoingPaymentAmount: 900_000, totalPaymentAmount: 23_500_000, paymentShare: 0.15 },
    ],
    totalPaymentAmount: 157_000_000, concentration: { top1Share: 0.24, top3Share: 0.52 }, missingData: ['REVENUE_PARTIAL'],
  }),
};

// Одно событие (ключевая ставка) + одно отраслевое. У второго есть материал банка.
export const DEMO_EVENTS: EventRead[] = [
  { id: 'evt-rate-14', title: 'Банк России сохранил ключевую ставку на уровне 14%', sourceType: 'TEXT', sourceRef: 'ЦБ РФ, пресс-релиз', status: 'READY', createdAt: '2026-09-11T10:30:00Z', materialsVersion: 3 },
  { id: 'evt-consumption', title: 'ЦМИ: потребительский спрос вблизи пика', sourceType: 'PDF', sourceRef: 'ALFA-consumption.pdf', status: 'READY', createdAt: '2026-10-17T16:40:00Z', materialsVersion: 3 },
];

// Источники-фрагменты (общие для демо).
const SRC = {
  news: 'f1000000-0000-0000-0000-000000000001',
  bankRate: 'f1000000-0000-0000-0000-000000000002',
  bankConsumption: 'f1000000-0000-0000-0000-000000000003',
  profile: 'f1000000-0000-0000-0000-000000000004',
};

const bankRateView = {
  text: 'Пауза в цикле снижения ставки: денежно-кредитные условия остаются жёсткими, стоимость заёмного финансирования в ближайшей перспективе не снижается.',
  fragment_id: SRC.bankRate,
};
const sourcesRate = [
  { fragment_id: SRC.news, document_id: null, page: null },
  { fragment_id: SRC.bankRate, document_id: 'doc-policy-rate-11sep', page: 4, url: '/api/documents/doc-policy-rate-11sep/file#page=4' },
  { fragment_id: SRC.profile, document_id: null, page: null },
];

// Инсайты по событию ставки — три разных вывода для одной новости.
export const DEMO_INSIGHTS: Record<string, Insight> = {
  'U-104233:evt-rate-14': {
    facts: [{ text: '11 сентября 2026 года Банк России сохранил ключевую ставку на уровне 14,0% годовых — первая пауза после серии снижений.', fragment_id: SRC.news }],
    bank_view: [bankRateView],
    ai_inference: [{
      text: 'У компании есть действующий лизинг. Стоимость нового и рефинансируемого лизинга следует за ключевой ставкой, поэтому в ближайшие месяцы она вряд ли снизится. Условия действующих договоров нужно проверить отдельно.',
      based_on: [SRC.bankRate, SRC.profile], confidence: 'medium',
    }],
    hypotheses: [{ text: 'Если в договорах лизинга есть привязка к плавающей ставке, ежемесячные платежи могут не снизиться вслед за ожиданиями рынка.' }],
    risks: [{ text: 'Удорожание нового финансирования при обновлении автопарка и складской техники.' }],
    opportunities: [{ text: 'Зафиксировать ставку по планируемым сделкам сейчас, не откладывая в ожидании удешевления.' }],
    actions: [
      { text: 'Проверить условия и даты пересмотра ставки по действующим договорам лизинга.' },
      { text: 'Обсудить с казначейством сценарии финансирования обновления парка на горизонте 6–12 месяцев.' },
    ],
    features_used: ['Лизинг', 'Грузоперевозки', 'Плотная сеть контрагентов'],
    missing_data: ['Неизвестны суммы, тип ставки и сроки пересмотра по договорам лизинга — рассчитать изменение расходов нельзя.'],
    sources: sourcesRate,
    trace_id: 'trace-104233-rate-14',
  },
  'U-215096:evt-rate-14': {
    facts: [{ text: '11 сентября 2026 года Банк России сохранил ключевую ставку на уровне 14,0% годовых — первая пауза после серии снижений.', fragment_id: SRC.news }],
    bank_view: [bankRateView],
    ai_inference: [{
      text: 'У компании есть кредитные продукты. Сохранение ставки означает, что стоимость обслуживания действующего долга и нового финансирования проектов остаётся высокой.',
      based_on: [SRC.bankRate, SRC.profile], confidence: 'high',
    }],
    hypotheses: [{ text: 'Если часть проектов финансируется за счёт заёмных средств, высокая ставка может сдвинуть сроки запуска или снизить маржу.' }],
    risks: [{ text: 'Сохранение высокой финансовой нагрузки по действующим кредитам и гарантиям.' }],
    opportunities: [{ text: 'Пересмотреть график выборки по кредитным линиям под более дорогие деньги.' }],
    actions: [
      { text: 'Сверить ставки и графики платежей по действующим кредитам.' },
      { text: 'Оценить чувствительность маржи проектов к стоимости финансирования.' },
    ],
    features_used: ['Кредитные продукты', 'Банковские гарантии', 'Строительство'],
    missing_data: ['Неизвестны суммы и ставки по конкретным кредитным договорам.'],
    sources: sourcesRate,
    trace_id: 'trace-215096-rate-14',
  },
  'U-087512:evt-rate-14': {
    facts: [{ text: '11 сентября 2026 года Банк России сохранил ключевую ставку на уровне 14,0% годовых — первая пауза после серии снижений.', fragment_id: SRC.news }],
    bank_view: [bankRateView],
    ai_inference: [{
      text: 'У самой компании кредит не отмечен. Но крупный плательщик по платежам — строительная компания — имеет кредитные продукты. Рост стоимости его финансирования может отразиться на сроках и условиях расчётов с вашей компанией. Это косвенное влияние через связь по платежам.',
      based_on: [SRC.bankRate, SRC.profile], confidence: 'low',
    }],
    hypotheses: [{ text: 'При высокой стоимости денег связанный по платежам контрагент может удлинить отсрочку оплаты — это гипотеза, требующая проверки.' }],
    risks: [{ text: 'Возможное увеличение отсрочки платежей со стороны крупного плательщика.' }],
    opportunities: [{ text: 'Заранее согласовать условия расчётов с ключевыми плательщиками.' }],
    actions: [
      { text: 'Уточнить у крупного плательщика планы по срокам оплаты на ближайший квартал.' },
      { text: 'Проверить запас прочности по оборотным средствам на случай удлинения отсрочек.' },
    ],
    features_used: ['ВЭД, страна: Казахстан, Беларусь', 'Связь по платежам со строительной компанией', 'Группа компаний'],
    missing_data: ['Нет условий кредита связанной компании и ваших договоров — масштаб влияния подтвердить нельзя.'],
    sources: sourcesRate,
    trace_id: 'trace-087512-rate-14',
  },
};

const bankConsumptionView = {
  text: 'Потребительский спрос вблизи пика: дальнейший рост ограничен, во втором полугодии возможна стабилизация.',
  fragment_id: SRC.bankConsumption,
};
const sourcesConsumption = [
  { fragment_id: SRC.news, document_id: null, page: null },
  { fragment_id: SRC.bankConsumption, document_id: 'doc-consumption', page: 2, url: '/api/documents/doc-consumption/file#page=2' },
  { fragment_id: SRC.profile, document_id: null, page: null },
];

// Второе событие (потребительский спрос) — для части компаний это возможность.
Object.assign(DEMO_INSIGHTS, {
  'U-104233:evt-consumption': {
    facts: [{ text: 'ЦМИ: потребительский спрос находится вблизи пика после устойчивого роста в первом полугодии.', fragment_id: SRC.news }],
    bank_view: [bankConsumptionView],
    ai_inference: [{
      text: 'Рост спроса у торговых и производственных клиентов может увеличить объём перевозок — это возможность для расширения заказов. Нужно подтвердить спрос у ключевых плательщиков.',
      based_on: [SRC.bankConsumption, SRC.profile], confidence: 'medium',
    }],
    hypotheses: [{ text: 'Если спрос у клиентов из розницы продолжит расти, может потребоваться дополнительный транспорт и склады.' }],
    risks: [{ text: 'Пик спроса означает, что дальнейшего роста заказов может не быть.' }],
    opportunities: [{ text: 'Обсудить с крупными плательщиками из розницы расширение объёмов перевозок.' }],
    actions: [{ text: 'Уточнить планы ключевых клиентов по отгрузкам на ближайший квартал.' }],
    features_used: ['Грузоперевозки', 'Плательщики из розничной торговли', 'Складское хозяйство в группе'],
    missing_data: ['Нет подтверждённых планов клиентов по объёмам — потенциал роста оценить точно нельзя.'],
    sources: sourcesConsumption,
    trace_id: 'trace-104233-consumption',
  },
  'U-215096:evt-consumption': {
    facts: [{ text: 'ЦМИ: потребительский спрос находится вблизи пика после устойчивого роста в первом полугодии.', fragment_id: SRC.news }],
    bank_view: [bankConsumptionView],
    ai_inference: [{
      text: 'Для строительной компании сигнал нейтрален: спрос на жильё и коммерческую недвижимость связан со спросом косвенно. Прямого эффекта по профилю не видно.',
      based_on: [SRC.bankConsumption, SRC.profile], confidence: 'low',
    }],
    hypotheses: [{ text: 'Охлаждение потребления в перспективе может затронуть спрос на коммерческую недвижимость.' }],
    risks: [{ text: 'При стабилизации спроса возможна пауза в новых проектах заказчиков.' }],
    opportunities: [],
    actions: [{ text: 'Следить за портфелем заказов и сроками запуска проектов.' }],
    features_used: ['Строительство', 'Кредитные продукты'],
    missing_data: ['Связь спроса с портфелем заказов компании по данным профиля не прослеживается.'],
    sources: sourcesConsumption,
    trace_id: 'trace-215096-consumption',
  },
  'U-087512:evt-consumption': {
    facts: [{ text: 'ЦМИ: потребительский спрос находится вблизи пика после устойчивого роста в первом полугодии.', fragment_id: SRC.news }],
    bank_view: [bankConsumptionView],
    ai_inference: [{
      text: 'Рост потребления поддерживает спрос на продукцию пищевой промышленности, а значит и на сельхозсырьё и удобрения — это возможность для компании.',
      based_on: [SRC.bankConsumption, SRC.profile], confidence: 'medium',
    }],
    hypotheses: [{ text: 'Спрос может поддержать закупки удобрений аграрными покупателями в следующем сезоне.' }],
    risks: [{ text: 'Сигнал отраслевой и может не относиться к конкретному сегменту компании.' }],
    opportunities: [{ text: 'Проверить потребности аграрных покупателей и доступные объёмы производства.' }],
    actions: [{ text: 'Уточнить план закупок у ключевых покупателей на следующий сезон.' }],
    features_used: ['Производство удобрений', 'ВЭД, страна: Казахстан, Беларусь', 'Группа компаний'],
    missing_data: ['Нет данных о сегментах спроса конкретных покупателей.'],
    sources: sourcesConsumption,
    trace_id: 'trace-087512-consumption',
  },
} satisfies Record<string, Insight>);

export function stubInsight(companyId: string, eventId: string): Insight | null {
  return DEMO_INSIGHTS[`${companyId}:${eventId}`] ?? null;
}
