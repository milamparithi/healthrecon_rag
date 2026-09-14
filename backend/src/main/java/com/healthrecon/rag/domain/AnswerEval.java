package com.healthrecon.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * A captured chat output queued for review (human-in-the-loop evaluation).
 * Every grounded answer is stored; {@code autoFlags} marks outputs that failed
 * an automated check and {@code sampled} marks randomly-selected rest, so review
 * effort is directed without losing a base-quality signal.
 */
@Entity
@Table(name = "answer_eval")
public class AnswerEval {

    public static final String ORIGIN_CHAT = "CHAT";
    public static final String ORIGIN_MANUAL = "MANUAL";

    public static final String REVIEW_PENDING = "PENDING";
    public static final String REVIEW_REVIEWED = "REVIEWED";
    public static final String REVIEW_DISMISSED = "DISMISSED";

    public static final String VERDICT_ACCEPT = "ACCEPT";
    public static final String VERDICT_REWORD = "REWORD";
    public static final String VERDICT_REJECT = "REJECT";

    public static final String FLAG_GUARDRAIL_REFUSAL = "GUARDRAIL_REFUSAL";
    public static final String FLAG_LOW_COVERAGE = "LOW_COVERAGE";
    public static final String FLAG_NO_SOURCES = "NO_SOURCES";

    public static final String FLAGS_SEPARATOR = ",";

    @Id
    private UUID id;

    @Column(name = "doc_set_id", nullable = false)
    private UUID docSetId;

    @Column(name = "chat_message_id")
    private UUID chatMessageId;

    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(nullable = false)
    private String question;

    @Column(nullable = false)
    private String answer;

    @Column
    private String sources;

    @Column(nullable = false)
    private String origin;

    @Column(name = "auto_flags")
    private String autoFlags;

    @Column(name = "coverage_score")
    private Double coverageScore;

    @Column(nullable = false)
    private boolean sampled;

    @Column(name = "review_status", nullable = false)
    private String reviewStatus;

    @Column
    private String verdict;

    @Column
    private Integer rating;

    @Column
    private String comment;

    @Column(name = "corrected_answer")
    private String correctedAnswer;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AnswerEval() {
    }

    public AnswerEval(UUID id, UUID docSetId, UUID chatMessageId, UUID conversationId,
                      String question, String answer, String sourcesJson, String origin,
                      List<String> autoFlags, Double coverageScore, boolean sampled,
                      Instant createdAt) {
        this.id = id;
        this.docSetId = docSetId;
        this.chatMessageId = chatMessageId;
        this.conversationId = conversationId;
        this.question = question;
        this.answer = answer;
        this.sources = sourcesJson;
        this.origin = origin;
        this.autoFlags = autoFlags == null || autoFlags.isEmpty() ? null : String.join(FLAGS_SEPARATOR, autoFlags);
        this.coverageScore = coverageScore;
        this.sampled = sampled;
        this.reviewStatus = REVIEW_PENDING;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocSetId() {
        return docSetId;
    }

    public UUID getChatMessageId() {
        return chatMessageId;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public String getQuestion() {
        return question;
    }

    public String getAnswer() {
        return answer;
    }

    public String getSources() {
        return sources;
    }

    public String getOrigin() {
        return origin;
    }

    public List<String> flags() {
        if (autoFlags == null || autoFlags.isBlank()) {
            return List.of();
        }
        return Arrays.asList(autoFlags.split(FLAGS_SEPARATOR));
    }

    public String getAutoFlags() {
        return autoFlags;
    }

    public Double getCoverageScore() {
        return coverageScore;
    }

    public boolean isSampled() {
        return sampled;
    }

    public String getReviewStatus() {
        return reviewStatus;
    }

    public String getVerdict() {
        return verdict;
    }

    public Integer getRating() {
        return rating;
    }

    public String getComment() {
        return comment;
    }

    public String getCorrectedAnswer() {
        return correctedAnswer;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean hasFlags() {
        return autoFlags != null && !autoFlags.isBlank();
    }

    public void review(String verdict, Integer rating, String comment, String correctedAnswer, Instant now) {
        if (!List.of(VERDICT_ACCEPT, VERDICT_REWORD, VERDICT_REJECT).contains(verdict)) {
            throw new IllegalArgumentException("verdict must be ACCEPT, REWORD or REJECT");
        }
        if (rating != null && (rating < 1 || rating > 5)) {
            throw new IllegalArgumentException("rating must be between 1 and 5");
        }
        if (VERDICT_REJECT.equals(verdict) || VERDICT_REWORD.equals(verdict)) {
            if (correctedAnswer == null || correctedAnswer.isBlank()) {
                throw new IllegalArgumentException("A corrected answer is required when the verdict is REWORD or REJECT");
            }
        }
        this.reviewStatus = REVIEW_REVIEWED;
        this.verdict = verdict;
        this.rating = rating;
        this.comment = comment;
        this.correctedAnswer = correctedAnswer == null ? null : correctedAnswer.strip();
        this.reviewedAt = now;
    }

    public void dismiss(Instant now) {
        this.reviewStatus = REVIEW_DISMISSED;
        this.verdict = null;
        this.rating = null;
        this.comment = null;
        this.correctedAnswer = null;
        this.reviewedAt = now;
    }
}