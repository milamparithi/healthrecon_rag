package com.healthrecon.rag.service.semanticcache;

import com.healthrecon.rag.api.dto.SourceResponse;

import java.util.List;

/**
 * A cache hit: the previously generated grounded answer together with the
 * sources it cited and the similarity of the matched question embedding.
 */
public record CachedAnswer(String answer, List<SourceResponse> sources, double similarity) {
}