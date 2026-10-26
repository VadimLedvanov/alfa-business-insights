package alpha.business.insight.core.dto;

import alpha.business.insight.core.model.EventSourceType;
import alpha.business.insight.core.model.EventStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Событие из персонального топа с оценкой релевантности, эффектом для компании, объяснением
 * и состоянием сохранённого разбора.
 */
public record EventCandidate(UUID id, String title, EventSourceType sourceType, EventStatus status,
                             Instant createdAt, double relevanceScore, boolean insightGenerated,
                             String effect, List<String> why) {}