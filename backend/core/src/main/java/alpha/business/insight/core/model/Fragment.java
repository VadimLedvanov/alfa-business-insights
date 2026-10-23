package alpha.business.insight.core.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Фрагмент исходного материала события.
 */
@Entity
@Table(name = "fragments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Fragment {
    /**
     * Идентификатор фрагмента, общий с точкой в Qdrant.
     */
    @Id
    private UUID id;

    /**
     * Событие, которому принадлежит фрагмент.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    /**
     * Номер страницы PDF; отсутствует для текста и URL.
     */
    @Column(name = "page")
    private Integer page;

    /**
     * Порядковый номер фрагмента внутри события, начиная с нуля.
     */
    @Column(name = "ordinal", nullable = false)
    private Integer ordinal;

    /**
     * Текст фрагмента исходного материала.
     */
    @Column(name = "text", nullable = false, columnDefinition = "text")
    private String text;
}
