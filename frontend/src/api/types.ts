// Типы ответов бэкенда. Имена и форма совпадают с DTO в backend/core (…/core/dto)
// и с JSON-контрактом инсайта (InsightValidator). Менять вместе с бэком.

export type Role = 'COMPANY_DIRECTOR' | 'SYSTEM_ADMIN' | string;

export interface Me {
  id: string;
  username: string;
  roles: Role[];
}

export type CompanyProfileStatus = 'AVAILABLE' | 'MISSING' | 'CONFLICTING' | string;

/** CompanyProfile.java */
export interface CompanyProfile {
  companyId: string;
  profileStatus: CompanyProfileStatus;
  organizationType: string | null;
  ownershipType: string | null;
  ageRange: string | null;
  businessLifecycleStage: string | null;
  mainIndustryCode: string | null;
  mainIndustryName: string | null;
  negativeMark: boolean | null;
  employeeCountRangeFns: string | null;
  employeeCountRangeSpark: string | null;
  clientType: string | null;
  hasSettlementAccount: boolean | null;
  hasBankGuarantee: boolean | null;
  hasLoan: boolean | null;
  hasLeasing: boolean | null;
  hasDeposit: boolean | null;
  hasPayrollProject: boolean | null;
  hasForeignTrade: boolean | null;
  foreignTradeCountries: string | null;
  hasFactoring: boolean | null;
  // Выручка; значение 1000 — техническая заглушка, показываем как «нет данных».
  revenue: number | null;
  groupId: string | null;
  city: string | null;
  region: string | null;
}

/** GroupCompany.java */
export interface GroupCompany {
  companyId: string;
  profileStatus: CompanyProfileStatus;
  mainIndustryCode: string | null;
  mainIndustryName: string | null;
}

/** Counterparty.java — направление платежей относительно компании. */
export interface Counterparty {
  companyId: string;
  profileStatus: CompanyProfileStatus;
  mainIndustryCode: string | null;
  mainIndustryName: string | null;
  incomingPaymentAmount: number | null;
  outgoingPaymentAmount: number | null;
  totalPaymentAmount: number | null;
  paymentShare: number | null; // 0..1
}

/** Concentration.java — доли 0..1, null при нулевом обороте. */
export interface Concentration {
  top1Share: number | null;
  top3Share: number | null;
}

/** CompanyContext.java */
export interface CompanyContext {
  profile: CompanyProfile;
  group: GroupCompany[];
  counterparties: Counterparty[];
  totalPaymentAmount: number | null;
  concentration: Concentration;
  missingData: string[];
}

export type EventSourceType = 'TEXT' | 'URL' | 'PDF' | string;
export type EventStatus = 'QUEUED' | 'PROCESSING' | 'READY' | 'FAILED' | string;

/** EventReadDto.java */
export interface EventRead {
  id: string;
  title: string;
  sourceType: EventSourceType;
  sourceRef: string | null;
  status: EventStatus;
  createdAt: string; // ISO Instant
  materialsVersion: number | null;
}

// ---- JSON-контракт инсайта (InsightValidator) ----

export type Confidence = 'low' | 'medium' | 'high';

export interface SourcedText {
  text: string;
  fragment_id: string;
}

export interface AiInference {
  text: string;
  based_on: string[]; // fragment_id[]
  confidence: Confidence;
}

export interface TextItem {
  text: string;
}

export interface SourceRef {
  fragment_id: string;
  document_id: string | null;
  page: number | null;
  url?: string;
}

/** Ответ POST/GET …/insight (Map в бэке). Любое поле может прийти пустым массивом. */
export interface Insight {
  facts: SourcedText[];
  bank_view: SourcedText[];
  ai_inference: AiInference[];
  hypotheses: TextItem[];
  risks: TextItem[];
  opportunities: TextItem[];
  actions: TextItem[];
  features_used: string[];
  missing_data: string[];
  sources: SourceRef[];
  trace_id: string;
  cost_rub?: number;
}

// ---- Пагинация и списки (бэк отдаёт Spring Page) ----

/** Spring Data Page<T> — отдаётся ручками списков. */
export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

/** CompanyListItem.java — краткая карточка компании в списке. */
export interface CompanyListItem {
  companyId: string;
  profileStatus: CompanyProfileStatus;
  mainIndustryCode: string | null;
  mainIndustryName: string | null;
  city: string | null;
  region: string | null;
}

/** EventListItem.java — краткая карточка события в списке. */
export interface EventListItem {
  id: string;
  title: string;
  sourceType: EventSourceType;
  status: EventStatus;
  createdAt: string;
}

// ---- Производные для интерфейса (не из бэка) ----

/** Значимость события для компании — вычисляется фронтом по контексту и инсайту. */
export type ImpactLevel = 'high' | 'medium' | 'indirect' | 'opportunity';

export interface DocumentMetadata {
  documentId: string;
  title: string;
  fileName: string;
  sizeBytes: number;
  sha256: string;
  uploadedAt: string;
  publicationDate: string | null;
  chunkCount: number;
  schemaVersion: number;
  embeddingModel: string;
  embeddingDimension: number;
  stub: boolean;
}
