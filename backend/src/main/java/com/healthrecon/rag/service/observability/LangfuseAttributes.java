package com.healthrecon.rag.service.observability;

/**
 * OpenTelemetry attribute names used by the Langfuse integration. Langfuse maps
 * {@code gen_ai.*}, {@code input.value}/{@code output.value} and
 * {@code langfuse.observation.metadata.*} onto its model, input/output and
 * metadata fields; {@code rag.*} and {@code eval.*} keys are freely chosen and
 * end up searchable in the trace UI.
 */
public final class LangfuseAttributes {

    private LangfuseAttributes() {
    }

    public static final String GEN_AI_SYSTEM = "gen_ai.system";
    public static final String GEN_AI_REQUEST_MODEL = "gen_ai.request.model";
    public static final String GEN_AI_USAGE_INPUT_TOKENS = "gen_ai.usage.input_tokens";
    public static final String GEN_AI_USAGE_OUTPUT_TOKENS = "gen_ai.usage.output_tokens";

    public static final String LANGFUSE_OBSERVATION_LEVEL = "langfuse.observation.level";
    public static final String LANGFUSE_OBSERVATION_METADATA_PREFIX = "langfuse.observation.metadata.";

    public static final String LANGFUSE_TRACE_NAME = "langfuse.trace.name";
    public static final String LANGFUSE_USER_ID = "langfuse.user.id";
    public static final String LANGFUSE_SESSION_ID = "langfuse.session.id";

    public static final String INPUT_VALUE = "input.value";
    public static final String OUTPUT_VALUE = "output.value";

    public static final String RAG_DOCUMENT_SET_ID = "rag.document_set_id";
    public static final String RAG_EMBEDDING_INPUTS = "rag.embedding.inputs";
    public static final String RAG_EMBEDDING_OUTPUTS = "rag.embedding.outputs";

    public static final String EVAL_VERDICT = "eval.verdict";
    public static final String EVAL_COVERAGE = "eval.coverage";
    public static final String EVAL_REFUSED = "eval.refused";
}